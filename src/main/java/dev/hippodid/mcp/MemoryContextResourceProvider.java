package dev.hippodid.mcp;

import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.HippoDidException;
import dev.hippodid.client.model.MemoryResult;
import dev.hippodid.client.model.SearchOptions;
import dev.hippodid.client.model.SearchResult;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Provides an MCP Resource that automatically injects recalled memories into
 * Claude's context at session start.
 *
 * <p>Exposes a single resource {@code hippodid://memory-context} containing the
 * top memories for the configured character, formatted as structured markdown.
 * Claude Code reads MCP resources at session initialization, so this enables
 * true auto-recall without any explicit tool call.
 *
 * <p>Only active when {@code HIPPODID_CHARACTER_ID} is set.
 */
public final class MemoryContextResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(MemoryContextResourceProvider.class);

    private static final String RESOURCE_URI = "hippodid://memory-context";
    private static final String RESOURCE_NAME = "HippoDid Memory Context";
    private static final String RESOURCE_DESCRIPTION =
            "Automatically recalled memories for the current character — "
            + "injected at session start so you know the user's context, "
            + "preferences, decisions, and ongoing work.";
    private static final String SEARCH_QUERY =
            "context preferences decisions skills goals relationships events";

    private final HippoDidClient client;
    private final String characterId;
    private final int topK;

    public MemoryContextResourceProvider(HippoDidClient client, String characterId, int topK) {
        this.client = client;
        this.characterId = characterId;
        this.topK = topK;
    }

    /**
     * Returns the MCP resource specifications to register on the server.
     */
    public List<McpServerFeatures.SyncResourceSpecification> resources() {
        McpSchema.Resource resource = new McpSchema.Resource(
                RESOURCE_URI,
                RESOURCE_NAME,
                RESOURCE_DESCRIPTION,
                "text/markdown",
                null);

        return List.of(new McpServerFeatures.SyncResourceSpecification(
                resource,
                (exchange, request) -> readMemoryContext()));
    }

    private McpSchema.ReadResourceResult readMemoryContext() {
        try {
            SearchResult result = client.characters(characterId)
                    .search(SEARCH_QUERY, SearchOptions.builder().topK(topK).build());

            String markdown = formatAsMarkdown(result.memories());

            log.info("[HippoDid] Auto-recall: injected {} memories for character {}",
                    result.memories().size(), characterId);

            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(RESOURCE_URI, "text/markdown", markdown)));

        } catch (HippoDidException e) {
            log.warn("[HippoDid] Auto-recall failed: {}", e.getMessage());
            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(RESOURCE_URI, "text/markdown",
                            "No memories available — auto-recall failed: " + e.getMessage())));
        }
    }

    private String formatAsMarkdown(List<MemoryResult> memories) {
        if (memories.isEmpty()) {
            return "No memories stored yet for this character.";
        }

        // Group by category for readable output
        Map<String, List<MemoryResult>> byCategory = memories.stream()
                .collect(Collectors.groupingBy(MemoryResult::category));

        StringBuilder sb = new StringBuilder();
        sb.append("--- HippoDid Memory Context ---\n\n");

        byCategory.forEach((category, mems) -> {
            sb.append("## ").append(category).append("\n");
            mems.forEach(mem ->
                    sb.append("- ").append(mem.content()).append("\n"));
            sb.append("\n");
        });

        sb.append("--- End Memory Context ---");
        return sb.toString();
    }
}
