package dev.hippodid.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileWatcherServiceTest {

    @Test
    void sha256ProducesConsistentHash() {
        String hash1 = FileWatcherService.sha256("hello world");
        String hash2 = FileWatcherService.sha256("hello world");
        assertEquals(hash1, hash2);
    }

    @Test
    void sha256ProducesDifferentHashForDifferentContent() {
        String hash1 = FileWatcherService.sha256("hello");
        String hash2 = FileWatcherService.sha256("world");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void sha256ProducesHexString() {
        String hash = FileWatcherService.sha256("test");
        assertEquals(64, hash.length()); // SHA-256 = 32 bytes = 64 hex chars
        assertTrue(hash.matches("[0-9a-f]+"));
    }
}
