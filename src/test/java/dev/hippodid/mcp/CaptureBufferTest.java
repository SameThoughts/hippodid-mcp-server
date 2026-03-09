package dev.hippodid.mcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CaptureBufferTest {

    @Test
    void flushesWhenMaxItemsReached() {
        List<List<String>> flushed = new ArrayList<>();
        CaptureBuffer<String> buffer = new CaptureBuffer<>(2, 60, flushed::add);

        buffer.add("k1", "v1");
        assertEquals(0, flushed.size());

        buffer.add("k2", "v2");
        assertEquals(1, flushed.size());
        assertEquals(List.of("v1", "v2"), flushed.get(0));
    }

    @Test
    void deduplicatesByKey() {
        List<List<String>> flushed = new ArrayList<>();
        CaptureBuffer<String> buffer = new CaptureBuffer<>(10, 60, flushed::add);

        buffer.add("k1", "v1");
        buffer.add("k1", "v2");
        assertEquals(1, buffer.size());

        buffer.flush();
        assertEquals(List.of("v2"), flushed.get(0));
    }

    @Test
    void manualFlushClearsBuffer() {
        List<List<String>> flushed = new ArrayList<>();
        CaptureBuffer<String> buffer = new CaptureBuffer<>(flushed::add);

        buffer.add("k1", "v1");
        buffer.flush();
        assertEquals(0, buffer.size());
        assertEquals(1, flushed.size());
    }

    @Test
    void emptyFlushIsNoop() {
        List<List<String>> flushed = new ArrayList<>();
        CaptureBuffer<String> buffer = new CaptureBuffer<>(flushed::add);

        buffer.flush();
        assertEquals(0, flushed.size());
    }
}
