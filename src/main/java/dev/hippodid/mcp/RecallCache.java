package dev.hippodid.mcp;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic in-memory cache for MCP recall (search) results.
 *
 * <p>Reduces redundant API queries within a single conversation session.
 * Entries expire after the configured TTL.
 *
 * @param <T> the type of cached value
 */
public final class RecallCache<T> {

    private final long ttlSeconds;
    private final ConcurrentHashMap<String, CacheEntry<T>> store = new ConcurrentHashMap<>();

    public RecallCache(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public Optional<T> get(String key) {
        CacheEntry<T> entry = store.get(key);
        if (entry == null) return Optional.empty();
        if (isExpired(entry)) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void put(String key, T value) {
        store.put(key, new CacheEntry<>(value, Instant.now()));
    }

    public void evictExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> isExpired(e.getValue(), now));
    }

    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    private boolean isExpired(CacheEntry<T> entry) {
        return isExpired(entry, Instant.now());
    }

    private boolean isExpired(CacheEntry<T> entry, Instant now) {
        return !entry.createdAt().plusSeconds(ttlSeconds).isAfter(now);
    }

    private record CacheEntry<V>(V value, Instant createdAt) {}
}