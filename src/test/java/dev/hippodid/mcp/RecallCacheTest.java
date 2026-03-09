package dev.hippodid.mcp;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RecallCacheTest {

    @Test
    void putAndGet() {
        RecallCache<String> cache = new RecallCache<>(60);
        cache.put("key1", "value1");
        assertEquals(Optional.of("value1"), cache.get("key1"));
    }

    @Test
    void getMissingReturnsEmpty() {
        RecallCache<String> cache = new RecallCache<>(60);
        assertEquals(Optional.empty(), cache.get("missing"));
    }

    @Test
    void expiredEntryReturnsEmpty() {
        RecallCache<String> cache = new RecallCache<>(0); // 0-second TTL
        cache.put("key", "val");
        assertEquals(Optional.empty(), cache.get("key"));
    }

    @Test
    void evictExpiredRemovesStaleEntries() {
        RecallCache<String> cache = new RecallCache<>(0);
        cache.put("key1", "v1");
        cache.put("key2", "v2");
        assertEquals(2, cache.size());
        cache.evictExpired();
        assertEquals(0, cache.size());
    }

    @Test
    void clearRemovesAll() {
        RecallCache<String> cache = new RecallCache<>(60);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.clear();
        assertEquals(0, cache.size());
    }
}
