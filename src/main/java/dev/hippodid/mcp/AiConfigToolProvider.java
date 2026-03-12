package dev.hippodid.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.HippoDidException;
import dev.hippodid.client.model.AiConfig;
import dev.hippodid.client.model.AiConfigRequest;
import dev.hippodid.client.model.AiTestResult;
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
 * MCP tools for tenant BYOK AI configuration.
 */
public final class AiConfigToolProvider implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(AiConfigToolProvider.class);

    private final HippoDidClient client;
    private final ObjectMapper objectMapper;

    public AiConfigToolProvider(HippoDidClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(configureAiTool(), testAiConfigTool());
    }

    private McpServerFeatures.SyncToolSpecification configureAiTool() {
        String schema = """
                {"type":"object","properties":{
                  "completionBaseUrl":{"type":"string","description":"Completion provider base URL"},
                  "completionApiKey":{"type":"string","description":"Completion provider API key"},
                  "completionModel":{"type":"string","description":"Completion model name"},
                  "completionTemperature":{"type":"number","description":"Optional temperature (0.0-2.0)"},
                  "completionMaxTokens":{"type":"integer","description":"Optional max tokens (1-32768)"},
                  "embeddingBaseUrl":{"type":"string","description":"Optional embedding base URL"},
                  "embeddingApiKey":{"type":"string","description":"Optional embedding API key"},
                  "embeddingModel":{"type":"string","description":"Optional embedding model"}
                },"required":["completionBaseUrl","completionApiKey","completionModel"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("configure_ai",
                        "Configure tenant BYOK AI providers.", schema),
                (exchange, args) -> {
                    try {
                        AiConfigRequest.Builder builder = AiConfigRequest.builder()
                                .completionBaseUrl(stringArg(args, "completionBaseUrl"))
                                .completionApiKey(stringArg(args, "completionApiKey"))
                                .completionModel(stringArg(args, "completionModel"));

                        optionalDoubleArg(args, "completionTemperature")
                                .ifPresent(builder::completionTemperature);
                        optionalIntArg(args, "completionMaxTokens")
                                .ifPresent(builder::completionMaxTokens);
                        optionalStringArg(args, "embeddingBaseUrl")
                                .ifPresent(builder::embeddingBaseUrl);
                        optionalStringArg(args, "embeddingApiKey")
                                .ifPresent(builder::embeddingApiKey);
                        optionalStringArg(args, "embeddingModel")
                                .ifPresent(builder::embeddingModel);

                        AiConfigRequest request = builder.build();
                        client.aiConfig().save(request);

                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "configured");
                        response.put("_status", McpOperationStatus.fallback("configure_ai").toMap());
                        return toJsonResult(response);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        return McpToolErrorMapper.toErrorResult("InvalidInput", e.getMessage());
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification testAiConfigTool() {
        String schema = """
                {"type":"object","properties":{}}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("test_ai_config",
                        "Test connectivity of saved AI provider configuration.", schema),
                (exchange, args) -> {
                    try {
                        AiConfig config = client.aiConfig().get();
                        if (!config.configured()) {
                            return McpToolErrorMapper.toErrorResult("AiNotConfigured",
                                    "No AI provider is configured");
                        }

                        // Build a test request from the saved config
                        // Note: we can't get the actual keys back from get() — use test endpoint
                        AiConfigRequest request = AiConfigRequest.builder()
                                .completionBaseUrl("saved")
                                .completionApiKey("saved")
                                .completionModel(config.completionModel())
                                .build();

                        AiTestResult result = client.aiConfig().test(request);

                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("completionStatus", result.completionStatus());
                        result.completionMessage().ifPresent(m -> payload.put("completionMessage", m));
                        result.embeddingStatus().ifPresent(s -> payload.put("embeddingStatus", s));
                        result.embeddingMessage().ifPresent(m -> payload.put("embeddingMessage", m));
                        payload.put("_status", McpOperationStatus.fallback("test_ai_config").toMap());
                        return toJsonResult(payload);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private CallToolResult toJsonResult(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
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

    private static Optional<Double> optionalDoubleArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return Optional.empty();
        if (val instanceof Number n) return Optional.of(n.doubleValue());
        try { return Optional.of(Double.parseDouble(val.toString())); }
        catch (NumberFormatException e) { return Optional.empty(); }
    }

    private static Optional<Integer> optionalIntArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return Optional.empty();
        if (val instanceof Number n) return Optional.of(n.intValue());
        try { return Optional.of(Integer.parseInt(val.toString())); }
        catch (NumberFormatException e) { return Optional.empty(); }
    }
}
