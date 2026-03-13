package dev.hippodid.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.HippoDidException;
import dev.hippodid.client.model.MemoryInfo;
import dev.hippodid.client.model.MemoryResult;
import dev.hippodid.client.model.SearchOptions;
import dev.hippodid.client.model.SearchResult;
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
import java.util.stream.Collectors;

/**
 * Provides memory MCP tools backed by the HippoDid REST API.
 *
 * <p>Tools: add_memory, add_memory_direct, search_memories, delete_memory.
 */
public final class MemoryToolProvider implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(MemoryToolProvider.class);

    private final HippoDidClient client;
    private final RecallCache<String> recallCache;
    private final ObjectMapper objectMapper;

    public MemoryToolProvider(HippoDidClient client, RecallCache<String> recallCache,
                               ObjectMapper objectMapper) {
        this.client = client;
        this.recallCache = recallCache;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(addMemoryTool(), addMemoryDirectTool(), searchMemoriesTool(), deleteMemoryTool());
    }

    private McpServerFeatures.SyncToolSpecification addMemoryTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID to add memory to"},
                  "content":{"type":"string","description":"Raw text to process via AUDN pipeline"}
                },"required":["character_id","content"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("add_memory",
                        "Add a memory via the AUDN pipeline (rule-based salience + dedup). "
                        + "Content is analyzed and structured memories are extracted.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String content = stringArg(args, "content");
                        recallCache.evictExpired();

                        List<MemoryInfo> memories = client.characters(charId).memories().add(content);
                        List<String> categories = memories.stream()
                                .map(MemoryInfo::category)
                                .distinct()
                                .toList();
                        McpOperationStatus status = McpOperationStatus.forAddMemory(memories.size(), categories);
                        return toListResultWithStatus(memories, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification addMemoryDirectTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "content":{"type":"string","description":"Memory content to write directly"},
                  "category":{"type":"string","description":"Category name (e.g. preferences, skills, relationships)"},
                  "salience":{"type":"number","description":"Salience score 0.0–1.0 (default: 0.5)"}
                },"required":["character_id","content","category"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("add_memory_direct",
                        "Write a memory directly, bypassing AUDN pipeline. Requires Starter+ tier.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String content = stringArg(args, "content");
                        String category = stringArg(args, "category");
                        double salience = doubleArg(args, "salience", 0.5);
                        recallCache.evictExpired();

                        MemoryInfo mem = client.characters(charId).memories().addDirect(content, category, salience);

                        McpOperationStatus status = McpOperationStatus.forAddMemoryDirect(mem.category());
                        return toResultWithStatus(mem, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification searchMemoriesTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID to search within"},
                  "query":{"type":"string","description":"Natural language search query"},
                  "top_k":{"type":"integer","description":"Max results to return (default: 10, max: 50)"},
                  "categories":{"type":"array","items":{"type":"string"},
                    "description":"Optional category filter list"}
                },"required":["character_id","query"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("search_memories",
                        "Hybrid semantic + keyword search across memories for a character.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String queryText = stringArg(args, "query");
                        int topK = Math.max(1, Math.min(intArg(args, "top_k", 10), 50));

                        List<String> categories = parseStringList(args.get("categories"));
                        String categoriesSuffix = categories.stream().sorted()
                                .collect(Collectors.joining(","));
                        String cacheKey = charId + ":" + queryText + ":" + topK + ":" + categoriesSuffix;

                        Optional<String> cached = recallCache.get(cacheKey);
                        if (cached.isPresent()) {
                            log.debug("Cache hit for search: {}", queryText);
                            return new CallToolResult(List.of(new TextContent(cached.get())), false);
                        }

                        SearchOptions.Builder opts = SearchOptions.builder().topK(topK);
                        if (!categories.isEmpty()) {
                            opts.categories(categories);
                        }

                        SearchResult result = client.characters(charId).search(queryText, opts.build());
                        List<String> hitCategories = result.memories().stream()
                                .map(MemoryResult::category)
                                .distinct()
                                .toList();

                        McpOperationStatus status = McpOperationStatus.forSearch(
                                result.memories().size(), hitCategories);

                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("results", result.memories());
                        map.put("_status", status.toMap());
                        String json = objectMapper.writeValueAsString(map);
                        recallCache.put(cacheKey, json);
                        return new CallToolResult(List.of(new TextContent(json)), false);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    } catch (Exception e) {
                        log.warn("Search failed", e);
                        return McpToolErrorMapper.toErrorResult("SerializationError", e.getMessage());
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification deleteMemoryTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "memory_id":{"type":"string","description":"Memory UUID to soft-delete"}
                },"required":["character_id","memory_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("delete_memory",
                        "Soft-delete a memory. Requires ADMIN or OWNER role.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String memoryId = stringArg(args, "memory_id");
                        recallCache.clear();
                        client.characters().deleteMemory(charId, memoryId);
                        McpOperationStatus status = McpOperationStatus.forDelete("memory");
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("result", "Memory " + memoryId + " deleted");
                        map.put("_status", status.toMap());
                        return toJsonResult(map);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private CallToolResult toListResultWithStatus(List<?> items, McpOperationStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("results", items);
        map.put("_status", status.toMap());
        try {
            String json = objectMapper.writeValueAsString(map);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (Exception e) {
            log.warn("Serialization failed", e);
            return McpToolErrorMapper.toErrorResult("SerializationError", e.getMessage());
        }
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

    private CallToolResult toResultWithStatus(Object payload, McpOperationStatus status) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.convertValue(payload,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            map.put("_status", status.toMap());
            String json = objectMapper.writeValueAsString(map);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (Exception e) {
            log.warn("Failed to serialize result", e);
            return McpToolErrorMapper.toErrorResult("SerializationError",
                    "Failed to serialize MCP tool result");
        }
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : "";
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static double doubleArg(Map<String, Object> args, String key, double defaultValue) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
