package dev.hippodid.mcp;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of watched file paths for the duration of an MCP server process.
 *
 * <p>Uses String character IDs (not domain CharacterId) for full decoupling from the API.
 */
public final class WatchPathRegistry {

    public record WatchPathEntry(
            String path,
            Optional<String> label,
            Optional<String> lastContentHash,
            Optional<Instant> lastSyncedAt,
            Optional<String> characterId) {}

    private final ConcurrentHashMap<String, WatchPathEntry> entries = new ConcurrentHashMap<>();

    public void register(String path, Optional<String> label, String contentHash, Instant syncedAt) {
        register(path, label, contentHash, syncedAt, Optional.empty());
    }

    public void register(String path, Optional<String> label, String contentHash,
                         Instant syncedAt, Optional<String> characterId) {
        entries.compute(path, (ignored, existing) -> new WatchPathEntry(
                path, label, Optional.of(contentHash), Optional.of(syncedAt),
                characterId.isPresent() ? characterId
                        : existing != null ? existing.characterId() : Optional.empty()));
    }

    public void preRegister(String path, Optional<String> label) {
        entries.putIfAbsent(path, new WatchPathEntry(
                path, label, Optional.empty(), Optional.empty(), Optional.empty()));
    }

    public List<WatchPathEntry> listAll() {
        return entries.values().stream()
                .sorted(Comparator.comparing(WatchPathEntry::path))
                .toList();
    }

    public boolean contains(String path) {
        return entries.containsKey(path);
    }

    public int size() {
        return entries.size();
    }

    public Optional<WatchPathEntry> getEntry(String path) {
        return Optional.ofNullable(entries.get(path));
    }

    public boolean remove(String path) {
        return entries.remove(path) != null;
    }
}
