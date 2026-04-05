package dev.hippodid.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.HippoDidException;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP tools for character template management.
 *
 * <p>Tools: create_character_template, list_character_templates,
 * get_character_template, preview_character_template, clone_character_template.
 */
public final class TemplateToolProvider implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(TemplateToolProvider.class);

    private final HippoDidClient client;
    private final ObjectMapper objectMapper;

    public TemplateToolProvider(HippoDidClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(
                createTemplateTool(),
                listTemplatesTool(),
                getTemplateTool(),
                previewTemplateTool(),
                cloneTemplateTool());
    }

    private McpServerFeatures.SyncToolSpecification createTemplateTool() {
        String schema = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Template name"},
                  "description":{"type":"string","description":"Template description"},
                  "categories":{"type":"array","items":{"type":"object","properties":{
                    "categoryName":{"type":"string"},
                    "purpose":{"type":"string"}
                  }},"description":"Category schema definitions"},
                  "fieldMappings":{"type":"array","items":{"type":"object","properties":{
                    "sourceColumn":{"type":"string","description":"Column name in CSV/data source"},
                    "targetField":{"type":"string","description":"Target field (e.g. name, description)"}
                  }},"description":"How data columns map to character fields"}
                },"required":["name"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("create_character_template",
                        "Create a reusable template for batch character creation. Templates define "
                        + "the category schema, field definitions, default values, and column mappings "
                        + "that drive how external data rows are materialized into characters. Call "
                        + "this when setting up a repeatable import pipeline — e.g. importing "
                        + "customer profiles, product catalogs, or NPC definitions from a spreadsheet.",
                        schema),
                (exchange, args) -> {
                    try {
                        String name = stringArg(args, "name");
                        String description = optionalStringArg(args, "description").orElse("");
                        List<Map<String, Object>> categories = parseObjectList(args.get("categories"));
                        List<Map<String, Object>> fieldMappings = parseObjectList(args.get("fieldMappings"));

                        Map<String, Object> template = client.templates()
                                .create(name, description, categories, fieldMappings);

                        McpOperationStatus status = McpOperationStatus.fallback("create_character_template");
                        return toResultWithStatus(template, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification listTemplatesTool() {
        String schema = """
                {"type":"object","properties":{}}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("list_character_templates",
                        "List all character templates available for this tenant. Call this to "
                        + "discover existing templates before creating characters from them or "
                        + "starting a batch import. Templates define the schema and mappings "
                        + "for batch character creation.", schema),
                (exchange, args) -> {
                    try {
                        List<Map<String, Object>> templates = client.templates().list();
                        McpOperationStatus status = McpOperationStatus.fallback("list_character_templates");
                        return toListResultWithStatus(templates, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification getTemplateTool() {
        String schema = """
                {"type":"object","properties":{
                  "template_id":{"type":"string","description":"Character template UUID"}
                },"required":["template_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("get_character_template",
                        "Get full details of a character template including its category schema, "
                        + "field mappings, and default values. Call this to inspect a template "
                        + "before using it for batch creation or to verify its configuration.",
                        schema),
                (exchange, args) -> {
                    try {
                        String templateId = stringArg(args, "template_id");
                        Map<String, Object> template = client.templates().get(templateId);
                        McpOperationStatus status = McpOperationStatus.fallback("get_character_template");
                        return toResultWithStatus(template, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification previewTemplateTool() {
        String schema = """
                {"type":"object","properties":{
                  "template_id":{"type":"string","description":"Character template UUID"},
                  "sample_row":{"type":"object","additionalProperties":{"type":"string"},
                    "description":"Sample data row to preview (key-value pairs matching template field mappings)"}
                },"required":["template_id","sample_row"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("preview_character_template",
                        "Dry-run a template with sample data to see what character would be "
                        + "created — without persisting anything. Call this to verify field "
                        + "mappings and default values are correct before running a batch import. "
                        + "Provide a sample row that mimics your CSV or data source columns.",
                        schema),
                (exchange, args) -> {
                    try {
                        String templateId = stringArg(args, "template_id");
                        @SuppressWarnings("unchecked")
                        Map<String, String> sampleRow = args.get("sample_row") instanceof Map<?, ?> m
                                ? toStringMap(m) : Map.of();

                        Map<String, Object> preview = client.templates().preview(templateId, sampleRow);
                        McpOperationStatus status = McpOperationStatus.fallback("preview_character_template");
                        return toResultWithStatus(preview, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification cloneTemplateTool() {
        String schema = """
                {"type":"object","properties":{
                  "template_id":{"type":"string","description":"Character template UUID to clone"}
                },"required":["template_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("clone_character_template",
                        "Clone an existing character template (including pre-built ones) to "
                        + "create a customizable copy. Call this when you want to start from "
                        + "an existing template's schema and modify it, rather than building "
                        + "from scratch.", schema),
                (exchange, args) -> {
                    try {
                        String templateId = stringArg(args, "template_id");
                        Map<String, Object> cloned = client.templates().clone(templateId);
                        McpOperationStatus status = McpOperationStatus.fallback("clone_character_template");
                        return toResultWithStatus(cloned, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private CallToolResult toResultWithStatus(Map<String, Object> payload, McpOperationStatus status) {
        try {
            Map<String, Object> map = new LinkedHashMap<>(payload);
            map.put("_status", status.toMap());
            String json = objectMapper.writeValueAsString(map);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (Exception e) {
            log.warn("Serialization failed", e);
            return McpToolErrorMapper.toErrorResult("SerializationError", e.getMessage());
        }
    }

    private CallToolResult toListResultWithStatus(List<?> items, McpOperationStatus status) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("results", items);
            map.put("_status", status.toMap());
            String json = objectMapper.writeValueAsString(map);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (Exception e) {
            log.warn("Serialization failed", e);
            return McpToolErrorMapper.toErrorResult("SerializationError", e.getMessage());
        }
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : "";
    }

    private static Optional<String> optionalStringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null && !val.toString().isBlank() ? Optional.of(val.toString()) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseObjectList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private static Map<String, String> toStringMap(Map<?, ?> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null) {
                result.put(k.toString(), v.toString());
            }
        });
        return result;
    }
}
