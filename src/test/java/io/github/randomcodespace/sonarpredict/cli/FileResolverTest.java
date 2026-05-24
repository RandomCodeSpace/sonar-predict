package io.github.randomcodespace.sonarpredict.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link FileResolver}: explicit file lists, project directory
 * walks, and {@code git diff} resolution. The diff test builds a real temp git
 * repository so the {@code git} invocation is exercised end to end.
 */
class FileResolverTest {

    private final FileResolver resolver = new FileResolver();

    @Test
    @DisplayName("explicit files pass through as base-relative paths")
    void explicitFilesPassThrough(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("A.java"), "class A {}");
        Path b = Files.writeString(dir.resolve("B.java"), "class B {}");

        FileResolver.ResolvedFiles resolved =
                resolver.resolveExplicit(java.util.List.of(a.toString(), b.toString()));

        assertEquals(2, resolved.relativePaths().size(), "both files must be resolved");
        assertTrue(resolved.relativePaths().contains("A.java"));
        assertTrue(resolved.relativePaths().contains("B.java"));
        // Every relative path must resolve back to a real file under the base.
        for (String rel : resolved.relativePaths()) {
            assertTrue(Files.isRegularFile(resolved.baseDir().resolve(rel)),
                    "resolved path must exist: " + rel);
        }
    }

    @Test
    @DisplayName("an explicit file that does not exist is rejected")
    void missingExplicitFileRejected(@TempDir Path dir) {
        String missing = dir.resolve("Ghost.java").toString();

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveExplicit(java.util.List.of(missing)),
                "a non-existent explicit file must be rejected, not silently dropped");
    }

    @Test
    @DisplayName("a project walk collects source files and skips target/, .git/, hidden dirs")
    void projectWalkSkipsBuildAndVcsDirs(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Main.java"), "class Main {}");
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src").resolve("App.java"), "class App {}");
        // Noise that must be skipped.
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("target").resolve("Built.java"), "class Built {}");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git").resolve("Hook.java"), "class Hook {}");
        Files.createDirectories(dir.resolve(".hidden"));
        Files.writeString(dir.resolve(".hidden").resolve("Secret.java"), "class Secret {}");
        Files.writeString(dir.resolve("README.md"), "# not source");

        FileResolver.ResolvedFiles resolved = resolver.resolveProject(dir);

        assertTrue(resolved.relativePaths().contains("Main.java"),
                "top-level source must be collected");
        assertTrue(resolved.relativePaths().contains("src/App.java"),
                "nested source must be collected");
        assertFalse(resolved.relativePaths().stream().anyMatch(p -> p.contains("target")),
                "target/ output must be skipped");
        assertFalse(resolved.relativePaths().stream().anyMatch(p -> p.contains(".git")),
                ".git/ must be skipped");
        assertFalse(resolved.relativePaths().stream().anyMatch(p -> p.contains(".hidden")),
                "hidden directories must be skipped");
        assertFalse(resolved.relativePaths().stream().anyMatch(p -> p.endsWith(".md")),
                "non-source files must be skipped");
    }

    @Test
    @DisplayName("--diff returns files changed against the given ref that still exist")
    void diffReturnsChangedExistingFiles(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test");
        Files.writeString(dir.resolve("Unchanged.java"), "class Unchanged {}");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "base");

        // Change one file, add another, in a new commit.
        Files.writeString(dir.resolve("Unchanged.java"), "class Unchanged { int x; }");
        Files.writeString(dir.resolve("Added.java"), "class Added {}");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "change");

        FileResolver.ResolvedFiles resolved = resolver.resolveDiff(dir, "HEAD~1");

        assertTrue(resolved.relativePaths().contains("Unchanged.java"),
                "a modified file must appear in the diff result");
        assertTrue(resolved.relativePaths().contains("Added.java"),
                "an added file must appear in the diff result");
        assertEquals(2, resolved.relativePaths().size(),
                "only the two changed files must be returned");
    }

    @Test
    @DisplayName("--diff defaults to HEAD when no ref is given")
    void diffDefaultsToHead(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test");
        Files.writeString(dir.resolve("Committed.java"), "class Committed {}");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "base");

        // An uncommitted modification — diff against HEAD must see it.
        Files.writeString(dir.resolve("Committed.java"), "class Committed { int y; }");

        FileResolver.ResolvedFiles resolved = resolver.resolveDiff(dir, null);

        assertTrue(resolved.relativePaths().contains("Committed.java"),
                "diff against the default ref (HEAD) must see the working-tree change");
    }

    private static void git(Path dir, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + code);
        }
    }
}
