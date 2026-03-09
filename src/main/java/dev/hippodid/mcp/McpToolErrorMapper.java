package dev.hippodid.mcp;

import dev.hippodid.client.HippoDidException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;

/**
 * Maps {@link HippoDidException} to MCP {@link CallToolResult} error responses.
 */
public final class McpToolErrorMapper {

    private McpToolErrorMapper() {}

    /**
     * Converts a HippoDidException to an MCP error result.
     */
    public static CallToolResult toErrorResult(HippoDidException e) {
        String json = "{\"error\":{\"type\":\"" + escapeJson(e.errorType())
                + "\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}}";
        return new CallToolResult(List.of(new TextContent(json)), true);
    }

    /**
     * Creates an error result from a type and message.
     */
    public static CallToolResult toErrorResult(String type, String message) {
        String json = "{\"error\":{\"type\":\"" + escapeJson(type)
                + "\",\"message\":\"" + escapeJson(message) + "\"}}";
        return new CallToolResult(List.of(new TextContent(json)), true);
    }

    /**
     * Creates a not-implemented error result.
     */
    public static CallToolResult notImplemented(String toolName) {
        return toErrorResult("NotImplemented",
                "Tool '" + toolName + "' not yet implemented");
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
