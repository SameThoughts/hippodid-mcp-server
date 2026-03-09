package dev.hippodid.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Validates filesystem paths for watch eligibility.
 *
 * <p>Supports .md/.txt files and directories containing .md files.
 * Rejects paths outside the user's home directory and files over 10 MB.
 */
public final class WatchPathValidator {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private WatchPathValidator() {}

    public record ValidatedPath(Path path, boolean isDirectory, Optional<String> label) {}

    public record ValidationError(String message) {}

    /**
     * Validates a path string for watch eligibility.
     *
     * @param pathStr the raw path string
     * @param homeDir the user's home directory (containment boundary)
     * @return validated path or error
     */
    public static Result validate(String pathStr, Path homeDir) {
        if (pathStr == null || pathStr.isBlank()) {
            return Result.error("Path cannot be blank");
        }
        Path path;
        try {
            path = Path.of(pathStr).toAbsolutePath();
        } catch (Exception e) {
            return Result.error("Invalid path syntax: " + pathStr);
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return validateDirectory(path, homeDir);
        } else {
            return validateFile(path, homeDir);
        }
    }

    public static List<Path> listMdFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Result validateFile(Path path, Path homeDir) {
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
        if (!fileName.endsWith(".md") && !fileName.endsWith(".txt")) {
            return Result.error("File must have .md or .txt extension: " + path);
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Result.error("File does not exist: " + path);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Result.error("Path is not a regular file: " + path);
        }
        return checkContainment(path, homeDir).flatMap(realPath -> {
            try {
                long size = Files.size(realPath);
                if (size > MAX_FILE_SIZE_BYTES) {
                    return Result.error("File exceeds 10 MB limit (" + size + " bytes): " + path);
                }
                return Result.ok(new ValidatedPath(realPath, false, Optional.empty()));
            } catch (IOException e) {
                return Result.error("Cannot read file size for: " + path);
            }
        });
    }

    private static Result validateDirectory(Path path, Path homeDir) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Result.error("Directory does not exist: " + path);
        }
        return checkContainment(path, homeDir).flatMap(realPath -> {
            List<Path> mdFiles = listMdFiles(realPath);
            if (mdFiles.isEmpty()) {
                return Result.error("Directory contains no .md files: " + path);
            }
            return Result.ok(new ValidatedPath(realPath, true, Optional.empty()));
        });
    }

    private static Result checkContainment(Path path, Path homeDir) {
        try {
            Path realPath = path.toRealPath();
            Path realHome = homeDir.toRealPath();
            if (!realPath.startsWith(realHome)) {
                return Result.error("Path is outside home directory: " + path);
            }
            return Result.ok(new ValidatedPath(realPath, false, Optional.empty()));
        } catch (IOException e) {
            return Result.error("Cannot resolve real path for: " + path);
        }
    }

    /**
     * Validation result — either a validated path or an error message.
     */
    public sealed interface Result {
        static Result ok(ValidatedPath path) { return new Ok(path); }
        static Result error(String message) { return new Error(message); }

        default boolean isOk() { return this instanceof Ok; }
        default boolean isError() { return this instanceof Error; }
        default ValidatedPath path() {
            if (this instanceof Ok ok) return ok.path;
            throw new IllegalStateException("Not an Ok result");
        }
        default String errorMessage() {
            if (this instanceof Error err) return err.message;
            throw new IllegalStateException("Not an Error result");
        }
        default Result flatMap(java.util.function.Function<Path, Result> fn) {
            if (this instanceof Ok ok) return fn.apply(ok.path.path());
            return this;
        }

        record Ok(ValidatedPath path) implements Result {}
        record Error(String message) implements Result {}
    }
}
