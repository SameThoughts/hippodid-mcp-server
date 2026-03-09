package dev.hippodid.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpOperationStatusTest {

    @Test
    void fallbackContainsOperation() {
        McpOperationStatus status = McpOperationStatus.fallback("test_tool");
        Map<String, Object> map = status.toMap();

        assertEquals("test_tool", map.get("operation"));
        assertEquals("test_tool completed", map.get("summary"));
    }

    @Test
    void forSearchContainsCategoriesAndCount() {
        McpOperationStatus status = McpOperationStatus.forSearch(5, List.of("skills", "preferences"));
        Map<String, Object> map = status.toMap();

        assertEquals(5, map.get("memoriesRetrieved"));
        assertEquals(List.of("skills", "preferences"), map.get("categoriesHit"));
        assertTrue(((String) map.get("summary")).contains("5 memories"));
    }

    @Test
    void forAddMemoryZeroCountIsFiltered() {
        McpOperationStatus status = McpOperationStatus.forAddMemory(0, List.of());
        assertTrue(status.summary().contains("filtered"));
        assertEquals(1, status.memoriesSkipped());
    }

    @Test
    void forAddMemoryWithContent() {
        McpOperationStatus status = McpOperationStatus.forAddMemory(3, List.of("preferences"));
        assertEquals(3, status.memoriesCreated());
        assertTrue(status.summary().contains("3 memories"));
    }

    @Test
    void forListCharacters() {
        McpOperationStatus status = McpOperationStatus.forListCharacters(10);
        assertNotNull(status.toMap());
        assertTrue(status.summary().contains("10 characters"));
    }

    @Test
    void forCreateCharacter() {
        McpOperationStatus status = McpOperationStatus.forCreateCharacter("Alice", 0);
        assertTrue(status.summary().contains("Alice"));
    }
}
