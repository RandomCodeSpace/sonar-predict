package io.github.randomcodespace.sonarpredict.cli.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

/**
 * Exercises {@code sonar setup} end to end against stubbed sources — a loopback
 * {@link HttpServer} serving fake plugin/engine jars. No public network is
 * touched. Verifies success, that {@code --repo} is threaded through, that
 * {@code --offline} provisions with no network, and that a checksum failure
 * exits 2 naming the artifact. No JRE is provisioned — the daemon runs on the
 * system Java.
 */
class SetupCommandTest {

    private HttpServer server;
    private Manifest manifest;
    private final AtomicReference<String> seenRepoBase = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        Manifest.Artifact engine = serveArtifact(
                "org.sonarsource.sonarlint.core", "sonarlint-analysis-engine", "10.24.0.81415");
        Manifest.Artifact java = serveArtifact(
                "org.sonarsource.java", "sonar-java-plugin", "8.15.0.39343");
        manifest = new Manifest("10.24.0.81415", engine, List.of(java));

        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private Manifest.Artifact serveArtifact(String group, String name, String version)
            throws Exception {
        byte[] body = ("jar:" + name + ":" + version + "\n").getBytes(StandardCharsets.UTF_8);
        String sha = sha256(body);
        Manifest.Artifact a = new Manifest.Artifact(group, name, version, sha);
        server.createContext("/" + a.mavenPath(), exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        return a;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** A runner factory wired to this test's HTTP server. */
    private SetupRunner stubRunner(SetupCommand.RunnerInputs inputs) {
        seenRepoBase.set(inputs.repoBase());
        Downloader downloader = new Downloader();
        return new SetupRunner(manifest, inputs.repoBase(), inputs.layout(),
                new PluginProvisioner(downloader));
    }

    /** Builds a {@code sonar setup} command line that provisions into {@code base}. */
    private CommandLine setupCommandInto(Path base) {
        System.setProperty(RuntimeLayout.HOME_PROPERTY, base.toString());
        SetupCommand command = new SetupCommand(this::stubRunner);
        return new CommandLine(command);
    }

    @Test
    @DisplayName("sonar setup provisions plugins and engine then reports success")
    void provisionsAndReportsSuccess(@TempDir Path base) throws Exception {
        try (StringWriter out = new StringWriter();
                PrintWriter writer = new PrintWriter(out)) {
            CommandLine cmd = setupCommandInto(base);
            cmd.setOut(writer);
            int code = cmd.execute("--repo", baseUrl());

            assertEquals(0, code, "a successful setup must exit 0");
            assertTrue(out.toString().contains("runtime ready"),
                    "setup must report the runtime is ready, got: " + out);

            RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
            assertTrue(layout.isProvisioned(),
                    "the layout must be fully provisioned after setup");
        } finally {
            System.clearProperty(RuntimeLayout.HOME_PROPERTY);
        }
    }

    @Test
    @DisplayName("--repo is threaded through to the PluginProvisioner")
    void repoOptionIsThreaded(@TempDir Path base) throws Exception {
        try (StringWriter sink = new StringWriter();
                PrintWriter writer = new PrintWriter(sink)) {
            CommandLine cmd = setupCommandInto(base);
            cmd.setOut(writer);
            cmd.execute("--repo", baseUrl());

            assertEquals(baseUrl(), seenRepoBase.get(),
                    "the --repo value must reach the runner");
        } finally {
            System.clearProperty(RuntimeLayout.HOME_PROPERTY);
        }
    }

    @Test
    @DisplayName("--offline provisions from a local archive with no network")
    void offlineProvisioning(@TempDir Path base, @TempDir Path src) throws Exception {
        // Build a runtime bundle mirroring the provisioned layout. The engine
        // and plugin jar bodies match the manifest's pinned checksums (the
        // manifest artifacts in setUp serve "jar:<name>:<version>\n"), so the
        // offline path can verify the bundle, not just check presence.
        Path bundle = src.resolve("runtime.tar.gz");
        byte[] engineBody = ("jar:sonarlint-analysis-engine:10.24.0.81415\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] javaBody = ("jar:sonar-java-plugin:8.15.0.39343\n")
                .getBytes(StandardCharsets.UTF_8);
        try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(bundle));
                TarWriter tar = new TarWriter(gz)) {
            tar.addDirectory("plugins/");
            tar.addFile("plugins/sonarlint-analysis-engine.jar", engineBody, false);
            tar.addFile("plugins/sonar-java-plugin-8.15.0.39343.jar", javaBody, false);
        }
        // Stop the server: --offline must not touch the network.
        server.stop(0);

        try (StringWriter out = new StringWriter();
                PrintWriter writer = new PrintWriter(out)) {
            CommandLine cmd = setupCommandInto(base);
            cmd.setOut(writer);
            int code = cmd.execute("--offline", bundle.toString());

            assertEquals(0, code, "offline setup must exit 0");
            RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
            assertTrue(layout.isProvisioned(),
                    "the layout must be provisioned from the offline archive");
            assertTrue(layout.isVerified(manifest),
                    "the offline-provisioned layout must verify against the manifest");
        } finally {
            System.clearProperty(RuntimeLayout.HOME_PROPERTY);
        }
    }

    @Test
    @DisplayName("a checksum failure exits 2 with a message naming the artifact")
    void checksumFailureExitsTwo(@TempDir Path base) throws Exception {
        // Tamper the java plugin's expected checksum.
        Manifest.Artifact java = manifest.plugins().get(0);
        Manifest tampered = new Manifest("10.24.0.81415", manifest.engine(),
                List.of(new Manifest.Artifact(java.groupId(), java.artifactId(),
                        java.version(), "0".repeat(64))));
        Manifest original = manifest;
        manifest = tampered;

        try (StringWriter err = new StringWriter();
                StringWriter outSink = new StringWriter();
                PrintWriter outWriter = new PrintWriter(outSink);
                PrintWriter errWriter = new PrintWriter(err)) {
            CommandLine cmd = setupCommandInto(base);
            cmd.setOut(outWriter);
            cmd.setErr(errWriter);
            int code = cmd.execute("--repo", baseUrl());

            assertEquals(2, code, "a checksum failure must exit 2");
            assertTrue(err.toString().contains("sonar-java-plugin"),
                    "the failure must name the offending artifact, got: " + err);
        } finally {
            manifest = original;
            System.clearProperty(RuntimeLayout.HOME_PROPERTY);
        }
    }
}
