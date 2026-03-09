package dev.hippodid.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WatchPathValidatorTest {

    @Test
    void blankPathIsError() {
        Path home = Path.of(System.getProperty("user.home"));
        var result = WatchPathValidator.validate("", home);
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("blank"));
    }

    @Test
    void nullPathIsError() {
        Path home = Path.of(System.getProperty("user.home"));
        var result = WatchPathValidator.validate(null, home);
        assertTrue(result.isError());
    }

    @Test
    void validMdFileIsOk(@TempDir Path tempDir) throws IOException {
        Path mdFile = tempDir.resolve("test.md");
        Files.writeString(mdFile, "# Hello");

        var result = WatchPathValidator.validate(mdFile.toString(), tempDir);
        assertTrue(result.isOk());
        assertFalse(result.path().isDirectory());
    }

    @Test
    void nonMdFileIsError(@TempDir Path tempDir) throws IOException {
        Path pyFile = tempDir.resolve("test.py");
        Files.writeString(pyFile, "print('hi')");

        var result = WatchPathValidator.validate(pyFile.toString(), tempDir);
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains(".md or .txt"));
    }

    @Test
    void directoryWithMdFilesIsOk(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("note.md"), "content");

        var result = WatchPathValidator.validate(tempDir.toString(), tempDir);
        assertTrue(result.isOk());
        assertTrue(result.path().isDirectory());
    }

    @Test
    void emptyDirectoryIsError(@TempDir Path tempDir) {
        var result = WatchPathValidator.validate(tempDir.toString(), tempDir);
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("no .md files"));
    }

    @Test
    void listMdFilesFindsOnlyMd(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("a.md"), "md");
        Files.writeString(tempDir.resolve("b.txt"), "txt");
        Files.writeString(tempDir.resolve("c.md"), "md2");

        List<Path> mdFiles = WatchPathValidator.listMdFiles(tempDir);
        assertEquals(2, mdFiles.size());
    }

    @Test
    void sealedResultFlatMap(@TempDir Path tempDir) throws IOException {
        Path mdFile = tempDir.resolve("test.md");
        Files.writeString(mdFile, "data");

        var result = WatchPathValidator.validate(mdFile.toString(), tempDir);
        assertTrue(result.isOk());

        // flatMap on Ok
        var mapped = result.flatMap(p -> WatchPathValidator.Result.ok(
                new WatchPathValidator.ValidatedPath(p, false, java.util.Optional.of("label"))));
        assertTrue(mapped.isOk());
        assertEquals(java.util.Optional.of("label"), mapped.path().label());
    }

    @Test
    void sealedResultFlatMapOnError() {
        var error = WatchPathValidator.Result.error("bad path");
        var mapped = error.flatMap(p -> WatchPathValidator.Result.ok(
                new WatchPathValidator.ValidatedPath(p, false, java.util.Optional.empty())));
        assertTrue(mapped.isError());
        assertEquals("bad path", mapped.errorMessage());
    }
}
