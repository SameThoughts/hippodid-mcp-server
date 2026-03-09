package dev.hippodid.mcp;

import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.HippoDidException;
import dev.hippodid.client.model.SyncedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Downloads cloud snapshots and writes them to the local filesystem via HTTP.
 *
 * <p>Supports both individual file paths and directory entries registered in
 * the {@link WatchPathRegistry}. Writes are atomic: content is written to a
 * {@code .hippodid.tmp} file then moved to the target with {@code ATOMIC_MOVE}.
 *
 * <p>For directory entries, all cloud snapshots for the character are fetched and
 * filtered by the directory path prefix, then each is hydrated individually.
 */
public final class FileHydrationService {

    private static final Logger log = LoggerFactory.getLogger(FileHydrationService.class);

    public enum HydrationAction { WRITTEN, SKIPPED_SAME_HASH, SKIPPED_NOT_FOUND, FAILED }

    public record HydrationResult(
            String path,
            HydrationAction action,
            Optional<String> cloudHash,
            Optional<String> localHash,
            Instant timestamp) {}

    private final HippoDidClient client;
    private final WatchPathRegistry watchPathRegistry;
    private final String defaultCharacterId;

    public FileHydrationService(HippoDidClient client,
                                 WatchPathRegistry watchPathRegistry,
                                 String defaultCharacterId) {
        this.client = client;
        this.watchPathRegistry = watchPathRegistry;
        this.defaultCharacterId = defaultCharacterId;
    }

    public List<HydrationResult> hydrateAll() {
        List<HydrationResult> results = new ArrayList<>();
        for (WatchPathRegistry.WatchPathEntry entry : watchPathRegistry.listAll()) {
            Path entryPath = Path.of(entry.path());
            if (Files.isDirectory(entryPath)) {
                results.addAll(hydrateDirectory(entry, entryPath));
            } else {
                results.add(hydrateSingle(entry.path()));
            }
        }
        return results;
    }

    public HydrationResult hydrateSingle(String filePath) {
        Optional<WatchPathRegistry.WatchPathEntry> entryOpt = watchPathRegistry.getEntry(filePath);
        Optional<String> label = entryOpt.flatMap(WatchPathRegistry.WatchPathEntry::label);
        String charId = entryOpt
                .flatMap(WatchPathRegistry.WatchPathEntry::characterId)
                .orElse(defaultCharacterId);

        return hydrateSingle(filePath, charId, label);
    }

    private HydrationResult hydrateSingle(String filePath, String charId, Optional<String> label) {
        try {
            String cloudContent = client.characters(charId).sync().download(filePath);
            String cloudHash = FileWatcherService.sha256(cloudContent);

            Path targetPath = Path.of(filePath);
            Optional<String> localHash = computeLocalHash(targetPath);

            if (localHash.map(cloudHash::equals).orElse(false)) {
                log.debug("[HippoDid] Hash match, skipping hydration for: {}", filePath);
                watchPathRegistry.register(filePath, label, cloudHash,
                        Instant.now(), Optional.of(charId));
                return new HydrationResult(filePath, HydrationAction.SKIPPED_SAME_HASH,
                        Optional.of(cloudHash), localHash, Instant.now());
            }

            writeAtomically(targetPath, cloudContent);
            watchPathRegistry.register(filePath, label, cloudHash,
                    Instant.now(), Optional.of(charId));
            log.info("[HippoDid] Hydrated file: {}", filePath);
            return new HydrationResult(filePath, HydrationAction.WRITTEN,
                    Optional.of(cloudHash), localHash, Instant.now());

        } catch (HippoDidException e) {
            if (e.statusCode() == 404) {
                log.debug("[HippoDid] No cloud snapshot found for: {}", filePath);
                return new HydrationResult(filePath, HydrationAction.SKIPPED_NOT_FOUND,
                        Optional.empty(), Optional.empty(), Instant.now());
            }
            log.warn("[HippoDid] Hydration failed for '{}': {}", filePath, e.getMessage());
            return new HydrationResult(filePath, HydrationAction.FAILED,
                    Optional.empty(), Optional.empty(), Instant.now());
        } catch (IOException e) {
            log.warn("[HippoDid] IO error hydrating '{}': {}", filePath, e.getMessage(), e);
            return new HydrationResult(filePath, HydrationAction.FAILED,
                    Optional.empty(), Optional.empty(), Instant.now());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private List<HydrationResult> hydrateDirectory(WatchPathRegistry.WatchPathEntry entry,
                                                     Path dirPath) {
        List<HydrationResult> results = new ArrayList<>();
        String dirPrefix = dirPath.toString();
        String charId = entry.characterId().orElse(defaultCharacterId);

        try {
            List<SyncedFile> files = client.characters(charId).sync().list();
            files.stream()
                    .filter(snapshot -> snapshot.path().startsWith(dirPrefix))
                    .map(SyncedFile::path)
                    .forEach(path -> results.add(hydrateSingle(path, charId, entry.label())));
        } catch (HippoDidException e) {
            log.warn("[HippoDid] Cannot list snapshots for directory hydration: {}", entry.path());
            results.add(new HydrationResult(entry.path(), HydrationAction.FAILED,
                    Optional.empty(), Optional.empty(), Instant.now()));
        }
        return results;
    }

    private Optional<String> computeLocalHash(Path path) {
        try {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return Optional.of(FileWatcherService.sha256(Files.readString(path)));
            }
        } catch (IOException e) {
            log.debug("[HippoDid] Cannot read local file for hash: {}", path);
        }
        return Optional.empty();
    }

    private void writeAtomically(Path targetPath, String content) throws IOException {
        if (targetPath.getParent() != null) {
            Files.createDirectories(targetPath.getParent());
        }
        Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".hippodid.tmp");
        Files.writeString(tempPath, content);
        try {
            Files.move(tempPath, targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
