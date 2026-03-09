package dev.hippodid.mcp.workspace;

import dev.hippodid.mcp.FileWatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls for an OpenClaw compaction-pending marker file and triggers an immediate sync when found.
 *
 * <p>OpenClaw writes {@code .openclaw-compaction-pending} in the workspace root before
 * compaction. When this hook detects the marker it calls
 * {@link FileWatcherService#syncAllNow()} to flush all watched files, then removes the marker.
 *
 * <p>Uses a single daemon thread. No-op when watcher or workspace is absent.
 */
public final class CompactionFlushHook {

    private static final Logger log = LoggerFactory.getLogger(CompactionFlushHook.class);

    private static final String MARKER_FILENAME = ".openclaw-compaction-pending";
    private static final int POLL_INTERVAL_SECONDS = 2;

    private final Optional<FileWatcherService> watcherService;
    private final Optional<Path> workspacePath;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> task;

    public CompactionFlushHook(Optional<FileWatcherService> watcherService,
                                Optional<Path> workspacePath) {
        this.watcherService = watcherService;
        this.workspacePath = workspacePath;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hippodid-compaction-hook");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (watcherService.isEmpty() || workspacePath.isEmpty()) {
            log.debug("[HippoDid] CompactionFlushHook disabled — no watcher or no workspace");
            return;
        }
        task = scheduler.scheduleAtFixedRate(
                this::pollMarker,
                POLL_INTERVAL_SECONDS,
                POLL_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        log.debug("[HippoDid] CompactionFlushHook started — polling every {}s", POLL_INTERVAL_SECONDS);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean checkAndFlush() {
        return workspacePath
                .map(workspace -> workspace.resolve(MARKER_FILENAME))
                .filter(Files::exists)
                .map(this::triggerFlush)
                .orElse(false);
    }

    private void pollMarker() {
        try {
            checkAndFlush();
        } catch (Exception e) {
            log.warn("[HippoDid] Error in compaction poll cycle: {}", e.getMessage(), e);
        }
    }

    private boolean triggerFlush(Path markerPath) {
        log.info("[HippoDid] Compaction marker detected — triggering immediate sync");
        watcherService.ifPresent(watcher -> {
            int count = watcher.syncAllNow().size();
            log.info("[HippoDid] Pre-compaction sync complete — {} paths synced", count);
        });
        try {
            Files.deleteIfExists(markerPath);
            log.debug("[HippoDid] Compaction marker removed: {}", markerPath);
        } catch (IOException e) {
            log.warn("[HippoDid] Could not remove compaction marker: {}", e.getMessage());
        }
        return true;
    }
}
