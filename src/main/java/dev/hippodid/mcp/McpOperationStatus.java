package dev.hippodid.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Structured metadata appended as {@code _status} to every MCP tool response.
 *
 * <p>Lets AI clients surface concise operation summaries without parsing the payload.
 */
public record McpOperationStatus(
        String operation,
        int memoriesRetrieved,
        int memoriesCreated,
        int memoriesUpdated,
        int memoriesSkipped,
        List<String> categoriesHit,
        String summary) {

    public static McpOperationStatus forSearch(int retrieved, List<String> categories) {
        String summary;
        if (retrieved == 0) {
            summary = "No memories found";
        } else if (categories.isEmpty()) {
            summary = "Retrieved " + retrieved + " memor" + (retrieved == 1 ? "y" : "ies");
        } else {
            String catSummary = categories.stream().collect(Collectors.joining(", "));
            summary = "Retrieved " + retrieved + " memor" + (retrieved == 1 ? "y" : "ies")
                    + " (" + catSummary + ")";
        }
        return new McpOperationStatus("search_memories", retrieved, 0, 0, 0,
                List.copyOf(categories), summary);
    }

    public static McpOperationStatus forAddMemory(int count, List<String> categories) {
        if (count == 0) {
            return new McpOperationStatus("add_memory", 0, 0, 0, 1, List.of(),
                    "Content filtered (low-value)");
        }
        return new McpOperationStatus("add_memory", 0, count, 0, 0,
                categories, "Added " + count + " memor" + (count == 1 ? "y" : "ies"));
    }

    public static McpOperationStatus forAddMemoryDirect(String category) {
        return new McpOperationStatus("add_memory_direct", 0, 1, 0, 0,
                List.of(category), "Added memory to " + category);
    }

    public static McpOperationStatus forSync(String path) {
        return new McpOperationStatus("sync_file", 0, 0, 0, 0, List.of(), "Synced " + path);
    }

    public static McpOperationStatus forImport(int memoriesAdded, int totalParsed) {
        return new McpOperationStatus("import_document", 0, memoriesAdded, 0, 0, List.of(),
                "Imported " + memoriesAdded + " memor" + (memoriesAdded == 1 ? "y" : "ies")
                        + " from " + totalParsed + " parsed");
    }

    public static McpOperationStatus forExport(int memoryCount) {
        return new McpOperationStatus("export_character", memoryCount, 0, 0, 0, List.of(),
                "Exported " + memoryCount + " memor" + (memoryCount == 1 ? "y" : "ies"));
    }

    public static McpOperationStatus forDelete(String entity) {
        return new McpOperationStatus("delete_" + entity, 0, 0, 0, 0, List.of(),
                entity.substring(0, 1).toUpperCase() + entity.substring(1) + " deleted");
    }

    public static McpOperationStatus forCreateCharacter(String name, int categoryCount) {
        return new McpOperationStatus("create_character", 0, 0, 0, 0, List.of(),
                "Created character '" + name + "' with " + categoryCount + " categor"
                        + (categoryCount == 1 ? "y" : "ies"));
    }

    public static McpOperationStatus forListCharacters(int count) {
        return new McpOperationStatus("list_characters", 0, 0, 0, 0, List.of(),
                "Found " + count + " character" + (count == 1 ? "" : "s"));
    }

    public static McpOperationStatus forBatchCreate(int rowCount) {
        return new McpOperationStatus("batch_create_characters", 0, 0, 0, 0, List.of(),
                "Batch job started with " + rowCount + " row" + (rowCount == 1 ? "" : "s"));
    }

    public static McpOperationStatus forCloneCharacter(String name) {
        return new McpOperationStatus("clone_character", 0, 0, 0, 0, List.of(),
                "Cloned character as '" + name + "'");
    }

    public static McpOperationStatus fallback(String operation) {
        return new McpOperationStatus(operation, 0, 0, 0, 0, List.of(), operation + " completed");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("operation", operation);
        map.put("memoriesRetrieved", memoriesRetrieved);
        map.put("memoriesCreated", memoriesCreated);
        map.put("memoriesUpdated", memoriesUpdated);
        map.put("memoriesSkipped", memoriesSkipped);
        map.put("categoriesHit", categoriesHit);
        map.put("summary", summary);
        return map;
    }
}