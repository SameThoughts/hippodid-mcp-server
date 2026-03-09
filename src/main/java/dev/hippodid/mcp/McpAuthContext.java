package dev.hippodid.mcp;

/**
 * Holds the API key for the MCP session.
 *
 * <p>Unlike the monorepo version, this does NOT set thread-local tenant context
 * — RLS is handled server-side via the Bearer token in the HippoDidClient.
 */
public final class McpAuthContext {

    private final String apiKey;
    private final String characterId;

    public McpAuthContext(String apiKey, String characterId) {
        this.apiKey = apiKey;
        this.characterId = characterId;
    }

    public String apiKey() { return apiKey; }
    public String characterId() { return characterId; }
}
