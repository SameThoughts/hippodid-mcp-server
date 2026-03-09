package dev.hippodid.mcp;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WatchPathRegistryTest {

    @Test
    void preRegisterAndList() {
        WatchPathRegistry registry = new WatchPathRegistry();
        registry.preRegister("/tmp/test.md", Optional.of("Test File"));

        assertEquals(1, registry.size());
        assertTrue(registry.contains("/tmp/test.md"));

        var entry = registry.getEntry("/tmp/test.md");
        assertTrue(entry.isPresent());
        assertEquals(Optional.of("Test File"), entry.get().label());
        assertEquals(Optional.empty(), entry.get().lastContentHash());
    }

    @Test
    void registerUpdatesEntry() {
        WatchPathRegistry registry = new WatchPathRegistry();
        registry.preRegister("/tmp/test.md", Optional.empty());
        registry.register("/tmp/test.md", Optional.of("label"),
                "abc123", Instant.now(), Optional.of("char-1"));

        var entry = registry.getEntry("/tmp/test.md").orElseThrow();
        assertEquals(Optional.of("abc123"), entry.lastContentHash());
        assertEquals(Optional.of("char-1"), entry.characterId());
    }

    @Test
    void removeEntry() {
        WatchPathRegistry registry = new WatchPathRegistry();
        registry.preRegister("/tmp/test.md", Optional.empty());
        assertTrue(registry.remove("/tmp/test.md"));
        assertEquals(0, registry.size());
        assertFalse(registry.contains("/tmp/test.md"));
    }

    @Test
    void listAllSortedByPath() {
        WatchPathRegistry registry = new WatchPathRegistry();
        registry.preRegister("/z/file.md", Optional.empty());
        registry.preRegister("/a/file.md", Optional.empty());

        var list = registry.listAll();
        assertEquals("/a/file.md", list.get(0).path());
        assertEquals("/z/file.md", list.get(1).path());
    }

    @Test
    void preRegisterDoesNotOverwrite() {
        WatchPathRegistry registry = new WatchPathRegistry();
        registry.register("/tmp/test.md", Optional.of("label"),
                "hash1", Instant.now(), Optional.of("char-1"));
        registry.preRegister("/tmp/test.md", Optional.of("different"));

        var entry = registry.getEntry("/tmp/test.md").orElseThrow();
        assertEquals(Optional.of("hash1"), entry.lastContentHash());
    }
}
