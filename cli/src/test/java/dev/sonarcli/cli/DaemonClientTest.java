package dev.sonarcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.sonarcli.protocol.MessageCodec;
import dev.sonarcli.protocol.Method;
import dev.sonarcli.protocol.SocketPaths;
import dev.sonarcli.protocol.WireMessage;
import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.PingResponse;

/**
 * Drives {@link DaemonClient} against a real daemon spawned by
 * {@link DaemonLauncher}. Deterministic: every wait polls observable state
 * (the socket accepting, the socket file gone). Each test isolates the daemon
 * in a temp {@code XDG_RUNTIME_DIR} and shuts it down in a {@code finally}.
 */
class DaemonClientTest {

    private static final Path FIXTURES =
            Path.of("src/test/resources/fixtures").toAbsolutePath();

    @Test
    @DisplayName("ping() returns a PingResponse from a running daemon")
    void pingReturnsResponse(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        DaemonLauncher launcher = launcher(paths);
        try {
            launcher.start();
            DaemonClient client = new DaemonClient(paths, launcher);

            PingResponse ping = client.ping();

            assertNotNull(ping.daemonVersion(), "ping must report a daemon version");
            assertFalse(ping.loadedLanguages().isEmpty(),
                    "ping must report the loaded languages");
        } finally {
            shutdown(paths);
        }
    }

    @Test
    @DisplayName("analyze() returns an AnalyzeResponse with the expected issues")
    void analyzeReturnsResponse(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        DaemonLauncher launcher = launcher(paths);
        try {
            launcher.start();
            DaemonClient client = new DaemonClient(paths, launcher);

            AnalyzeResponse response = client.analyze(new AnalyzeRequest(
                    FIXTURES.toString(),
                    List.of("java/UtilityClass.java"), List.of(), null, List.of()));

            assertTrue(response.issues().stream()
                            .anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "analyze must raise java:S1118, got: " + response.issues());
        } finally {
            shutdown(paths);
        }
    }

    @Test
    @DisplayName("a call with no daemon running auto-starts it via DaemonLauncher")
    void autoStartsDaemonWhenNotRunning(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        DaemonLauncher launcher = launcher(paths);
        try {
            assertFalse(launcher.isDaemonRunning(), "no daemon should be running yet");
            DaemonClient client = new DaemonClient(paths, launcher);

            // No explicit launcher.start() — the client must auto-start.
            PingResponse ping = client.ping();

            assertNotNull(ping.daemonVersion(),
                    "the client must auto-start the daemon and complete the call");
            assertTrue(launcher.isDaemonRunning(),
                    "the daemon must be running after an auto-started call");
        } finally {
            shutdown(paths);
        }
    }

    @Test
    @DisplayName("an {\"error\":...} payload surfaces as a thrown DaemonException")
    void errorPayloadThrowsDaemonException(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        DaemonLauncher launcher = launcher(paths);
        try {
            launcher.start();
            DaemonClient client = new DaemonClient(paths, launcher);

            // RULE_METADATA for an unknown rule -> the daemon returns an
            // {"error": "..."} payload; the client must throw, not return junk.
            DaemonException thrown = assertThrows(DaemonException.class,
                    () -> client.ruleMetadata("java:NOPE9999"));

            assertTrue(thrown.getMessage().toLowerCase().contains("unknown rule")
                            || thrown.getMessage().toLowerCase().contains("nope9999"),
                    "the exception must carry the daemon's error text, got: "
                            + thrown.getMessage());
        } finally {
            shutdown(paths);
        }
    }

    private static SocketPaths paths(Path dir) {
        return SocketPaths.resolve(Map.of("XDG_RUNTIME_DIR", dir.toString()));
    }

    private static DaemonLauncher launcher(SocketPaths paths) {
        return new DaemonLauncher(paths, Duration.ofSeconds(60));
    }

    private static void shutdown(SocketPaths paths) throws Exception {
        if (Files.exists(paths.socket())) {
            try (SocketChannel channel =
                    SocketChannel.open(UnixDomainSocketAddress.of(paths.socket()))) {
                MessageCodec.writeMessage(Channels.newOutputStream(channel),
                        new WireMessage("shutdown", Method.SHUTDOWN, null));
                MessageCodec.readMessage(Channels.newInputStream(channel));
            } catch (Exception ignored) {
                // Daemon already gone.
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (Files.exists(paths.socket()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
