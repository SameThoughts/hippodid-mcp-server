package dev.hippodid.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hippodid.autoconfigure.HippoDidProperties;
import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.HippoDidException;
import dev.hippodid.client.model.TierInfo;
import dev.hippodid.mcp.workspace.CompactionFlushHook;
import dev.hippodid.mcp.workspace.SessionLifecycleHook;
import dev.hippodid.mcp.workspace.WorkspaceDetector;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MCP server entry point — starts when the Spring Boot application launches.
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Validate the API key via {@code HippoDidClient.tier()}</li>
 *   <li>Build and register all MCP tools across tool providers</li>
 *   <li>Start background sync engine (if character ID and watch paths configured)</li>
 *   <li>Start the MCP server on stdio transport — blocking until stdin closes</li>
 * </ol>
 *
 * <p>Stdout is reserved for the MCP JSON-RPC protocol. All logging must go to stderr.
 */
@Component
public class McpServerRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(McpServerRunner.class);

    private final HippoDidClient client;
    private final McpProperties mcpProperties;
    private final HippoDidProperties hippoDidProperties;
    private final ObjectMapper objectMapper;

    private record McpSession(
            McpSyncServer server,
            CaptureBuffer<String> captureBuffer,
            Optional<TierAwareEngineController> engineController,
            SessionLifecycleHook lifecycleHook,
            CompactionFlushHook compactionHook) {}

    private volatile McpSession session;

    public McpServerRunner(HippoDidClient client,
                            McpProperties mcpProperties,
                            HippoDidProperties hippoDidProperties,
                            ObjectMapper objectMapper) {
        this.client = client;
        this.mcpProperties = mcpProperties;
        this.hippoDidProperties = hippoDidProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        // Step 1: Validate API key — fail fast with clear message on stderr
        String apiKey = System.getenv("HIPPODID_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[HippoDid MCP] ERROR: No API key configured. "
                    + "Set HIPPODID_API_KEY environment variable.");
            System.exit(1);
        }

        TierInfo tierInfo;
        try {
            tierInfo = client.tier();
        } catch (HippoDidException e) {
            System.err.println("[HippoDid MCP] ERROR: API key validation failed — " + e.getMessage());
            System.exit(1);
            return;
        }
        log.info("MCP auth OK — tier={}", tierInfo.tier());

        String characterId = System.getenv("HIPPODID_CHARACTER_ID");
        if (characterId == null || characterId.isBlank()) {
            characterId = System.getenv("HIPPODID_MCP_CHARACTER_ID");
        }

        // Step 2: Build tool providers
        RecallCache<String> recallCache = new RecallCache<>(mcpProperties.effectiveRecallCacheTtlSeconds());
        CaptureBuffer<String> captureBuffer = new CaptureBuffer<>(items ->
                log.debug("CaptureBuffer flushed {} items", items.size()));

        CharacterToolProvider characterToolProvider = new CharacterToolProvider(client, objectMapper);
        MemoryToolProvider memoryToolProvider = new MemoryToolProvider(client, recallCache, objectMapper);
        AiConfigToolProvider aiConfigToolProvider = new AiConfigToolProvider(client, objectMapper);

        // Step 3: Create WatchPathRegistry and pre-populate from config
        WatchPathRegistry watchPathRegistry = new WatchPathRegistry();
        mcpProperties.effectiveWatchPaths().forEach(cfg ->
                watchPathRegistry.preRegister(cfg.path(),
                        cfg.label() != null && !cfg.label().isBlank()
                                ? Optional.of(cfg.label())
                                : Optional.empty()));
        if (watchPathRegistry.size() > 0) {
            log.info("Pre-registered {} watch paths from config", watchPathRegistry.size());
        }

        // Step 3a: Auto-detect OpenClaw workspace
        WorkspaceDetector.DetectionResult openclawResult = WorkspaceDetector.detect();
        if (openclawResult.detected()) {
            log.info("[HippoDid] OpenClaw workspace detected at {}",
                    openclawResult.workspacePath().orElse(null));
            if (watchPathRegistry.size() == 0) {
                openclawResult.memoryPath().ifPresent(memDir -> {
                    watchPathRegistry.preRegister(memDir.toString(),
                            Optional.of("OpenClaw Memory Dir"));
                    log.info("[HippoDid] Auto-registered OpenClaw memory directory: {}", memDir);
                });
            }
        }

        // Step 3b: Validate watch paths — remove invalid ones
        Path homeDir = Path.of(System.getProperty("user.home"));
        watchPathRegistry.listAll().stream()
                .map(entry -> java.util.Map.entry(entry.path(),
                        WatchPathValidator.validate(entry.path(), homeDir)))
                .filter(e -> e.getValue().isError())
                .forEach(e -> {
                    log.warn("Invalid watch path '{}': {}", e.getKey(),
                            e.getValue().errorMessage());
                    watchPathRegistry.remove(e.getKey());
                });

        // Step 3c: Start tier-aware background engine
        Optional<TierAwareEngineController> engineController = Optional.empty();
        if (characterId != null && !characterId.isBlank() && !watchPathRegistry.listAll().isEmpty()) {
            TierAwareEngineController controller = new TierAwareEngineController(
                    mcpProperties, client, watchPathRegistry, characterId);
            controller.start();
            engineController = Optional.of(controller);
            log.info("[HippoDid] Background sync engine started — {} watched paths",
                    watchPathRegistry.size());
        } else {
            log.info("[HippoDid] Background sync skipped — no character ID or no watch paths");
        }

        // Step 3d: Session lifecycle + compaction hooks
        SessionCostTracker costTracker = new SessionCostTracker();
        SessionLifecycleHook lifecycleHook = new SessionLifecycleHook(
                engineController.flatMap(TierAwareEngineController::watcherService),
                costTracker);
        lifecycleHook.onSessionStart(characterId != null ? characterId : "(none)");

        CompactionFlushHook compactionHook = new CompactionFlushHook(
                engineController.flatMap(TierAwareEngineController::watcherService),
                openclawResult.workspacePath());
        compactionHook.start();

        FileSyncToolProvider fileSyncToolProvider = new FileSyncToolProvider(
                client, watchPathRegistry, objectMapper);

        // Step 4: Collect all tool specifications
        List<McpServerFeatures.SyncToolSpecification> allTools = new ArrayList<>();
        allTools.addAll(characterToolProvider.tools());
        allTools.addAll(memoryToolProvider.tools());
        allTools.addAll(fileSyncToolProvider.tools());
        allTools.addAll(aiConfigToolProvider.tools());

        log.info("Registering {} MCP tools", allTools.size());

        // Step 4b: Build auto-recall instructions (memories + character profile)
        Optional<String> instructions = Optional.empty();
        if (characterId != null && !characterId.isBlank()) {
            InstructionsBuilder instructionsBuilder = new InstructionsBuilder(
                    client, hippoDidProperties.getBaseUrl(), hippoDidProperties.getApiKey(),
                    characterId, mcpProperties.getRecallTopK());
            instructions = instructionsBuilder.build();
            instructions.ifPresentOrElse(
                    text -> log.info("[HippoDid] Auto-recall: {} chars injected via instructions",
                            text.length()),
                    () -> log.warn("[HippoDid] Auto-recall: failed to build instructions, "
                            + "continuing without"));
        }

        // Step 5: Build MCP server with stdio transport
        StdioServerTransportProvider transport = new StdioServerTransportProvider(objectMapper);

        var serverBuilder = McpServer.sync(transport)
                .serverInfo("hippodid-mcp-server", "1.1.0")
                .capabilities(ServerCapabilities.builder()
                        .tools(false)
                        .build())
                .tools(allTools);

        instructions.ifPresent(serverBuilder::instructions);

        McpSyncServer mcpServer = serverBuilder.build();

        session = new McpSession(mcpServer, captureBuffer, engineController, lifecycleHook, compactionHook);
        log.info("HippoDid MCP server started with {} tools — listening on stdio", allTools.size());
    }

    @PreDestroy
    public void shutdown() {
        McpSession s = this.session;
        if (s == null) return;
        if (s.lifecycleHook() != null) s.lifecycleHook().onSessionEnd();
        if (s.compactionHook() != null) s.compactionHook().shutdown();
        s.engineController().ifPresent(TierAwareEngineController::shutdown);
        if (s.captureBuffer() != null) s.captureBuffer().flush();
        s.server().closeGracefully();
        log.info("HippoDid MCP server shut down");
    }
}
