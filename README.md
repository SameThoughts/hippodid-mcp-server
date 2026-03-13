# HippoDid MCP Server

[![Install on Smithery](https://smithery.ai/badge/hippodid)](https://smithery.ai/server/hippodid)

Persistent, structured cloud memory for AI agents — accessible from
Claude Desktop, Claude Code, Cursor, Windsurf, and any MCP-compatible tool.

## What it does

HippoDid gives your AI agents memory that persists across sessions, tools,
and providers. Store decisions, preferences, project context, and facts —
retrieve them semantically when relevant.

- **Cross-tool memory** — Write in Claude Code, read in Claude Desktop. Same character, same memories.
- **Structured extraction** — Raw text in, atomic facts out. HippoDid's AUDN pipeline extracts and categorises automatically.
- **Temporal decay** — Memories age by category. Skills are evergreen. Events fade in 14 days. Decisions persist 180 days.
- **BYOK** — Bring your own AI key. HippoDid never charges for AI ops.

## Quick setup

### Prerequisites
- Java 21+
- HippoDid account (free): https://app.hippodid.com/signup
- API key from: https://app.hippodid.com/settings/api-keys

### Download

```bash
# Download latest release
curl -L https://github.com/SameThoughts/hippodid-mcp-server/releases/latest/download/hippodid-mcp-server.jar \
  -o hippodid-mcp-server.jar
```

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "hippodid": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/hippodid-mcp-server.jar"],
      "env": {
        "HIPPODID_API_KEY": "your_api_key_here"
      }
    }
  }
}
```

### Claude Code

Add to `.mcp.json` in your project root:
```json
{
  "hippodid": {
    "command": "java",
    "args": ["-jar", "/absolute/path/to/hippodid-mcp-server.jar"],
    "env": {
      "HIPPODID_API_KEY": "your_api_key_here"
    }
  }
}
```

### Cursor / Windsurf

Settings > MCP > Add Server > Command:
```
java -jar /absolute/path/to/hippodid-mcp-server.jar
```
Environment variable: `HIPPODID_API_KEY=your_api_key_here`

## Available tools

| Tool | Description | Tier |
|---|---|---|
| `add_memory` | Extract and store memories from any text | Free |
| `search_memories` | Semantic + keyword retrieval | Free |
| `list_characters` | List memory namespaces | Free |
| `create_character` | Create a new memory namespace | Free |
| `get_character` | Get character details | Free |
| `import_document` | Extract memories from files | Free (50 KB) |
| `export_character` | Export all memories as Markdown | Free |
| `list_synced_files` | List cloud-stored files | Free |
| `get_sync_status` | Sync overview | Free |
| `force_sync` | Sync watched files now | Free |
| `delete_memory` | Remove a specific memory | Free |
| `archive_character` | Archive a character | Free |
| `add_memory_direct` | Write memory bypassing extraction | Starter+ |
| `update_character_profile` | Set character identity and persona | Starter+ |
| `update_character_aliases` | Set character aliases | Starter+ |
| `resolve_alias` | Resolve alias to character | Starter+ |
| `sync_file` | Upload file to cloud storage | Free |
| `add_watch_path` | Register file for session tracking | Free |
| `list_watch_paths` | List session-tracked files | Free |
| `configure_ai` | Set BYOK AI provider | All |
| `test_ai_config` | Test AI provider connectivity | All |

## Environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `HIPPODID_API_KEY` | Yes | — | Your HippoDid API key |
| `HIPPODID_CHARACTER_ID` | No | — | Default character UUID for background sync |
| `HIPPODID_BASE_URL` | No | `https://api.hippodid.com` | API base URL |
| `MCP_SYNC_INTERVAL` | No | `300` | Background sync interval in seconds |
| `MCP_AUTO_CAPTURE` | No | `false` | Disable background watcher (AI handles sync) |
| `MCP_AUTO_RECALL` | No | `false` | Disable background hydration (AI handles download) |
| `MCP_RECALL_CACHE_TTL` | No | `120` | Search result cache TTL in seconds |

## Architecture

```
Claude Desktop -> stdio (JSON-RPC) -> McpServerRunner -> HippoDidClient -> HTTP -> HippoDid REST API
```

The MCP server uses the [hippodid-spring-boot-starter](https://github.com/SameThoughts/hippodid-spring-boot-starter) to communicate with the HippoDid REST API over HTTP. All authentication and tenant isolation is handled server-side via Bearer tokens.

## Building from source

Requires Java 21+ and Maven 3.8+.

```bash
git clone https://github.com/SameThoughts/hippodid-mcp-server
cd hippodid-mcp-server
mvn clean package -DskipTests
java -jar target/hippodid-mcp-server-1.1.0.jar
```

## Versioning

The MCP server version matches the [hippodid-spring-boot-starter](https://github.com/SameThoughts/hippodid-spring-boot-starter) version. Both must be at the same version for compatibility.

## Docs

- Full documentation: https://hippodid.com/docs
- MCP setup guide: https://hippodid.com/docs/mcp-setup
- API reference: https://hippodid.com/docs/api
- Cloud platform: https://app.hippodid.com

## License

Apache License 2.0 — see [LICENSE](LICENSE).
