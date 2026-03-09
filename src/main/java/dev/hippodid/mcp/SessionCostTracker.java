package dev.hippodid.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks AI operation costs for the current MCP session.
 *
 * <p>Stub implementation — counts calls/tokens, cost calculation deferred.
 */
public final class SessionCostTracker {

    private static final Logger log = LoggerFactory.getLogger(SessionCostTracker.class);

    public record SessionCostEstimate(
            long totalAiCalls,
            long totalTokensUsed,
            double estimatedCostUsd,
            Instant sessionStartedAt) {}

    private final Instant sessionStartedAt = Instant.now();
    private final AtomicLong aiCallCount = new AtomicLong(0);
    private final AtomicLong tokensUsed = new AtomicLong(0);

    public void recordAiCall(String operation, long tokenCount, String model) {
        aiCallCount.incrementAndGet();
        tokensUsed.addAndGet(tokenCount);
    }

    public SessionCostEstimate snapshot() {
        return new SessionCostEstimate(aiCallCount.get(), tokensUsed.get(), 0.0, sessionStartedAt);
    }

    public void logSummary() {
        log.info("[HippoDid] Session: {} AI calls, {} tokens, ~$0.00 (cost tracking pending)",
                aiCallCount.get(), tokensUsed.get());
    }
}
