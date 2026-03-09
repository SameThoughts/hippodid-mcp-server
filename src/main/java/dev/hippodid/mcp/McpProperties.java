package dev.hippodid.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP server configuration properties.
 *
 * <p>Bound from {@code mcp.*} in application.yml. The HippoDid API connection
 * is configured separately via the {@code hippodid.*} prefix (handled by the starter).
 */
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    private int recallCacheTtlSeconds = 120;
    private List<WatchPathConfig> watchPaths;
    private List<WatchPathConfig> additionalPaths;
    private int syncIntervalSeconds = 300;
    private boolean autoCapture;
    private boolean autoRecall;

    public record WatchPathConfig(String path, String label) {}

    public int getRecallCacheTtlSeconds() { return recallCacheTtlSeconds; }
    public void setRecallCacheTtlSeconds(int val) { this.recallCacheTtlSeconds = val; }

    public List<WatchPathConfig> getWatchPaths() { return watchPaths; }
    public void setWatchPaths(List<WatchPathConfig> val) { this.watchPaths = val; }

    public List<WatchPathConfig> getAdditionalPaths() { return additionalPaths; }
    public void setAdditionalPaths(List<WatchPathConfig> val) { this.additionalPaths = val; }

    public int getSyncIntervalSeconds() { return syncIntervalSeconds; }
    public void setSyncIntervalSeconds(int val) { this.syncIntervalSeconds = val; }

    public boolean isAutoCapture() { return autoCapture; }
    public void setAutoCapture(boolean val) { this.autoCapture = val; }

    public boolean isAutoRecall() { return autoRecall; }
    public void setAutoRecall(boolean val) { this.autoRecall = val; }

    public int effectiveRecallCacheTtlSeconds() {
        return recallCacheTtlSeconds > 0 ? recallCacheTtlSeconds : 120;
    }

    public int effectiveSyncIntervalSeconds() {
        return syncIntervalSeconds > 0 ? syncIntervalSeconds : 300;
    }

    public List<WatchPathConfig> effectiveWatchPaths() {
        List<WatchPathConfig> result = new ArrayList<>();
        if (watchPaths != null) result.addAll(watchPaths);
        if (additionalPaths != null) result.addAll(additionalPaths);
        return List.copyOf(result);
    }
}
