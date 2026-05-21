package dev.sonarcli.cli.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link PluginProvisioner} against a loopback {@link HttpServer} that
 * serves fake jars at the Maven repository paths the manifest dictates. No
 * public network is touched: the test builds its own manifest whose checksums
 * match the fake jars it serves.
 */
class PluginProvisionerTest {

    private HttpServer server;
    private Manifest manifest;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        Manifest.Artifact engine = artifact(
                "org.sonarsource.sonarlint.core", "sonarlint-analysis-engine", "10.24.0.81415");
        Manifest.Artifact javaPlugin = artifact(
                "org.sonarsource.java", "sonar-java-plugin", "8.15.0.39343");
        Manifest.Artifact pyPlugin = artifact(
                "org.sonarsource.python", "sonar-python-plugin", "5.5.0.23291");

        manifest = new Manifest("10.24.0.81415", engine,
                List.of(javaPlugin, pyPlugin));

        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Registers a fake jar at the artifact's Maven path; returns it with its real sha256. */
    private Manifest.Artifact artifact(String group, String name, String version)
            throws Exception {
        byte[] body = ("fake-jar:" + name + ":" + version + "\n").getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body));
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

    @Test
    @DisplayName("provisions the engine and every plugin into the plugins directory")
    void provisionsEngineAndPlugins(@TempDir Path base) {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");

        new PluginProvisioner(new Downloader()).provision(manifest, baseUrl(), layout);

        assertTrue(Files.isRegularFile(layout.engineJar()),
                "the engine jar must be provisioned");
        assertTrue(Files.isRegularFile(
                        layout.pluginsDir().resolve("sonar-java-plugin-8.15.0.39343.jar")),
                "the java plugin must be provisioned");
        assertTrue(Files.isRegularFile(
                        layout.pluginsDir().resolve("sonar-python-plugin-5.5.0.23291.jar")),
                "the python plugin must be provisioned");
    }

    @Test
    @DisplayName("a checksum mismatch fails the provisioning")
    void checksumMismatchFails(@TempDir Path base) {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
        Manifest tampered = new Manifest("10.24.0.81415",
                new Manifest.Artifact(manifest.engine().groupId(),
                        manifest.engine().artifactId(), manifest.engine().version(),
                        "0".repeat(64)),
                manifest.plugins());

        assertThrows(DownloadException.class,
                () -> new PluginProvisioner(new Downloader())
                        .provision(tampered, baseUrl(), layout));
    }

    @Test
    @DisplayName("an already-provisioned layout is a no-op")
    void idempotent(@TempDir Path base) {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
        PluginProvisioner provisioner = new PluginProvisioner(new Downloader());

        provisioner.provision(manifest, baseUrl(), layout);
        long firstModified = lastModified(layout.engineJar());

        // Stop the server: a second provision must not re-download anything.
        server.stop(0);
        provisioner.provision(manifest, baseUrl(), layout);

        assertEquals(firstModified, lastModified(layout.engineJar()),
                "a second provision of a complete layout must not re-fetch");
    }

    @Test
    @DisplayName("a pre-existing jar whose content does not match the manifest is re-fetched")
    void corruptExistingJarIsReFetched(@TempDir Path base) throws Exception {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
        PluginProvisioner provisioner = new PluginProvisioner(new Downloader());

        // First provision: every jar is downloaded and checksum-verified.
        provisioner.provision(manifest, baseUrl(), layout);
        Path javaJar = layout.pluginsDir().resolve("sonar-java-plugin-8.15.0.39343.jar");
        assertTrue(Files.isRegularFile(javaJar), "the java plugin must be provisioned");

        // Corrupt the on-disk java plugin jar: its content no longer matches
        // the manifest's pinned SHA-256.
        Files.writeString(javaJar, "corrupted bytes");

        // A second provision must NOT silently trust the corrupt jar — it must
        // delete and re-fetch it, restoring the verified content.
        provisioner.provision(manifest, baseUrl(), layout);

        byte[] expected = ("fake-jar:sonar-java-plugin:8.15.0.39343\n")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(expected)),
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(javaJar))),
                "the corrupt jar must have been re-fetched to its verified content");
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
