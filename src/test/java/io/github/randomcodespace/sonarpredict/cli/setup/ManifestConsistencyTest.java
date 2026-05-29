package io.github.randomcodespace.sonarpredict.cli.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drift guard: every analyzer plugin the bundled manifest pins must actually be
 * staged in the {@code plugins/} directory with a SHA-256 matching the manifest.
 *
 * <p>The manifest is hand-tracked against the jars {@code pom.xml} resolves, and
 * that pairing has drifted before. This turns a future mismatch (a manifest pin
 * that no longer matches the jar the build actually stages) into a failing build
 * rather than an un-provisionable release. The engine jar is excluded: it is
 * shaded into the daemon fat jar, not staged as a separate plugin jar.
 */
class ManifestConsistencyTest {

    /** Where the build stages the analyzer jars (matches {@code PluginsDir} default). */
    private static final Path PLUGINS_DIR = Path.of("plugins");

    @Test
    @DisplayName("every manifest-pinned analyzer is staged in plugins/ with a matching SHA-256")
    void manifestPluginsMatchStagedJars() throws Exception {
        Manifest manifest = Manifest.bundled();
        // The build stages plugins/ during the test phase; if absent (e.g. an
        // isolated IDE run before the copy executions), skip rather than fail.
        Assumptions.assumeTrue(Files.isDirectory(PLUGINS_DIR),
                "staged plugins/ directory not present — skipping drift check");

        for (Manifest.Artifact plugin : manifest.plugins()) {
            Path jar = PLUGINS_DIR.resolve(plugin.jarName());
            assertTrue(Files.isRegularFile(jar),
                    "manifest pins " + plugin.jarName()
                            + " but it is not staged in plugins/ (version drift?)");
            assertEquals(plugin.sha256().toLowerCase(), sha256(jar),
                    "SHA-256 drift for " + plugin.jarName()
                            + " — the manifest pin does not match the staged jar");
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
