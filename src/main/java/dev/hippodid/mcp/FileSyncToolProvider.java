package dev.hippodid.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.HippoDidException;
import dev.hippodid.client.model.ExportFormat;
import dev.hippodid.client.model.ImportDocumentResult;
import dev.hippodid.client.model.SyncStatus;
import dev.hippodid.client.model.SyncedFile;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides file-sync MCP tools backed by the HippoDid REST API.
 *
 * <p>Tools: sync_file, import_document, list_synced_files, get_sync_status,
 * export_character, add_watch_path, list_watch_paths, force_sync.
 */
public final class FileSyncToolProvider implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(FileSyncToolProvider.class);

    private final HippoDidClient client;
    private final WatchPathRegistry watchPathRegistry;
    private final ObjectMapper objectMapper;

    public FileSyncToolProvider(HippoDidClient client,
                                WatchPathRegistry watchPathRegistry,
                                ObjectMapper objectMapper) {
        this.client = client;
        this.watchPathRegistry = watchPathRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(
                syncFileTool(), importDocumentTool(), listSyncedFilesTool(),
                getSyncStatusTool(), exportCharacterTool(),
                addWatchPathTool(), listWatchPathsTool(), forceSyncTool());
    }

    private McpServerFeatures.SyncToolSpecification syncFileTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "file_path":{"type":"string","description":"Canonical file path"},
                  "file_content":{"type":"string","description":"Full UTF-8 file content"},
                  "label":{"type":"string","description":"Optional human-readable label"}
                },"required":["character_id","file_path","file_content"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("sync_file", "Sync a local file to the HippoDid cloud.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String path = stringArg(args, "file_path");
                        String content = stringArg(args, "file_content");
                        String label = optionalStringArg(args, "label").orElse(null);

                        SyncedFile snapshot = client.characters(charId).sync().upload(path, content, label);
                        return toResultWithStatus(snapshot, McpOperationStatus.forSync(path));
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification importDocumentTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "file_name":{"type":"string","description":"Document filename"},
                  "file_content":{"type":"string","description":"Full UTF-8 document content"}
                },"required":["character_id","file_name","file_content"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("import_document",
                        "Import a document and extract memories. Available on all tiers: Free and Starter support up to 50 KB per file; Developer and Business have no size limit.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String fileName = stringArg(args, "file_name");
                        String content = stringArg(args, "file_content");

                        ImportDocumentResult result = client.characters(charId).sync()
                                .importDocument(fileName, content, "auto");

                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("totalParsed", result.totalParsed());
                        map.put("memoriesAdded", result.memoriesAdded());
                        map.put("duplicatesSkipped", result.duplicatesSkipped());
                        map.put("fillerFiltered", result.fillerFiltered());
                        map.put("_status", McpOperationStatus.forImport(
                                result.memoriesAdded(), result.totalParsed()).toMap());
                        return toJsonResult(map);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification listSyncedFilesTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("list_synced_files", "List files synced to the cloud for a character.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        List<SyncedFile> files = client.characters(charId).sync().list();
                        return toListResultWithStatus(files,
                                McpOperationStatus.fallback("list_synced_files"));
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification getSyncStatusTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("get_sync_status", "Get sync status summary for a character.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        SyncStatus status = client.characters(charId).sync().status();

                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("characterId", status.characterId());
                        map.put("totalFiles", status.totalFiles());
                        map.put("totalSizeBytes", status.totalSizeBytes());
                        map.put("latestSyncAt", status.latestSyncAt()
                                .map(Instant::toString).orElse(null));
                        map.put("_status", McpOperationStatus.fallback("get_sync_status").toMap());
                        return toJsonResult(map);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification exportCharacterTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("export_character",
                        "Export all memories for a character as Markdown.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String content = client.characters(charId).exportAsString(ExportFormat.MARKDOWN);

                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("content", content);
                        map.put("_status", McpOperationStatus.forExport(0).toMap());
                        return toJsonResult(map);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification addWatchPathTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "file_path":{"type":"string","description":"File path to watch"},
                  "file_content":{"type":"string","description":"Current file content"},
                  "label":{"type":"string","description":"Optional label"}
                },"required":["character_id","file_path","file_content"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("add_watch_path",
                        "Sync a file and register the path for background tracking. "
                        + "file_content is required because the MCP server runs via stdio and "
                        + "cannot read local files directly — the AI client must provide it.",
                        schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String path = stringArg(args, "file_path");
                        String content = stringArg(args, "file_content");
                        Optional<String> label = optionalStringArg(args, "label");

                        SyncedFile snapshot = client.characters(charId).sync()
                                .upload(path, content, label.orElse(null));

                        watchPathRegistry.register(path, label,
                                snapshot.contentHash(), snapshot.capturedAt(),
                                Optional.of(charId));

                        return toResultWithStatus(snapshot, McpOperationStatus.forSync(path));
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification listWatchPathsTool() {
        String schema = """
                {"type":"object","properties":{}}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("list_watch_paths", "List all watched file paths in this session.", schema),
                (exchange, args) -> {
                    List<Map<String, Object>> entries = watchPathRegistry.listAll().stream()
                            .map(entry -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("path", entry.path());
                                m.put("label", entry.label().orElse(null));
                                m.put("lastContentHash", entry.lastContentHash().orElse(null));
                                m.put("lastSyncedAt", entry.lastSyncedAt()
                                        .map(Instant::toString).orElse(null));
                                return m;
                            })
                            .toList();
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("results", entries);
                    response.put("_status", McpOperationStatus.fallback("list_watch_paths").toMap());
                    return toJsonResult(response);
                });
    }

    private McpServerFeatures.SyncToolSpecification forceSyncTool() {
        String schema = """
                {"type":"object","properties":{}}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("force_sync",
                        "Force an immediate sync of all watched paths.", schema),
                (exchange, args) -> {
                    // Background watcher uses HTTP sync now — iterate registry
                    List<Map<String, Object>> results = watchPathRegistry.listAll().stream()
                            .map(entry -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("path", entry.path());
                                try {
                                    String charId = entry.characterId().orElse(null);
                                    if (charId == null) {
                                        m.put("success", false);
                                        m.put("message", "No character ID for this path");
                                        return m;
                                    }
                                    String content = java.nio.file.Files.readString(
                                            java.nio.file.Path.of(entry.path()));
                                    client.characters(charId).sync()
                                            .upload(entry.path(), content,
                                                    entry.label().orElse(null));
                                    m.put("success", true);
                                    m.put("message", "synced");
                                } catch (Exception e) {
                                    m.put("success", false);
                                    m.put("message", e.getMessage());
                                }
                                return m;
                            })
                            .toList();

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("results", results);
                    response.put("_status", McpOperationStatus.fallback("force_sync").toMap());
                    return toJsonResult(response);
                });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private CallToolResult toResultWithStatus(Object payload, McpOperationStatus status) {
        try {
            Map<String, Object> map = objectMapper.convertValue(payload,
                    new TypeReference<Map<String, Object>>() {});
            map.put("_status", status.toMap());
            return toJsonResult(map);
        } catch (Exception e) {
            log.warn("Serialization failed", e);
            return McpToolErrorMapper.toErrorResult("SerializationError", e.getMessage());
        }
    }

    private CallToolResult toListResultWithStatus(List<?> items, McpOperationStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("results", items);
        map.put("_status", status.toMap());
        return toJsonResult(map);
    }

    private CallToolResult toJsonResult(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (Exception e) {
            log.warn("Serialization failed", e);
            return McpToolErrorMapper.toErrorResult("SerializationError", e.getMessage());
        }
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : "";
    }

    private static Optional<String> optionalStringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null && !val.toString().isBlank() ? Optional.of(val.toString()) : Optional.empty();
    }
}
