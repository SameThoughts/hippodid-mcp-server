package dev.hippodid.mcp.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Detects whether the MCP server is running inside an OpenClaw workspace.
 *
 * <p>Detection strategy:
 * <ol>
 *   <li>Check {@code OPENCLAW_WORKSPACE} environment variable</li>
 *   <li>Walk up from the current working directory, looking for a {@code .openclaw} marker file</li>
 *   <li>If workspace found, verify {@code ${workspace}/memory/} directory exists</li>
 * </ol>
 *
 * <p>All detection is best-effort — any error returns {@link DetectionResult#notDetected()}.
 */
public final class WorkspaceDetector {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceDetector.class);

    private static final String ENV_VAR = "OPENCLAW_WORKSPACE";
    private static final String MARKER_FILE = ".openclaw";
    private static final String MEMORY_DIR = "memory";
    private static final int MAX_WALK_DEPTH = 8;

    private WorkspaceDetector() {}

    public static DetectionResult detect() {
        return detect(Path.of(System.getProperty("user.dir")),
                Optional.ofNullable(System.getenv(ENV_VAR)));
    }

    public static DetectionResult detect(Path cwd, Optional<String> envVar) {
        try {
            return doDetect(cwd, envVar);
        } catch (Exception e) {
            log.debug("[HippoDid] OpenClaw detection failed — treating as not detected: {}",
                    e.getMessage());
            return DetectionResult.notDetected();
        }
    }

    private static DetectionResult doDetect(Path cwd, Optional<String> envVar) {
        Optional<Path> workspaceFromEnv = envVar
                .filter(v -> !v.isBlank())
                .map(Path::of)
                .filter(Files::isDirectory);

        if (workspaceFromEnv.isPresent()) {
            Path workspace = workspaceFromEnv.get();
            log.debug("[HippoDid] OpenClaw workspace from env: {}", workspace);
            return buildResult(workspace);
        }

        Optional<Path> workspaceFromMarker = findMarkerDir(cwd);
        if (workspaceFromMarker.isPresent()) {
            Path workspace = workspaceFromMarker.get();
            log.debug("[HippoDid] OpenClaw workspace from marker: {}", workspace);
            return buildResult(workspace);
        }

        return DetectionResult.notDetected();
    }

    private static Optional<Path> findMarkerDir(Path start) {
        Path current = start.toAbsolutePath();
        int depth = 0;
        while (current != null && depth < MAX_WALK_DEPTH) {
            if (Files.exists(current.resolve(MARKER_FILE))) {
                return Optional.of(current);
            }
            current = current.getParent();
            depth++;
        }
        return Optional.empty();
    }

    private static DetectionResult buildResult(Path workspace) {
        Path memoryDir = workspace.resolve(MEMORY_DIR);
        if (!Files.isDirectory(memoryDir)) {
            return new DetectionResult(true, Optional.of(workspace), Optional.empty());
        }
        return new DetectionResult(true, Optional.of(workspace), Optional.of(memoryDir));
    }

    public record DetectionResult(
            boolean detected,
            Optional<Path> workspacePath,
            Optional<Path> memoryPath) {

        public static DetectionResult notDetected() {
            return new DetectionResult(false, Optional.empty(), Optional.empty());
        }
    }
}
