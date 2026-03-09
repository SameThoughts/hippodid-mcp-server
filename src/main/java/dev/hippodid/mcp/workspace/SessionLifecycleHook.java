package dev.hippodid.mcp.workspace;

import dev.hippodid.mcp.FileWatcherService;
import dev.hippodid.mcp.SessionCostTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Lifecycle hooks for an MCP session — invoked at session start and end.
 *
 * <p>Session start only logs the character name; startup hydration is owned by
 * {@link dev.hippodid.mcp.TierAwareEngineController#start()}.
 *
 * <p>Session end triggers a final sync of all watched paths and logs the cost summary.
 * No-op when watcher is empty.
 */
public final class SessionLifecycleHook {

    private static final Logger log = LoggerFactory.getLogger(SessionLifecycleHook.class);

    private final Optional<FileWatcherService> watcherService;
    private final SessionCostTracker costTracker;

    public SessionLifecycleHook(Optional<FileWatcherService> watcherService,
                                 SessionCostTracker costTracker) {
        this.watcherService = watcherService;
        this.costTracker = costTracker;
    }

    public void onSessionStart(String characterName) {
        log.info("[HippoDid] Session started for character: {}", characterName);
    }

    public void onSessionEnd() {
        watcherService.ifPresent(watcher -> {
            log.info("[HippoDid] Session ending — triggering final sync");
            int count = watcher.syncAllNow().size();
            log.info("[HippoDid] Final sync complete — {} paths synced", count);
        });
        costTracker.logSummary();
    }
}
