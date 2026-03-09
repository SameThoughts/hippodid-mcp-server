package dev.hippodid.mcp;

import dev.hippodid.client.HippoDidClient;
import dev.hippodid.client.model.TierInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Orchestrates which background services to start based on the member's tier and config.
 *
 * <p>Decision matrix:
 * <pre>
 * Tier  | autoCapture | Watcher | autoRecall | Hydration
 * FREE  | (ignored)   | ON      | (ignored)  | ON
 * PAID  | false       | ON      | false      | ON
 * PAID  | true        | OFF     | true       | OFF
 * </pre>
 *
 * <p>On FREE tier, both watcher and hydration are always ON.
 * On PAID tiers, {@code autoCapture=true} means the AI agent handles syncing
 * (so the background watcher is OFF), and {@code autoRecall=true} means the AI
 * agent handles hydration (so the background hydration is OFF).
 */
public final class TierAwareEngineController {

    private static final Logger log = LoggerFactory.getLogger(TierAwareEngineController.class);

    private final McpProperties mcpProperties;
    private final HippoDidClient client;
    private final WatchPathRegistry watchPathRegistry;
    private final String defaultCharacterId;

    private FileWatcherService watcherService;
    private FileHydrationService hydrationService;

    public TierAwareEngineController(McpProperties mcpProperties,
                                      HippoDidClient client,
                                      WatchPathRegistry watchPathRegistry,
                                      String defaultCharacterId) {
        this.mcpProperties = mcpProperties;
        this.client = client;
        this.watchPathRegistry = watchPathRegistry;
        this.defaultCharacterId = defaultCharacterId;
    }

    public void start() {
        boolean isPaid = isPaidTier();
        boolean shouldWatch = !isPaid || !mcpProperties.isAutoCapture();
        boolean shouldHydrate = !isPaid || !mcpProperties.isAutoRecall();

        log.info("[HippoDid] tier={} — file watcher: {}, hydration: {}{}{}",
                isPaid ? "PAID" : "FREE",
                shouldWatch ? "ON" : "OFF",
                shouldHydrate ? "ON" : "OFF",
                shouldWatch ? "" : " (autoCapture active)",
                shouldHydrate ? "" : " (autoRecall active)");

        if (shouldHydrate) {
            hydrationService = new FileHydrationService(
                    client, watchPathRegistry, defaultCharacterId);
            log.info("[HippoDid] Running startup hydration for {} paths",
                    watchPathRegistry.size());
            hydrationService.hydrateAll();
        }

        if (shouldWatch) {
            watcherService = new FileWatcherService(
                    client, watchPathRegistry, defaultCharacterId,
                    mcpProperties.effectiveSyncIntervalSeconds());
            watcherService.start();
        }
    }

    public Optional<FileWatcherService> watcherService() {
        return Optional.ofNullable(watcherService);
    }

    public Optional<FileHydrationService> hydrationService() {
        return Optional.ofNullable(hydrationService);
    }

    public void shutdown() {
        if (watcherService != null) {
            watcherService.shutdown();
        }
        log.info("[HippoDid] TierAwareEngineController stopped");
    }

    private boolean isPaidTier() {
        try {
            TierInfo tier = client.tier();
            String tierName = tier.tier();
            return tierName != null && !tierName.equalsIgnoreCase("FREE");
        } catch (Exception e) {
            log.warn("[HippoDid] Could not fetch tier info, defaulting to FREE: {}", e.getMessage());
            return false;
        }
    }
}
