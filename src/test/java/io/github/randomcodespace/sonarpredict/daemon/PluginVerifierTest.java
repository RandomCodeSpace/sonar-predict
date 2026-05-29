package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.randomcodespace.sonarpredict.cli.setup.Manifest;

/**
 * Unit tests for {@link PluginVerifier}: it must accept exactly the
 * manifest-pinned analyzers (by name and SHA-256) plus the project's own engine
 * and host jars, and refuse to start on a tampered or unaccounted-for jar.
 */
class PluginVerifierTest {

    private static final String PLUGIN_JAR = "sonar-foo-plugin-1.0.jar";

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** A manifest with one analyzer plugin pinned to {@code pluginSha}. */
    private static Manifest manifestWith(String pluginSha) {
        Manifest.Artifact engine = new Manifest.Artifact(
                "org.sonarsource.sonarlint.core", "sonarlint-analysis-engine",
                "1.0", "0".repeat(64));
        Manifest.Artifact plugin = new Manifest.Artifact(
                "org.example", "sonar-foo-plugin", "1.0", pluginSha);
        return new Manifest("1.0", engine, List.of(plugin));
    }

    private static Set<String> names(Set<Path> jars) {
        return jars.stream().map(p -> p.getFileName().toString()).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a SHA-matching analyzer plus the host plugin are both allowed")
    void cleanDirAllowsManifestPluginAndHostPlugin(@TempDir Path dir) throws Exception {
        byte[] body = "real-analyzer".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve(PLUGIN_JAR), body);
        Files.write(dir.resolve("sonar-predictor-0.3.0-SNAPSHOT-host.jar"),
                "host".getBytes(StandardCharsets.UTF_8));

        Set<Path> verified = PluginVerifier.verifiedJars(dir, manifestWith(sha256(body)));

        assertEquals(
                Set.of(PLUGIN_JAR, "sonar-predictor-0.3.0-SNAPSHOT-host.jar"),
                names(verified),
                "the verified set must be the manifest analyzer plus the host plugin");
    }

    @Test
    @DisplayName("the embedded engine jar is allowed by name (no manifest pin needed)")
    void engineJarAllowedByName(@TempDir Path dir) throws Exception {
        byte[] body = "real-analyzer".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve(PLUGIN_JAR), body);
        Files.write(dir.resolve("sonarlint-analysis-engine.jar"),
                "engine".getBytes(StandardCharsets.UTF_8));

        Set<Path> verified = PluginVerifier.verifiedJars(dir, manifestWith(sha256(body)));

        assertTrue(names(verified).contains("sonarlint-analysis-engine.jar"),
                "the project's own engine jar must be allow-listed by name");
    }

    @Test
    @DisplayName("a tampered analyzer (SHA mismatch) makes the daemon refuse to start")
    void tamperedPluginRejected(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve(PLUGIN_JAR), "tampered".getBytes(StandardCharsets.UTF_8));
        // Manifest pins the SHA of DIFFERENT content than what is on disk.
        Manifest manifest = manifestWith(sha256("original".getBytes(StandardCharsets.UTF_8)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PluginVerifier.verifiedJars(dir, manifest));
        assertTrue(ex.getMessage().contains("SHA-256"),
                "the rejection must cite the failed checksum, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("an unaccounted-for jar makes the daemon refuse to start")
    void unaccountedJarRejected(@TempDir Path dir) throws Exception {
        byte[] body = "real-analyzer".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve(PLUGIN_JAR), body);
        Files.write(dir.resolve("evil-analyzer.jar"), "pwn".getBytes(StandardCharsets.UTF_8));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PluginVerifier.verifiedJars(dir, manifestWith(sha256(body))));
        assertTrue(ex.getMessage().contains("unaccounted"),
                "the rejection must flag the unaccounted jar, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("an empty plugins directory is rejected")
    void emptyDirRejected(@TempDir Path dir) {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PluginVerifier.verifiedJars(dir, manifestWith("0".repeat(64))));
        assertTrue(ex.getMessage().contains("no analyzer plugin"),
                "an empty directory must be rejected, got: " + ex.getMessage());
    }
}
