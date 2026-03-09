package dev.hippodid.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionCostTrackerTest {

    @Test
    void snapshotReflectsRecordedCalls() {
        SessionCostTracker tracker = new SessionCostTracker();
        tracker.recordAiCall("search", 100, "gpt-4");
        tracker.recordAiCall("add", 50, "gpt-4");

        var snapshot = tracker.snapshot();
        assertEquals(2, snapshot.totalAiCalls());
        assertEquals(150, snapshot.totalTokensUsed());
        assertEquals(0.0, snapshot.estimatedCostUsd());
        assertNotNull(snapshot.sessionStartedAt());
    }

    @Test
    void logSummaryDoesNotThrow() {
        SessionCostTracker tracker = new SessionCostTracker();
        assertDoesNotThrow(tracker::logSummary);
    }
}
