package dev.hippodid.mcp.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceDetectorTest {

    @Test
    void detectsFromEnvVar(@TempDir Path tempDir) throws IOException {
        Files.createDirectory(tempDir.resolve("memory"));
        var result = WorkspaceDetector.detect(tempDir, Optional.of(tempDir.toString()));

        assertTrue(result.detected());
        assertEquals(Optional.of(tempDir), result.workspacePath());
        assertTrue(result.memoryPath().isPresent());
    }

    @Test
    void detectsFromMarkerFile(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve(".openclaw"));
        Files.createDirectory(tempDir.resolve("memory"));

        var result = WorkspaceDetector.detect(tempDir, Optional.empty());

        assertTrue(result.detected());
        assertTrue(result.memoryPath().isPresent());
    }

    @Test
    void notDetectedWhenNoMarkerOrEnv(@TempDir Path tempDir) {
        var result = WorkspaceDetector.detect(tempDir, Optional.empty());
        assertFalse(result.detected());
    }

    @Test
    void detectedWithoutMemoryDir(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve(".openclaw"));

        var result = WorkspaceDetector.detect(tempDir, Optional.empty());

        assertTrue(result.detected());
        assertTrue(result.workspacePath().isPresent());
        assertFalse(result.memoryPath().isPresent());
    }

    @Test
    void notDetectedStaticFactory() {
        var result = WorkspaceDetector.DetectionResult.notDetected();
        assertFalse(result.detected());
        assertEquals(Optional.empty(), result.workspacePath());
        assertEquals(Optional.empty(), result.memoryPath());
    }
}
