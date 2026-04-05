package dev.hippodid.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hippodid.autoconfigure.HippoDidProperties;
import dev.hippodid.client.HippoDidClient;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSE transport configuration — active when {@code mcp.mode=sse}.
 *
 * <p>Exposes the same MCP tools over HTTP SSE instead of stdio:
 * <ul>
 *   <li>{@code GET /mcp/sse} — establishes SSE stream</li>
 *   <li>{@code POST /mcp/message?sessionId=<uuid>} — JSON-RPC messages</li>
 * </ul>
 *
 * <p>Auth: Bearer token on every request, validated against {@code HIPPODID_API_KEY}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mcp.mode", havingValue = "sse")
public class McpSseConfig {

    private static final Logger log = LoggerFactory.getLogger(McpSseConfig.class);

    private volatile HttpServletSseServerTransportProvider transport;

    @Bean
    public HttpServletSseServerTransportProvider sseTransport(ObjectMapper objectMapper) {
        this.transport = HttpServletSseServerTransportProvider.builder()
                .objectMapper(objectMapper)
                .baseUrl("/mcp")
                .messageEndpoint("/message")
                .sseEndpoint("/sse")
                .build();
        return this.transport;
    }

    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> mcpServlet(
            HttpServletSseServerTransportProvider transport) {
        var bean = new ServletRegistrationBean<>(transport, "/mcp/*");
        bean.setAsyncSupported(true);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<SseAuthFilter> sseAuthFilter() {
        String apiKey = System.getenv("HIPPODID_API_KEY");
        var bean = new FilterRegistrationBean<>(new SseAuthFilter(apiKey));
        bean.addUrlPatterns("/mcp/*");
        bean.setOrder(1);
        return bean;
    }

    @Bean
    public CorsFilter corsFilter() {
        var source = new UrlBasedCorsConfigurationSource();
        var config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(false);
        source.registerCorsConfiguration("/mcp/**", config);
        return new CorsFilter(source);
    }

    @Bean
    public McpSyncServer mcpServer(
            HttpServletSseServerTransportProvider transport,
            HippoDidClient client,
            McpProperties mcpProperties,
            HippoDidProperties hippoDidProperties,
            ObjectMapper objectMapper) {

        // Build tool providers (same tools as stdio mode — self-hosted has filesystem access)
        RecallCache<String> recallCache = new RecallCache<>(mcpProperties.effectiveRecallCacheTtlSeconds());

        CharacterToolProvider characterToolProvider = new CharacterToolProvider(client, objectMapper);
        MemoryToolProvider memoryToolProvider = new MemoryToolProvider(client, recallCache, objectMapper);
        AiConfigToolProvider aiConfigToolProvider = new AiConfigToolProvider(client, objectMapper);
        TemplateToolProvider templateToolProvider = new TemplateToolProvider(client, objectMapper);
        BatchToolProvider batchToolProvider = new BatchToolProvider(client, objectMapper);
        AgentConfigTemplateToolProvider agentConfigTemplateToolProvider =
                new AgentConfigTemplateToolProvider(client, objectMapper);

        WatchPathRegistry watchPathRegistry = new WatchPathRegistry();
        mcpProperties.effectiveWatchPaths().forEach(cfg ->
                watchPathRegistry.preRegister(cfg.path(),
                        cfg.label() != null && !cfg.label().isBlank()
                                ? Optional.of(cfg.label())
                                : Optional.empty()));

        FileSyncToolProvider fileSyncToolProvider = new FileSyncToolProvider(
                client, watchPathRegistry, objectMapper);

        List<McpServerFeatures.SyncToolSpecification> allTools = new ArrayList<>();
        allTools.addAll(characterToolProvider.tools());
        allTools.addAll(memoryToolProvider.tools());
        allTools.addAll(fileSyncToolProvider.tools());
        allTools.addAll(aiConfigToolProvider.tools());
        allTools.addAll(templateToolProvider.tools());
        allTools.addAll(batchToolProvider.tools());
        allTools.addAll(agentConfigTemplateToolProvider.tools());

        log.info("SSE mode — registering {} MCP tools", allTools.size());

        // Build auto-recall instructions
        String characterId = resolveCharacterId();
        Optional<String> instructions = Optional.empty();
        if (characterId != null && !characterId.isBlank()) {
            InstructionsBuilder builder = new InstructionsBuilder(
                    client, hippoDidProperties.getBaseUrl(), hippoDidProperties.getApiKey(),
                    characterId, mcpProperties.getRecallTopK());
            instructions = builder.build();
            instructions.ifPresentOrElse(
                    text -> log.info("Auto-recall: {} chars injected via instructions", text.length()),
                    () -> log.warn("Auto-recall: failed to build instructions"));
        }

        var serverBuilder = McpServer.sync(transport)
                .serverInfo("hippodid-mcp-server", "2.1.0")
                .capabilities(ServerCapabilities.builder()
                        .tools(false)
                        .build())
                .tools(allTools);

        instructions.ifPresent(serverBuilder::instructions);

        McpSyncServer server = serverBuilder.build();
        log.info("HippoDid MCP SSE server started — listening on /mcp/sse");
        return server;
    }

    /**
     * SSE keepalive — sends a lightweight MCP logging notification every 15 seconds.
     *
     * <p>The SDK does not expose raw SSE comment writing ({@code : ping\n\n}).
     * {@code notifyClients} sends a JSON-RPC notification to all sessions,
     * which keeps load balancers and proxies from closing idle connections.
     */
    @Scheduled(fixedRate = 15_000, initialDelay = 15_000)
    public void keepalive() {
        if (transport == null) return;
        transport.notifyClients("notifications/message", Map.of(
                "level", "debug",
                "logger", "system",
                "data", "keepalive"))
                .subscribe(
                        unused -> {},
                        err -> log.debug("Keepalive send failed: {}", err.getMessage()));
    }

    private static String resolveCharacterId() {
        String id = System.getenv("HIPPODID_CHARACTER_ID");
        if (id == null || id.isBlank()) {
            id = System.getenv("HIPPODID_MCP_CHARACTER_ID");
        }
        return id;
    }
}
