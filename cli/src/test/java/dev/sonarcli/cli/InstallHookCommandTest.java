package dev.sonarcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.PingResponse;
import dev.sonarcli.protocol.dto.RuleMetadata;

import picocli.CommandLine;

/**
 * Exercises {@code sonar install-hook}: writes an executable {@code pre-push}
 * git hook, refuses outside a git repo, and never silently clobbers an existing
 * hook.
 */
class InstallHookCommandTest {

    /** A no-op daemon RPC — install-hook never touches the daemon. */
    private static final class StubRpc implements DaemonRpc {
        @Override
        public PingResponse ping() {
            return new PingResponse("0.1.0-test", 1L, List.of("java"));
        }

        @Override
        public AnalyzeResponse analyze(AnalyzeRequest request) {
            return new AnalyzeResponse(List.of(), List.of());
        }

        @Override
        public RuleMetadata ruleMetadata(String ruleKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RuleMetadata> ruleCatalog() {
            return List.of();
        }

        @Override
        public void shutdown() {
        }
    }

    /** A no-op daemon control. */
    private static final class StubControl implements DaemonControl {
        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void start() {
        }

        @Override
        public boolean stop() {
            return true;
        }
    }

    private record Run(int exitCode, String out, String err) {
    }

    private static Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        SonarCommand root = new SonarCommand(new StubRpc(), new StubControl());
        CommandLine cmd = SonarCommand.configure(new CommandLine(root))
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        int code = cmd.execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    private static void gitInit(Path dir) throws Exception {
        Process p = new ProcessBuilder("git", "init", "-q")
                .directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git init failed");
        }
    }

    @Test
    @DisplayName("install-hook writes an executable pre-push hook in a git repo")
    void installsExecutablePrePushHook(@TempDir Path dir) throws Exception {
        gitInit(dir);

        Run run = run("install-hook", "--project", dir.toString());

        assertEquals(0, run.exitCode(), "install-hook must exit 0, err: " + run.err());
        Path hook = dir.resolve(".git/hooks/pre-push");
        assertTrue(Files.isRegularFile(hook), "a pre-push hook file must be written");
        assertTrue(Files.isExecutable(hook), "the pre-push hook must be executable");
        String body = Files.readString(hook);
        assertTrue(body.contains("sonar check --diff"),
                "the hook must invoke sonar check --diff, got:\n" + body);
    }

    @Test
    @DisplayName("install-hook outside a git repo refuses with exit 2")
    void refusesOutsideGitRepo(@TempDir Path dir) {
        Run run = run("install-hook", "--project", dir.toString());

        assertEquals(2, run.exitCode(), "outside a git repo install-hook must exit 2");
        assertFalse(run.err().isBlank(), "the refusal must be explained on stderr");
    }

    @Test
    @DisplayName("install-hook does not silently clobber an existing pre-push hook")
    void doesNotClobberExistingHook(@TempDir Path dir) throws Exception {
        gitInit(dir);
        Path hooks = dir.resolve(".git/hooks");
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("pre-push");
        String existing = "#!/bin/sh\necho existing hook\n";
        Files.writeString(hook, existing);

        Run run = run("install-hook", "--project", dir.toString());

        // Either it refuses (exit 2) or it backs the old hook up — never a silent overwrite.
        if (run.exitCode() == 2) {
            assertEquals(existing, Files.readString(hook),
                    "a refusal must leave the existing hook untouched");
        } else {
            assertEquals(0, run.exitCode(), "a non-refusal must be a clean install");
            boolean backedUp = Files.list(hooks)
                    .anyMatch(p -> p.getFileName().toString().startsWith("pre-push.")
                            && !p.getFileName().toString().equals("pre-push"));
            assertTrue(backedUp, "the existing hook must be backed up before overwrite");
        }
    }

    @Test
    @DisplayName("install-hook is idempotent: re-running over our own hook succeeds")
    void rerunOverOwnHookSucceeds(@TempDir Path dir) throws Exception {
        gitInit(dir);

        assertEquals(0, run("install-hook", "--project", dir.toString()).exitCode());
        Run second = run("install-hook", "--project", dir.toString());

        assertEquals(0, second.exitCode(),
                "re-installing over our own hook must succeed, err: " + second.err());
    }
}
