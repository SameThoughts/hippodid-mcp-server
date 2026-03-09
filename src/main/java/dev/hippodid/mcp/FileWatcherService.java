package dev.hippodid.mcp;

import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.HippoDidException;
import dev.hippodid.client.model.SyncedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background file watcher that polls watched paths for changes and syncs to cloud via HTTP.
 *
 * <p>Runs on a single daemon thread using {@link ScheduledExecutorService}.
 * Each poll cycle iterates all registered paths in {@link WatchPathRegistry} and
 * uploads changed files via {@link HippoDidClient}.
 *
 * <p>Supports both file and directory watch entries:
 * <ul>
 *   <li>File entries — read content, compare hash, sync if changed</li>
 *   <li>Directory entries — list .md files, sync each changed file</li>
 * </ul>
 *
 * <p>Thread safety: {@code runCycle()} is {@code synchronized} to prevent concurrent
 * executions from the scheduled poll thread, {@code force_sync} MCP tool calls, and
 * the {@link dev.hippodid.mcp.workspace.CompactionFlushHook}.
 */
public final class FileWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherService.class);

    public record SyncResult(String path, boolean success, String message) {}

    private final HippoDidClient client;
    private final WatchPathRegistry watchPathRegistry;
    private final String defaultCharacterId;
    private final int syncIntervalSeconds;
    private final ScheduledExecutorService scheduler;

    public FileWatcherService(HippoDidClient client,
                               WatchPathRegistry watchPathRegistry,
                               String defaultCharacterId,
                               int syncIntervalSeconds) {
        this.client = client;
        this.watchPathRegistry = watchPathRegistry;
        this.defaultCharacterId = defaultCharacterId;
        this.syncIntervalSeconds = syncIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hippodid-file-watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
                this::pollCycle,
                syncIntervalSeconds,
                syncIntervalSeconds,
                TimeUnit.SECONDS);
        log.info("[HippoDid] FileWatcherService started — polling every {}s", syncIntervalSeconds);
    }

    public List<SyncResult> syncAllNow() {
        log.info("[HippoDid] force_sync requested — syncing {} watched paths",
                watchPathRegistry.size());
        return runCycle();
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[HippoDid] FileWatcherService stopped");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void pollCycle() {
        log.debug("[HippoDid] FileWatcherService poll cycle — {} paths", watchPathRegistry.size());
        runCycle();
    }

    private synchronized List<SyncResult> runCycle() {
        List<SyncResult> results = new ArrayList<>();
        for (WatchPathRegistry.WatchPathEntry entry : watchPathRegistry.listAll()) {
            try {
                Path entryPath = Path.of(entry.path());
                if (Files.isDirectory(entryPath)) {
                    results.addAll(syncDirectory(entry, entryPath));
                } else {
                    syncSingleFile(entry, entryPath).ifPresent(results::add);
                }
            } catch (Exception e) {
                log.warn("[HippoDid] Unexpected error syncing path '{}': {}",
                        entry.path(), e.getMessage(), e);
            }
        }
        return results;
    }

    private Optional<SyncResult> syncSingleFile(WatchPathRegistry.WatchPathEntry entry, Path filePath) {
        try {
            String content = Files.readString(filePath);
            String hash = sha256(content);
            String charId = entry.characterId().orElse(defaultCharacterId);

            if (entry.lastContentHash().map(hash::equals).orElse(false)) {
                log.debug("[HippoDid] Skipping unchanged file: {}", entry.path());
                return Optional.empty();
            }

            SyncedFile snapshot = client.characters(charId).sync()
                    .upload(entry.path(), content, entry.label().orElse(null));

            watchPathRegistry.register(
                    entry.path(), entry.label(), hash,
                    Instant.now(), Optional.of(charId));

            return Optional.of(new SyncResult(entry.path(), true, "synced"));
        } catch (IOException e) {
            log.warn("[HippoDid] Cannot read file '{}': {}", filePath, e.getMessage());
            return Optional.of(new SyncResult(entry.path(), false, "Cannot read file: " + e.getMessage()));
        } catch (HippoDidException e) {
            log.warn("[HippoDid] Sync failed for '{}': {}", entry.path(), e.getMessage());
            return Optional.of(new SyncResult(entry.path(), false, e.getMessage()));
        }
    }

    private List<SyncResult> syncDirectory(WatchPathRegistry.WatchPathEntry entry, Path dirPath) {
        List<SyncResult> results = new ArrayList<>();
        List<Path> mdFiles = WatchPathValidator.listMdFiles(dirPath);
        for (Path mdFile : mdFiles) {
            String registryKey = mdFile.toString();
            try {
                String content = Files.readString(mdFile);
                String hash = sha256(content);

                Optional<WatchPathRegistry.WatchPathEntry> existing =
                        watchPathRegistry.getEntry(registryKey);
                String charId = existing.flatMap(WatchPathRegistry.WatchPathEntry::characterId)
                        .or(() -> entry.characterId())
                        .orElse(defaultCharacterId);

                if (existing.isPresent()
                        && existing.get().lastContentHash().map(hash::equals).orElse(false)) {
                    log.debug("[HippoDid] Skipping unchanged dir file: {}", registryKey);
                    continue;
                }

                client.characters(charId).sync()
                        .upload(registryKey, content, entry.label().orElse(null));

                watchPathRegistry.register(
                        registryKey, entry.label(), hash,
                        Instant.now(), Optional.of(charId));

                results.add(new SyncResult(registryKey, true, "synced"));
            } catch (IOException e) {
                log.warn("[HippoDid] Cannot read dir file '{}': {}", registryKey, e.getMessage());
                results.add(new SyncResult(registryKey, false, "Cannot read file: " + e.getMessage()));
            } catch (HippoDidException e) {
                log.warn("[HippoDid] Dir sync failed for '{}': {}", registryKey, e.getMessage());
                results.add(new SyncResult(registryKey, false, e.getMessage()));
            }
        }
        return results;
    }

    static String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
