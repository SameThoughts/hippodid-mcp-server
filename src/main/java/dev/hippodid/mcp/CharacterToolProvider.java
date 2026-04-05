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
                resolveAliasTool(),
                cloneCharacterTool(),
                setAgentConfigTool(),
                getAgentConfigTool(),
                setMemoryModeTool(),
                askWithAgentConfigTool());
    }

    private McpServerFeatures.SyncToolSpecification createCharacterTool() {
        String schema = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Character name (unique within tenant)"},
                  "description":{"type":"string","description":"Optional character description"}
                },"required":["name"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("create_character",
                        "Create a new isolated memory namespace (character) for an agent, persona, "
                        + "or project. Call this when a user wants to start tracking memory for a new "
                        + "agent or when no suitable character exists yet. Characters are the top-level "
                        + "containers — all memories, categories, and file syncs belong to one character. "
                        + "Use a descriptive name that reflects the agent's role or project scope.", schema),
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
                new Tool("list_characters",
                        "List all memory characters (namespaced memory stores) available for this "
                        + "tenant. Call this when you need to discover which characters exist before "
                        + "reading or writing memories, or when the user asks what agents or personas "
                        + "are configured. Each character is an isolated memory namespace — one per "
                        + "agent, project, persona, or user.", schema),
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
                new Tool("get_character",
                        "Retrieve full details for a specific character including memory count, "
                        + "categories, and profile. Call this when you need to inspect a character's "
                        + "configuration before working with its memories, or when the user asks about "
                        + "a specific agent's memory setup. Use list_characters first if you do not "
                        + "have the character ID.", schema),
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
                new Tool("archive_character",
                        "Archive (soft-delete) a character and all its memories. Call this only "
                        + "when the user explicitly asks to remove or retire an agent or persona. "
                        + "Archived characters are excluded from list_characters but data is preserved "
                        + "for recovery. Always confirm with the user before archiving — this affects "
                        + "all memories stored under this character.", schema),
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
                        "Set or update the persistent identity of a character — who they are, "
                        + "how they behave, and what rules they follow. The system prompt and "
                        + "personality are injected at every session start (Tier 1 bootstrap), "
                        + "so this shapes every interaction with this character. Call this when "
                        + "setting up a new agent persona or when the user wants to change how "
                        + "an agent behaves. Requires Starter+ tier.",
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
                new Tool("update_character_aliases",
                        "Set alternative names or references that map to this character. "
                        + "Call this when users refer to a character by different names across "
                        + "tools or conversations — e.g. 'my dev agent', 'Claude', 'the coding "
                        + "assistant'. Aliases enable resolve_alias to find the right character "
                        + "from natural language references. Requires Starter+ tier.", schema),
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
                        "Look up which character a natural language reference points to. "
                        + "Call this when the user refers to an agent by name or nickname and "
                        + "you need the character ID to read or write memories. Provide "
                        + "source_hint with surrounding context for better disambiguation when "
                        + "multiple characters have similar names. Requires Starter+ tier.",
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

    private McpServerFeatures.SyncToolSpecification cloneCharacterTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Source character UUID to clone"},
                  "name":{"type":"string","description":"Name for the cloned character"},
                  "external_id":{"type":"string","description":"Optional external ID for the clone"},
                  "copy_memories":{"type":"boolean","description":"Deep-copy all memories to the clone (default: false)"},
                  "copy_tags":{"type":"boolean","description":"Copy tags to the clone (default: true)"}
                },"required":["character_id","name"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("clone_character",
                        "Deep clone a character — copies profile, categories, agent config, "
                        + "and optionally all memories and tags. Use this to create parallel "
                        + "agent instances, A/B test different configurations, or fork a "
                        + "character for a new use case. Requires Developer+ tier. Set "
                        + "copy_memories=true to include all stored memories (may be slow "
                        + "for large memory sets).", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String name = stringArg(args, "name");
                        String externalId = optionalStringArg(args, "external_id").orElse(null);
                        Boolean copyTags = booleanArgOrNull(args, "copy_tags");
                        Boolean copyMemories = booleanArgOrNull(args, "copy_memories");

                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = client.characters().clone(
                                charId, name, externalId, copyTags, copyMemories, null);

                        McpOperationStatus status = McpOperationStatus.fallback("clone_character");
                        return toResultWithStatus(result, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification setAgentConfigTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "system_prompt":{"type":"string","description":"System prompt for the LLM when this character answers questions"},
                  "preferred_model":{"type":"string","description":"Model name (e.g. claude-sonnet-4-20250514, gpt-4o)"},
                  "temperature":{"type":"number","description":"Temperature 0.0-2.0 (default 0.7)"},
                  "max_tokens":{"type":"integer","description":"Max response tokens (default 2048)"},
                  "tools":{"type":"array","items":{"type":"string"},"description":"Enabled tool names"},
                  "response_format":{"type":"string","enum":["TEXT","JSON","MARKDOWN"],"description":"Response format"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("set_agent_config",
                        "Set the LLM behavior configuration for a character — system prompt, "
                        + "model, temperature, tools, and response format. This config is "
                        + "applied when using ask_with_agent_config and is preserved across "
                        + "clones. Call this when setting up how a character should behave "
                        + "when answering questions via the RAG pipeline.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        Map<String, Object> config = new LinkedHashMap<>();
                        optionalStringArg(args, "system_prompt").ifPresent(v -> config.put("systemPrompt", v));
                        optionalStringArg(args, "preferred_model").ifPresent(v -> config.put("preferredModel", v));
                        optionalDoubleArg(args, "temperature").ifPresent(v -> config.put("temperature", v));
                        optionalIntArg(args, "max_tokens").ifPresent(v -> config.put("maxTokens", v));
                        Object tools = args.get("tools");
                        if (tools instanceof List<?> toolList) {
                            config.put("tools", toolList);
                        }
                        optionalStringArg(args, "response_format").ifPresent(v -> config.put("responseFormat", v));

                        Map<String, Object> result = client.characters().setAgentConfig(charId, config);
                        McpOperationStatus status = McpOperationStatus.fallback("set_agent_config");
                        return toResultWithStatus(result, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification getAgentConfigTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"}
                },"required":["character_id"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("get_agent_config",
                        "Get the stored agent configuration for a character. Returns the "
                        + "system prompt, model, temperature, tools, and response format — "
                        + "or null if no config is set. Call this to inspect a character's "
                        + "behavior settings before modifying them.", schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        Map<String, Object> result = client.characters().getAgentConfig(charId);
                        McpOperationStatus status = McpOperationStatus.fallback("get_agent_config");
                        return toResultWithStatus(result, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification setMemoryModeTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "mode":{"type":"string","enum":["EXTRACTED","VERBATIM","HYBRID"],
                    "description":"Memory ingestion mode"}
                },"required":["character_id","mode"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("set_memory_mode",
                        "Set how add_memory processes content for a character. "
                        + "EXTRACTED (default): AI extracts structured facts via AUDN pipeline — "
                        + "requires BYOK AI config. Best for general knowledge management. "
                        + "VERBATIM: Store exact content as-is, no AI processing, zero LLM cost. "
                        + "Best for chat logs, support tickets, compliance records. "
                        + "HYBRID: Both extraction and verbatim archive (Business+ tier only).",
                        schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String mode = stringArg(args, "mode");

                        // Use the update endpoint with memoryMode — need current name
                        CharacterInfo character = client.characters().get(charId);
                        CharacterInfo updated = client.characters().update(
                                charId, character.name(), character.description(), mode);

                        McpOperationStatus status = McpOperationStatus.fallback("set_memory_mode");
                        return toResultWithStatus(updated, status);
                    } catch (HippoDidException e) {
                        return McpToolErrorMapper.toErrorResult(e);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification askWithAgentConfigTool() {
        String schema = """
                {"type":"object","properties":{
                  "character_id":{"type":"string","description":"Character UUID"},
                  "message":{"type":"string","description":"Question or message to send"}
                },"required":["character_id","message"]}""";

        return new McpServerFeatures.SyncToolSpecification(
                new Tool("ask_with_agent_config",
                        "Ask a question and get an answer powered by the character's memories "
                        + "and stored agent config (system prompt, model, temperature). Uses "
                        + "RAG — retrieves relevant memories, then generates an answer with "
                        + "citations. Requires Developer+ tier and tenant BYOK AI config. "
                        + "The character must have an agent config set via set_agent_config.",
                        schema),
                (exchange, args) -> {
                    try {
                        String charId = stringArg(args, "character_id");
                        String message = stringArg(args, "message");

                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = client.characters().ask(charId, message, true);

                        McpOperationStatus status = McpOperationStatus.fallback("ask_with_agent_config");
                        return toResultWithStatus(result, status);
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

    private static Boolean booleanArgOrNull(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return null;
    }

    private static Optional<Double> optionalDoubleArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return Optional.empty();
        if (val instanceof Number n) return Optional.of(n.doubleValue());
        try { return Optional.of(Double.parseDouble(val.toString())); }
        catch (NumberFormatException e) { return Optional.empty(); }
    }

    private static Optional<Integer> optionalIntArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return Optional.empty();
        if (val instanceof Number n) return Optional.of(n.intValue());
        try { return Optional.of(Integer.parseInt(val.toString())); }
        catch (NumberFormatException e) { return Optional.empty(); }
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
