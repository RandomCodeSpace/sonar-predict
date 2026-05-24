package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.sonarcli.protocol.Json;
import dev.sonarcli.protocol.MessageCodec;
import dev.sonarcli.protocol.Method;
import dev.sonarcli.protocol.SocketPaths;
import dev.sonarcli.protocol.WireMessage;
import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.PingResponse;
import dev.sonarcli.protocol.dto.RuleMetadata;

/**
 * End-to-end: a real {@link Daemon} (warm engine, socket server, pidfile)
 * driven by a client {@link SocketChannel} over a Unix domain socket. Every
 * frame goes through the protocol {@link MessageCodec}. Deterministic — the
 * client waits on {@link Daemon#awaitListening()} and {@link Daemon#awaitStopped()},
 * never a sleep.
 */
class DaemonIntegrationTest {

    private static final Path FIXTURES = Path.of("src/test/resources/daemon/fixtures");

    @Test
    @DisplayName("ping, analyze, rule-metadata, shutdown over one Unix-socket connection")
    void fullRoundTripOverSocket(@TempDir Path dir) throws Exception {
        SocketPaths paths = SocketPaths.resolve(Map.of("XDG_RUNTIME_DIR", dir.toString()));
        Daemon daemon = Daemon.start(paths, Duration.ofMinutes(10));
        assertTrue(daemon.startedNewListener(), "the test must own the listening daemon");
        daemon.awaitListening();

        try (SocketChannel channel =
                SocketChannel.open(UnixDomainSocketAddress.of(paths.socket()))) {
            OutputStream out = Channels.newOutputStream(channel);
            InputStream in = Channels.newInputStream(channel);

            // 1. PING -> daemon version + loaded languages.
            MessageCodec.writeMessage(out, new WireMessage("ping", Method.PING, null));
            WireMessage pingResp = MessageCodec.readMessage(in);
            assertEquals("ping", pingResp.id());
            PingResponse ping =
                    Json.mapper().convertValue(pingResp.payload(), PingResponse.class);
            assertNotNull(ping.daemonVersion(), "PING must report a daemon version");
            assertFalse(ping.loadedLanguages().isEmpty(),
                    "PING must report the loaded languages");

            // 2. ANALYZE a real Java fixture -> AnalyzeResponse with java:S1118.
            AnalyzeRequest analyzeReq = new AnalyzeRequest(
                    FIXTURES.toAbsolutePath().toString(),
                    List.of("java/UtilityClass.java"), List.of(), null, List.of());
            MessageCodec.writeMessage(out, new WireMessage(
                    "analyze", Method.ANALYZE, Json.mapper().valueToTree(analyzeReq)));
            WireMessage analyzeResp = MessageCodec.readMessage(in);
            assertEquals("analyze", analyzeResp.id());
            AnalyzeResponse analyze =
                    Json.mapper().convertValue(analyzeResp.payload(), AnalyzeResponse.class);
            assertTrue(analyze.issues().stream().anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "ANALYZE must raise java:S1118, got: " + analyze.issues());

            // 3. RULE_METADATA -> metadata for the rule the analysis just raised.
            MessageCodec.writeMessage(out, new WireMessage(
                    "meta", Method.RULE_METADATA, new TextNode("java:S1118")));
            WireMessage metaResp = MessageCodec.readMessage(in);
            assertEquals("meta", metaResp.id());
            RuleMetadata meta =
                    Json.mapper().convertValue(metaResp.payload(), RuleMetadata.class);
            assertEquals("java:S1118", meta.ruleKey());
            assertEquals("java", meta.language());
            assertNotNull(meta.name(), "rule metadata must carry a name");

            // The engine stayed warm: a second analysis on the same connection
            // still works without any reload.
            MessageCodec.writeMessage(out, new WireMessage(
                    "analyze2", Method.ANALYZE, Json.mapper().valueToTree(analyzeReq)));
            WireMessage analyzeResp2 = MessageCodec.readMessage(in);
            AnalyzeResponse analyze2 =
                    Json.mapper().convertValue(analyzeResp2.payload(), AnalyzeResponse.class);
            assertTrue(analyze2.issues().stream().anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "second ANALYZE on the warm daemon must still raise java:S1118");

            // 4. SHUTDOWN -> ack, then the server stops.
            MessageCodec.writeMessage(out, new WireMessage("bye", Method.SHUTDOWN, null));
            WireMessage shutdownResp = MessageCodec.readMessage(in);
            assertEquals("bye", shutdownResp.id());
            assertEquals(Method.SHUTDOWN, shutdownResp.method());
        }

        daemon.awaitStopped();
        assertFalse(Files.exists(paths.socket()), "socket must be removed after SHUTDOWN");
        assertFalse(Files.exists(paths.pidFile()),
                "pidfile must be removed after SHUTDOWN");
    }
}
