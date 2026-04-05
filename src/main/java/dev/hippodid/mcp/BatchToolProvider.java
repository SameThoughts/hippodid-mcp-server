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
 * MCP tools for batch character creation and job polling.
 *
 * <p>Tools: batch_create_characters, get_batch_job_status.
 */
public final class BatchToolProvider implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(BatchToolProvider.class);

    private final HippoDidClient client;
    private final ObjectMapper objectMapper;

    public BatchToolProvider(HippoDidClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(batchCreateTool(), getJobStatusTool());
    }

    private McpServerFeatures.SyncToolSpecification batchCreateTool() {
        String schema = """
                {"type":"object","properties":{
                  "template_id":{"type":"string","description":"Character template UUID to use for creation"},
                  "rows":{"type":"array","items":{"type":"object","additionalProperties":{"type":"string"}},
                    "description":"Array of data rows (JSON objects). Each row is a key-value map matching the template's field mappings."},
                  "external_id_column":{"type":"string","description":"Column name in the rows that holds the external ID (must be unique per row)"},
                  "on_conflict":{"type":"string","enum":["SKIP","UPDATE","ERROR"],
                    "description":"How to handle rows whose external ID already exists. SKIP: skip silently. UPDATE: update existing character. ERROR: fail the row. Default: ERROR"},
                  "dry_run":{"type":"boolean","description":"If true, validates all rows without persisting. Default: false"}
                },"required":["template_id","rows","external_id_column"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("batch_create_characters",
                        "Batch create characters from a template and inline data rows. Each row "
                        + "is materialized into a character using the template's field mappings "
                        + "and category schema. Processing is asynchronous — use get_batch_job_status "
                        + "to poll progress. Use dry_run=true first to validate your data before "
                        + "committing. Requires Starter+ tier.", schema),
                (exchange, args) -> {
                    try {
                        String templateId = stringArg(args, "template_id");
                        List<Map<String, String>> rows = parseStringMapList(args.get("rows"));
                        String externalIdColumn = stringArg(args, "external_id_column");
                        String onConflict = optionalStringArg(args, "on_conflict").orElse("ERROR");
                        boolean dryRun = booleanArg(args, "dry_run", false);

                        if (rows.isEmpty()) {
                            return McpToolErrorMapper.toErrorResult("InvalidInput",
                                    "rows array is required and must not be empty");
                        }

                        Map<String, Object> job = client.batch()
                                .create(templateId, rows, externalIdColumn, onConflict, dryRun);

                        McpOperationStatus status = McpOperationStatus.forBatchCreate(rows.size());
                        return toResultWithStatus(job, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification getJobStatusTool() {
        String schema = """
                {"type":"object","properties":{
                  "job_id":{"type":"string","description":"Batch job UUID returned by batch_create_characters"}
                },"required":["job_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("get_batch_job_status",
                        "Check the progress of a batch character creation job. Returns the "
                        + "current status (ACCEPTED, IN_PROGRESS, COMPLETED, FAILED), progress "
                        + "counts (succeeded, failed, skipped), and any row-level errors. "
                        + "Poll this after batch_create_characters to monitor completion.",
                        schema),
                (exchange, args) -> {
                    try {
                        String jobId = stringArg(args, "job_id");
                        Map<String, Object> job = client.batch().getJobStatus(jobId);
                        McpOperationStatus status = McpOperationStatus.fallback("get_batch_job_status");
                        return toResultWithStatus(job, status);
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

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : "";
    }

    private static Optional<String> optionalStringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null && !val.toString().isBlank() ? Optional.of(val.toString()) : Optional.empty();
    }

    private static boolean booleanArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> parseStringMapList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> {
                        Map<String, String> result = new LinkedHashMap<>();
                        ((Map<?, ?>) item).forEach((k, v) -> {
                            if (k != null) result.put(k.toString(), v != null ? v.toString() : "");
                        });
                        return result;
                    })
                    .toList();
        }
        return List.of();
    }
}
