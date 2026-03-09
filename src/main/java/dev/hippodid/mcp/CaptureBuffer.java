package dev.hippodid.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Accumulates items and flushes them in batch to a downstream consumer.
 *
 * @param <T> the type of buffered item
 */
public final class CaptureBuffer<T> {

    private static final int DEFAULT_MAX_ITEMS = 10;
    private static final long DEFAULT_FLUSH_INTERVAL_SECONDS = 60;

    private final int maxItems;
    private final long flushIntervalSeconds;
    private final Consumer<List<T>> flushHandler;
    private final Map<String, T> buffer = new LinkedHashMap<>();
    private Instant lastFlush = Instant.now();

    public CaptureBuffer(Consumer<List<T>> flushHandler) {
        this(DEFAULT_MAX_ITEMS, DEFAULT_FLUSH_INTERVAL_SECONDS, flushHandler);
    }

    public CaptureBuffer(int maxItems, long flushIntervalSeconds, Consumer<List<T>> flushHandler) {
        this.maxItems = maxItems;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.flushHandler = flushHandler;
    }

    public synchronized void add(String dedupKey, T item) {
        buffer.put(dedupKey, item);
        if (buffer.size() >= maxItems) {
            flush();
        }
    }

    public synchronized void flush() {
        if (buffer.isEmpty()) return;
        List<T> items = new ArrayList<>(buffer.values());
        buffer.clear();
        lastFlush = Instant.now();
        flushHandler.accept(items);
    }

    public synchronized void flushIfNeeded() {
        if (!buffer.isEmpty() && isIntervalElapsed()) {
            flush();
        }
    }

    public synchronized int size() {
        return buffer.size();
    }

    private boolean isIntervalElapsed() {
        return lastFlush.plusSeconds(flushIntervalSeconds).isBefore(Instant.now());
    }
}
