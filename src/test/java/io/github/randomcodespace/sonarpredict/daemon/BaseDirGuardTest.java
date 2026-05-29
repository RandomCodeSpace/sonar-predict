package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused unit tests for {@link BaseDirGuard}, the path-traversal defense
 * lifted out of {@code AnalysisService}. The decisions asserted here mirror
 * the accept/reject behavior the analysis core relied on before the lift.
 */
class BaseDirGuardTest {

    /** Resolves a request file the way {@code AnalysisService} does before guarding. */
    private static Path resolve(Path baseDir, String relative) {
        return baseDir.resolve(relative).toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("a real file inside the base directory is accepted")
    void inBaseDir_accepted(@TempDir Path tmp) throws Exception {
        Path baseDir = Files.createDirectory(tmp.resolve("project"));
        Files.writeString(baseDir.resolve("Main.java"), "class Main {}");

        assertTrue(BaseDirGuard.isWithinBaseDir(
                "Main.java", resolve(baseDir, "Main.java"), baseDir),
                "a real file under baseDir must be accepted");
    }

    @Test
    @DisplayName("a real file in a nested subdirectory of the base directory is accepted")
    void nestedInBaseDir_accepted(@TempDir Path tmp) throws Exception {
        Path baseDir = Files.createDirectory(tmp.resolve("project"));
        Path nested = Files.createDirectories(baseDir.resolve("src/main"));
        Files.writeString(nested.resolve("Main.java"), "class Main {}");

        String relative = "src/main/Main.java";
        assertTrue(BaseDirGuard.isWithinBaseDir(
                relative, resolve(baseDir, relative), baseDir),
                "a real nested file under baseDir must be accepted");
    }

    @Test
    @DisplayName("a '..' segment escaping the base directory is rejected")
    void parentTraversal_rejected(@TempDir Path tmp) throws Exception {
        Path baseDir = Files.createDirectory(tmp.resolve("project"));
        // A secret the daemon must never read, a sibling of baseDir.
        Files.writeString(tmp.resolve("secret.java"), "class Secret {}");

        String relative = "../secret.java";
        assertFalse(BaseDirGuard.isWithinBaseDir(
                relative, resolve(baseDir, relative), baseDir),
                "a '..' path escaping baseDir must be rejected");
    }

    @Test
    @DisplayName("an absolute request path is rejected")
    void absolutePath_rejected(@TempDir Path tmp) throws Exception {
        Path baseDir = Files.createDirectory(tmp.resolve("project"));
        // An absolute path that genuinely exists, so only the absoluteness —
        // not a failed toRealPath() — can be what rejects it.
        Path outside = tmp.resolve("hostname");
        Files.writeString(outside, "host");

        String absolute = outside.toAbsolutePath().toString();
        assertFalse(BaseDirGuard.isWithinBaseDir(
                absolute, resolve(baseDir, absolute), baseDir),
                "an absolute request path must be rejected");
    }

    @Test
    @DisplayName("a non-existent file is rejected (real path cannot be proven)")
    void nonExistentFile_rejected(@TempDir Path tmp) throws Exception {
        Path baseDir = Files.createDirectory(tmp.resolve("project"));

        // ghost.java does not exist: toRealPath() throws, which the guard
        // treats as "not contained" rather than letting the request crash.
        String relative = "ghost.java";
        assertFalse(BaseDirGuard.isWithinBaseDir(
                relative, resolve(baseDir, relative), baseDir),
                "a non-existent file must be rejected, not crash");
    }

    @Test
    @DisplayName("a symlink under the base directory pointing outside the tree is rejected")
    void symlinkEscaping_rejected(@TempDir Path tmp) throws Exception {
        Path baseDir = Files.createDirectory(tmp.resolve("project"));
        Path outsideSecret = tmp.resolve("secret.java");
        Files.writeString(outsideSecret, "class Secret {}");

        // A symlink whose name has no '..' and is not absolute, so it passes
        // the lexical guards — but resolves to a file outside baseDir.
        Path escapeLink = baseDir.resolve("escape.java");
        try {
            Files.createSymbolicLink(escapeLink, outsideSecret);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false,
                    "filesystem does not support symbolic links; skipping: " + e);
            return;
        }

        String relative = "escape.java";
        assertFalse(BaseDirGuard.isWithinBaseDir(
                relative, resolve(baseDir, relative), baseDir),
                "a symlink escaping baseDir must be rejected even though it is "
                        + "lexically clean");
    }

    @Test
    @DisplayName("a symlink under the base directory resolving back inside the tree is accepted")
    void symlinkInside_accepted(@TempDir Path tmp) throws Exception {
        Path baseDir = Files.createDirectory(tmp.resolve("project"));
        Files.writeString(baseDir.resolve("Main.java"), "class Main {}");

        Path link = baseDir.resolve("alias.java");
        try {
            Files.createSymbolicLink(link, baseDir.resolve("Main.java"));
        } catch (java.io.IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false,
                    "filesystem does not support symbolic links; skipping: " + e);
            return;
        }

        String relative = "alias.java";
        assertTrue(BaseDirGuard.isWithinBaseDir(
                relative, resolve(baseDir, relative), baseDir),
                "a symlink resolving to a real path inside baseDir must be accepted");
    }
}
