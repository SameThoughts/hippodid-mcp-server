package dev.hippodid.mcp;

import dev.hippodid.client.HippoDidException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpToolErrorMapperTest {

    @Test
    void mapsHippoDidException() {
        HippoDidException e = new HippoDidException(403, "Forbidden", "Not authorized");
        CallToolResult result = McpToolErrorMapper.toErrorResult(e);

        assertTrue(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("Forbidden"));
        assertTrue(json.contains("Not authorized"));
    }

    @Test
    void mapsTypeAndMessage() {
        CallToolResult result = McpToolErrorMapper.toErrorResult("InvalidInput", "Bad value");

        assertTrue(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("InvalidInput"));
        assertTrue(json.contains("Bad value"));
    }

    @Test
    void notImplementedResult() {
        CallToolResult result = McpToolErrorMapper.notImplemented("some_tool");

        assertTrue(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("NotImplemented"));
        assertTrue(json.contains("some_tool"));
    }

    @Test
    void escapesSpecialCharacters() {
        CallToolResult result = McpToolErrorMapper.toErrorResult("Err",
                "Line1\nLine2\twith\"quotes\"");

        String json = ((TextContent) result.content().get(0)).text();
        assertFalse(json.contains("\n")); // newline should be escaped
        assertTrue(json.contains("\\n"));
    }
}
