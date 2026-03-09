package dev.hippodid.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.HippoDidException;
import dev.hippodid.client.model.CharacterInfo;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides character-management MCP tools backed by the HippoDid REST API.
 *
 * <p>Tools: create_character, list_characters.
 * Note: get_character, archive, update_profile, update_aliases, resolve_alias
 * require additional REST endpoints not yet covered by the starter — marked not-implemented.
 */
public final class CharacterToolProvider implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(CharacterToolProvider.class);

    private final HippoDidClient client;
    private final ObjectMapper objectMapper;

    public CharacterToolProvider(HippoDidClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(
                createCharacterTool(),
                listCharactersTool(),
                getCharacterTool(),
                archiveCharacterTool(),
                updateCharacterProfileTool(),
                updateCharacterAliasesTool(),
                resolveAliasTool());
    }

    private McpServerFeatures.SyncToolSpecification createCharacterTool() {
        String schema = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Character name (unique within tenant)"},
                  "description":{"type":"string","description":"Optional character description"}
                },"required":["name"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("create_character", "Create a new character to store memories for.", schema),
                (exchange, args) -> {
                    try {
                        String name = stringArg(args, "name");
                        String description = optionalStringArg(args, "description").orElse(null);

                        CharacterInfo character = client.characters().create(name, description);
                        McpOperationStatus status = McpOperationStatus.forCreateCharacter(name, 0);
                        return toResultWithStatus(character, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification listCharactersTool() {
        String schema = """
                {"type":"object","properties":{}}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("list_characters", "List all characters accessible to you.", schema),
                (exchange, args) -> {
                    try {
                        List<CharacterInfo> characters = client.characters().list();
                        McpOperationStatus status = McpOperationStatus.forListCharacters(characters.size());
                        return toListResultWithStatus(characters, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification getCharacterTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("get_character", "Get a character by ID.", schema),
                (exchange, args) -> McpToolErrorMapper.notImplemented("get_character"));
    }

    private McpServerFeatures.SyncToolSpecification archiveCharacterTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID to archive"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("archive_character", "Soft-delete (archive) a character.", schema),
                (exchange, args) -> McpToolErrorMapper.notImplemented("archive_character"));
    }

    private McpServerFeatures.SyncToolSpecification updateCharacterProfileTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("update_character_profile", "Update a character's profile.", schema),
                (exchange, args) -> McpToolErrorMapper.notImplemented("update_character_profile"));
    }

    private McpServerFeatures.SyncToolSpecification updateCharacterAliasesTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "aliases":{"type":"array","items":{"type":"string"}}
                },"required":["character_id","aliases"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("update_character_aliases", "Replace the alias list for a character.", schema),
                (exchange, args) -> McpToolErrorMapper.notImplemented("update_character_aliases"));
    }

    private McpServerFeatures.SyncToolSpecification resolveAliasTool() {
        String schema = """
                {"type":"object","properties":{
                  "alias":{"type":"string","description":"Alias string to resolve"}
                },"required":["alias"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("resolve_alias", "Resolve an alias to a character.", schema),
                (exchange, args) -> McpToolErrorMapper.notImplemented("resolve_alias"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private CallToolResult toResultWithStatus(Object payload, McpOperationStatus status) {
        try {
            Map<String, Object> map = objectMapper.convertValue(payload,
                    new TypeReference<Map<String, Object>>() {});
            map.put("_status", status.toMap());
            String json = objectMapper.writeValueAsString(map);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (Exception e) {
            log.warn("Serialization failed", e);
            return McpToolErrorMapper.toErrorResult("SerializationError", e.getMessage());
        }
    }

    private CallToolResult toListResultWithStatus(List<?> items, McpOperationStatus status) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("results", items);
            map.put("_status", status.toMap());
            String json = objectMapper.writeValueAsString(map);
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
