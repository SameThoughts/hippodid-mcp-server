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
 * <p>Tools: create_character, list_characters, get_character, archive_character,
 * update_character_profile, update_character_aliases, resolve_alias.
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
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        CharacterInfo character = client.characters().get(charId);
                        McpOperationStatus status = McpOperationStatus.fallback("get_character");
                        return toResultWithStatus(character, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification archiveCharacterTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID to archive"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("archive_character", "Soft-delete (archive) a character.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        client.characters().archive(charId);
                        McpOperationStatus status = McpOperationStatus.forDelete("character");
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("result", "Character archived");
                        map.put("_status", status.toMap());
                        return toJsonResult(map);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification updateCharacterProfileTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "system_prompt":{"type":"string","description":"System prompt injected at session start (T1)"},
                  "personality":{"type":"string","description":"Personality description"},
                  "background":{"type":"string","description":"Character background / backstory"},
                  "rules":{"type":"array","items":{"type":"string"},"description":"Behavioral rules the character should follow"},
                  "custom_fields":{"type":"object","additionalProperties":{"type":"string"},"description":"Arbitrary key-value metadata"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("update_character_profile",
                        "Update a character's profile fields (system prompt, personality, background, rules, custom fields). Requires Starter+ tier.",
                        schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        Map<String, Object> profile = new LinkedHashMap<>();
                        optionalStringArg(args, "system_prompt").ifPresent(v -> profile.put("systemPrompt", v));
                        optionalStringArg(args, "personality").ifPresent(v -> profile.put("personality", v));
                        optionalStringArg(args, "background").ifPresent(v -> profile.put("background", v));
                        Object rules = args.get("rules");
                        if (rules instanceof List<?> ruleList) {
                            profile.put("rules", ruleList);
                        }
                        Object customFields = args.get("custom_fields");
                        if (customFields instanceof Map<?, ?> cfMap) {
                            profile.put("customFields", cfMap);
                        }
                        CharacterInfo character = client.characters().updateProfile(charId, profile);
                        McpOperationStatus status = McpOperationStatus.fallback("update_character_profile");
                        return toResultWithStatus(character, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification updateCharacterAliasesTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "aliases":{"type":"array","items":{"type":"string"}}
                },"required":["character_id","aliases"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("update_character_aliases", "Replace the alias list for a character.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        List<String> aliases = parseStringList(args.get("aliases"));
                        CharacterInfo character = client.characters().updateAliases(charId, aliases);
                        McpOperationStatus status = McpOperationStatus.fallback("update_character_aliases");
                        return toResultWithStatus(character, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification resolveAliasTool() {
        String schema = """
                {"type":"object","properties":{
                  "alias":{"type":"string","description":"Alias string to resolve"},
                  "source_hint":{"type":"string","description":"Optional context hint (e.g. reference text) to improve resolution quality"}
                },"required":["alias"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("resolve_alias",
                        "Resolve an alias to a character. Optionally provide source_hint for better disambiguation.",
                        schema),
                (exchange, args) -> {
                    try {
                        String alias = stringArg(args, "alias");
                        CharacterInfo character = client.characters().resolve(alias);
                        McpOperationStatus status = McpOperationStatus.fallback("resolve_alias");
                        return toResultWithStatus(character, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
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

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
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
}
