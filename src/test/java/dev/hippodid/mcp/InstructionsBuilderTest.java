package dev.hippodid.mcp;

import dev.hippodid.autoconfigure.HippoDidProperties;
import dev.hippodid.client.HippoDidClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InstructionsBuilderTest {

    private MockWebServer mockServer;
    private HippoDidClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        baseUrl = mockServer.url("/").toString();

        HippoDidProperties props = new HippoDidProperties();
        props.setApiKey("test-key");
        props.setBaseUrl(baseUrl);

        client = new HippoDidClient(props,
                org.springframework.web.reactive.function.client.WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("Authorization", "Bearer test-key")
                        .defaultHeader("Content-Type", "application/json")
                        .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void happyPath_profileAndMemories_returnsFullMarkdown() {
        // Profile response (GET /v1/characters/{id})
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(profileJson("TestAgent", "You are a helpful agent.",
                        "Friendly and concise", "Built for testing",
                        "[\"Always be honest\",\"Never fabricate data\"]")));

        // Memory search response (POST /v1/characters/{id}/memories/search)
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(searchJson(
                        memory("m1", "User prefers dark mode", "preferences", 0.95),
                        memory("m2", "Working on HippoDid MCP server", "project", 0.88))));

        InstructionsBuilder builder = new InstructionsBuilder(
                client, baseUrl, "test-key", "char-123", 15);

        Optional<String> result = builder.build();

        assertTrue(result.isPresent());
        String text = result.get();
        assertTrue(text.contains("# HippoDid Character: TestAgent"));
        assertTrue(text.contains("## Identity"));
        assertTrue(text.contains("You are a helpful agent."));
        assertTrue(text.contains("## Personality"));
        assertTrue(text.contains("Friendly and concise"));
        assertTrue(text.contains("## Background"));
        assertTrue(text.contains("Built for testing"));
        assertTrue(text.contains("## Rules"));
        assertTrue(text.contains("- Always be honest"));
        assertTrue(text.contains("- Never fabricate data"));
        assertTrue(text.contains("## Recalled Memories"));
        assertTrue(text.contains("User prefers dark mode"));
        assertTrue(text.contains("Working on HippoDid MCP server"));
    }

    @Test
    void profileFetchFails_memoriesOnly() {
        // Profile fetch returns error
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        // Memory search succeeds
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(searchJson(
                        memory("m1", "User prefers dark mode", "preferences", 0.9))));

        InstructionsBuilder builder = new InstructionsBuilder(
                client, baseUrl, "test-key", "char-123", 10);

        Optional<String> result = builder.build();

        assertTrue(result.isPresent());
        String text = result.get();
        assertFalse(text.contains("# HippoDid Character:"));
        assertTrue(text.contains("## Recalled Memories"));
        assertTrue(text.contains("User prefers dark mode"));
    }

    @Test
    void memorySearchFails_profileOnly() {
        // Profile succeeds
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(profileJson("TestAgent", "System prompt here.",
                        null, null, "[]")));

        // Memory search returns error
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        InstructionsBuilder builder = new InstructionsBuilder(
                client, baseUrl, "test-key", "char-123", 10);

        Optional<String> result = builder.build();

        assertTrue(result.isPresent());
        String text = result.get();
        assertTrue(text.contains("# HippoDid Character: TestAgent"));
        assertTrue(text.contains("System prompt here."));
        assertFalse(text.contains("## Recalled Memories"));
    }

    @Test
    void bothFail_returnsEmpty() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        InstructionsBuilder builder = new InstructionsBuilder(
                client, baseUrl, "test-key", "char-123", 10);

        Optional<String> result = builder.build();

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyMemories_noRecalledMemoriesSection() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(profileJson("Agent", "Do things.", null, null, "[]")));

        // Empty search results
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"results\":[],\"count\":0}"));

        InstructionsBuilder builder = new InstructionsBuilder(
                client, baseUrl, "test-key", "char-123", 10);

        Optional<String> result = builder.build();

        assertTrue(result.isPresent());
        assertFalse(result.get().contains("## Recalled Memories"));
    }

    @Test
    void sparseProfile_omitsMissingSections_keepsRules() {
        // Profile with only rules, no systemPrompt/personality/background
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(profileJson("MinimalAgent", null, null, null,
                        "[\"Rule one\",\"Rule two\"]")));

        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"results\":[],\"count\":0}"));

        InstructionsBuilder builder = new InstructionsBuilder(
                client, baseUrl, "test-key", "char-123", 10);

        Optional<String> result = builder.build();

        assertTrue(result.isPresent());
        String text = result.get();
        assertTrue(text.contains("# HippoDid Character: MinimalAgent"));
        assertFalse(text.contains("## Identity"));
        assertFalse(text.contains("## Personality"));
        assertFalse(text.contains("## Background"));
        assertTrue(text.contains("## Rules"));
        assertTrue(text.contains("- Rule one"));
        assertTrue(text.contains("- Rule two"));
    }

    @Test
    void truncation_capsOutputAt3000Chars() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(profileJson("Agent", "Short prompt.", null, null, "[]")));

        // Generate many large memories to exceed 3000 chars
        StringBuilder memoriesJson = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            if (i > 0) memoriesJson.append(",");
            String content = "Memory number " + i + " with lots of padding text "
                    + "to make this really long and push us over the 3000 char limit. "
                    + "Adding even more text here to be sure we go way over.";
            memoriesJson.append(memory("m" + i, content, "category" + (i % 3), 1.0 - i * 0.01));
        }

        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"results\":[" + memoriesJson + "],\"count\":50}"));

        InstructionsBuilder builder = new InstructionsBuilder(
                client, baseUrl, "test-key", "char-123", 50);

        Optional<String> result = builder.build();

        assertTrue(result.isPresent());
        assertTrue(result.get().length() <= InstructionsBuilder.MAX_CHARS,
                "Output should be capped at " + InstructionsBuilder.MAX_CHARS
                        + " chars but was " + result.get().length());
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private static String profileJson(String name, String systemPrompt,
                                       String personality, String background,
                                       String rulesArrayJson) {
        return """
                {
                  "id": "char-123",
                  "name": "%s",
                  "description": "Test character",
                  "visibility": "PRIVATE",
                  "memoryCount": 42,
                  "profile": {
                    "systemPrompt": %s,
                    "personality": %s,
                    "background": %s,
                    "rules": %s,
                    "customFields": {}
                  }
                }
                """.formatted(
                name,
                systemPrompt != null ? "\"" + systemPrompt + "\"" : "null",
                personality != null ? "\"" + personality + "\"" : "null",
                background != null ? "\"" + background + "\"" : "null",
                rulesArrayJson);
    }

    private static String memory(String id, String content, String category, double score) {
        return """
                {
                  "memoryId": "%s",
                  "content": "%s",
                  "category": "%s",
                  "relevanceScore": %.2f,
                  "salience": 0.5,
                  "decayWeight": 1.0,
                  "finalScore": %.2f
                }
                """.formatted(id, content, category, score, score);
    }

    private static String searchJson(String... memories) {
        return "{\"results\":[" + String.join(",", memories) + "],\"count\":" + memories.length + "}";
    }
}
