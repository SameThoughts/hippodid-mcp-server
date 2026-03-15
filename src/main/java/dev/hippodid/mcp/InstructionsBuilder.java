package dev.hippodid.mcp;

import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.model.MemoryResult;
import dev.hippodid.client.model.SearchOptions;
import dev.hippodid.client.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the MCP {@code instructions} string for auto-recall context injection.
 *
 * <p>Fetches the character profile (via direct HTTP, bypassing the SDK's lossy
 * deserialization) and top-K recalled memories (via the SDK). Composes both into
 * a single markdown string capped at {@value #MAX_CHARS} characters.
 *
 * <p>Returns {@link Optional#empty()} only if both fetches fail. Partial results
 * (profile-only or memories-only) are still returned. Errors are logged, never thrown.
 *
 * <p><b>Known limitation (Phase 1)</b>: content is fetched at process startup.
 * For stdio transport (one process per session), this is equivalent to session start.
 * Phase 2 will add a {@code notifications/initialized} hook for SSE transport.
 */
final class InstructionsBuilder {

    private static final Logger log = LoggerFactory.getLogger(InstructionsBuilder.class);

    static final int MAX_CHARS = 3_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String SEARCH_QUERY =
            "recent decisions tasks in progress architecture preferences goals context";

    private final HippoDidClient client;
    private final WebClient profileClient;
    private final String characterId;
    private final int topK;

    InstructionsBuilder(HippoDidClient client,
                        String baseUrl,
                        String apiKey,
                        String characterId,
                        int topK) {
        this.client = client;
        this.characterId = characterId;
        this.topK = topK;
        this.profileClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Fetches character profile and recalled memories, then formats them as a
     * markdown instructions string.
     *
     * @return the formatted instructions, or empty if both fetches fail
     */
    Optional<String> build() {
        Optional<CharacterWithProfile> profile = fetchProfile();
        Optional<List<MemoryResult>> memories = fetchMemories();

        if (profile.isEmpty() && memories.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder();

        profile.ifPresent(p -> appendProfile(sb, p));
        memories.ifPresent(mems -> appendMemories(sb, mems));

        if (sb.isEmpty()) {
            return Optional.empty();
        }

        String result = sb.toString();
        if (result.length() > MAX_CHARS) {
            result = truncateToLimit(result, profile.orElse(null), memories.orElse(List.of()));
        }

        return Optional.of(result);
    }

    private Optional<CharacterWithProfile> fetchProfile() {
        try {
            CharacterWithProfile response = profileClient.get()
                    .uri("/v1/characters/{id}", characterId)
                    .retrieve()
                    .bodyToMono(CharacterWithProfile.class)
                    .block(TIMEOUT);
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("[HippoDid] Auto-recall: profile fetch failed for {}: {}",
                    characterId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<List<MemoryResult>> fetchMemories() {
        try {
            SearchResult result = client.characters(characterId)
                    .search(SEARCH_QUERY, SearchOptions.builder().topK(topK).build());
            return Optional.of(result.memories());
        } catch (Exception e) {
            log.warn("[HippoDid] Auto-recall: memory search failed for {}: {}",
                    characterId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Formatting ──────────────────────────────────────────────────────────

    private void appendProfile(StringBuilder sb, CharacterWithProfile character) {
        sb.append("# HippoDid Character: ").append(character.name()).append("\n\n");

        CharacterWithProfile.ProfileDto profile = character.profile();
        if (profile == null) {
            return;
        }

        appendSection(sb, "Identity", profile.systemPrompt());
        appendSection(sb, "Personality", profile.personality());
        appendSection(sb, "Background", profile.background());

        if (profile.rules() != null && !profile.rules().isEmpty()) {
            sb.append("## Rules\n");
            profile.rules().forEach(rule -> sb.append("- ").append(rule).append("\n"));
            sb.append("\n");
        }

        if (profile.customFields() != null && !profile.customFields().isEmpty()) {
            sb.append("## Custom Fields\n");
            profile.customFields().forEach((key, value) ->
                    sb.append("- ").append(key).append(": ").append(value).append("\n"));
            sb.append("\n");
        }
    }

    private void appendSection(StringBuilder sb, String heading, String content) {
        if (content != null && !content.isBlank()) {
            sb.append("## ").append(heading).append("\n");
            sb.append(content).append("\n\n");
        }
    }

    private void appendMemories(StringBuilder sb, List<MemoryResult> memories) {
        if (memories.isEmpty()) {
            return;
        }

        sb.append("## Recalled Memories\n\n");

        Map<String, List<MemoryResult>> byCategory = memories.stream()
                .collect(Collectors.groupingBy(MemoryResult::category));

        byCategory.forEach((category, mems) -> {
            sb.append("### ").append(category).append("\n");
            mems.forEach(mem -> sb.append("- ").append(mem.content()).append("\n"));
            sb.append("\n");
        });
    }

    // ── Truncation ──────────────────────────────────────────────────────────

    /**
     * Rebuilds the output with fewer memories until it fits within {@link #MAX_CHARS}.
     * Profile is always preserved; memories are cut from the bottom (least relevant first,
     * since they're already sorted by finalScore descending).
     */
    private String truncateToLimit(String fullOutput,
                                   CharacterWithProfile profile,
                                   List<MemoryResult> memories) {
        if (memories.isEmpty()) {
            // Profile alone is too large — hard truncate
            return fullOutput.substring(0, MAX_CHARS);
        }

        // Binary search: find the max number of memories that fits
        int lo = 0;
        int hi = memories.size();
        String bestFit = "";

        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            StringBuilder sb = new StringBuilder();
            if (profile != null) {
                appendProfile(sb, profile);
            }
            appendMemories(sb, memories.subList(0, mid));

            if (sb.length() <= MAX_CHARS) {
                bestFit = sb.toString();
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        int truncated = memories.size() - (lo - 1);
        if (truncated > 0) {
            log.info("[HippoDid] Auto-recall: truncated {} least-relevant memories to fit {} char limit",
                    truncated, MAX_CHARS);
        }

        return bestFit.isEmpty() ? fullOutput.substring(0, MAX_CHARS) : bestFit;
    }
}
