package dev.hippodid.mcp;

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
 * MCP tools for agent config template management.
 *
 * <p>Tools: create_agent_config_template, list_agent_config_templates.
 */
public final class AgentConfigTemplateToolProvider implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigTemplateToolProvider.class);

    private final HippoDidClient client;
    private final ObjectMapper objectMapper;

    public AgentConfigTemplateToolProvider(HippoDidClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(createTemplateTool(), listTemplatesTool());
    }

    private McpServerFeatures.SyncToolSpecification createTemplateTool() {
        String schema = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Template name (e.g. 'Customer Support Bot', 'Research Assistant')"},
                  "config":{"type":"object","properties":{
                    "systemPrompt":{"type":"string","description":"System prompt for the LLM"},
                    "preferredModel":{"type":"string","description":"Model name (e.g. claude-sonnet-4-20250514)"},
                    "temperature":{"type":"number","description":"Temperature 0.0-2.0 (default 0.7)"},
                    "maxTokens":{"type":"integer","description":"Max response tokens (default 2048)"},
                    "tools":{"type":"array","items":{"type":"string"},"description":"Enabled tool names"},
                    "responseFormat":{"type":"string","enum":["TEXT","JSON","MARKDOWN"],"description":"Response format"},
                    "metadata":{"type":"object","additionalProperties":{"type":"string"},"description":"Key-value metadata"}
                  },"description":"Agent configuration preset"}
                },"required":["name","config"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("create_agent_config_template",
                        "Create a reusable LLM behavior preset — a saved combination of system "
                        + "prompt, model, temperature, tools, and response format. Apply these "
                        + "presets to characters via set_agent_config or when cloning. Call this "
                        + "when you want to standardize behavior across multiple characters.",
                        schema),
                (exchange, args) -> {
                    try {
                        String name = stringArg(args, "name");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> config = args.get("config") instanceof Map<?, ?> m
                                ? toObjectMap(m) : Map.of();

                        Map<String, Object> template = client.agentConfigTemplates().create(name, config);
                        McpOperationStatus status = McpOperationStatus.fallback("create_agent_config_template");
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
                new Tool("list_agent_config_templates",
                        "List all agent config templates (behavior presets) for this tenant. "
                        + "Call this to discover available presets before applying one to "
                        + "a character or when the user asks what configurations are available.",
                        schema),
                (exchange, args) -> {
                    try {
                        List<Map<String, Object>> templates = client.agentConfigTemplates().list();
                        McpOperationStatus status = McpOperationStatus.fallback("list_agent_config_templates");
                        return toListResultWithStatus(templates, status);
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

    private static Map<String, Object> toObjectMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null) result.put(k.toString(), v);
        });
        return result;
    }
}
