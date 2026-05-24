package dev.sonarcli.cli.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link RuntimeLayout} — the {@code ~/.sonar/<version>/} directory
 * map for a provisioned runtime. No JRE is provisioned; the daemon runs on the
 * system Java.
 */
class RuntimeLayoutTest {

    @Test
    @DisplayName("exposes the plugins directory and engine jar paths")
    void exposesPaths(@TempDir Path base) {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");

        Path versionDir = base.resolve("10.24.0.81415");
        assertEquals(versionDir.resolve("plugins"), layout.pluginsDir());
        assertTrue(layout.engineJar().startsWith(versionDir.resolve("plugins")),
                "the engine jar lives in the plugins directory");
    }

    @Test
    @DisplayName("isProvisioned is false on an empty base directory")
    void notProvisionedWhenEmpty(@TempDir Path base) {
        RuntimeLayout layout = new RuntimeLayout(base, "1.2.3");
        assertFalse(layout.isProvisioned(),
                "an empty layout must not report as provisioned");
    }

    @Test
    @DisplayName("isProvisioned is true once the expected files exist")
    void provisionedWhenFilesPresent(@TempDir Path base) throws Exception {
        RuntimeLayout layout = new RuntimeLayout(base, "1.2.3");

        Files.createDirectories(layout.pluginsDir());
        Files.createFile(layout.pluginsDir().resolve("sonar-java-plugin-8.15.0.39343.jar"));
        Files.createFile(layout.engineJar());

        assertTrue(layout.isProvisioned(),
                "a layout with plugins and the engine jar must be provisioned");
    }

    /** Writes {@code content} to {@code file} and returns its hex SHA-256. */
    private static String writeJar(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        byte[] body = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, body);
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body));
    }

    /** Builds a layout whose engine + one plugin jar match the returned manifest. */
    private static Manifest fullyVerifiedLayout(RuntimeLayout layout) throws Exception {
        String engineSha = writeJar(layout.engineJar(), "engine-bytes");
        Path pluginJar = layout.pluginsDir().resolve("sonar-java-plugin-8.15.0.39343.jar");
        String pluginSha = writeJar(pluginJar, "java-plugin-bytes");

        Manifest.Artifact engine = new Manifest.Artifact(
                "org.sonarsource.sonarlint.core", "sonarlint-analysis-engine",
                "10.24.0.81415", engineSha);
        Manifest.Artifact plugin = new Manifest.Artifact(
                "org.sonarsource.java", "sonar-java-plugin", "8.15.0.39343", pluginSha);
        return new Manifest("10.24.0.81415", engine, java.util.List.of(plugin));
    }

    @Test
    @DisplayName("isVerified is true when every artifact is present and checksum-matches the manifest")
    void verifiedWhenAllArtifactsMatch(@TempDir Path base) throws Exception {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
        Manifest manifest = fullyVerifiedLayout(layout);
        assertTrue(layout.isVerified(manifest),
                "a complete checksum-matching layout must verify");
    }

    @Test
    @DisplayName("isVerified is false when a plugin jar's content does not match the manifest SHA-256")
    void notVerifiedWhenChecksumMismatch(@TempDir Path base) throws Exception {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
        Manifest good = fullyVerifiedLayout(layout);
        // Corrupt the plugin jar on disk: its content no longer hashes to the
        // manifest's pinned SHA-256.
        Path pluginJar = layout.pluginsDir().resolve("sonar-java-plugin-8.15.0.39343.jar");
        Files.writeString(pluginJar, "tampered-content");
        assertFalse(layout.isVerified(good),
                "a layout whose plugin jar fails its manifest checksum must not verify");
    }

    @Test
    @DisplayName("isVerified is false when an expected plugin from the manifest is missing")
    void notVerifiedWhenPluginMissing(@TempDir Path base) throws Exception {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
        Manifest onePlugin = fullyVerifiedLayout(layout);
        // The manifest now expects a SECOND plugin that was never provisioned.
        Manifest.Artifact extra = new Manifest.Artifact(
                "org.sonarsource.python", "sonar-python-plugin", "5.5.0.23291",
                "a".repeat(64));
        var plugins = new java.util.ArrayList<>(onePlugin.plugins());
        plugins.add(extra);
        Manifest twoPlugins = new Manifest(onePlugin.version(), onePlugin.engine(),
                plugins);
        assertFalse(layout.isVerified(twoPlugins),
                "a layout missing an expected manifest plugin must not verify");
    }

    @Test
    @DisplayName("isVerified is false when the plugins directory holds an extra non-manifest jar")
    void notVerifiedWhenExtraPluginJarPresent(@TempDir Path base) throws Exception {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
        Manifest manifest = fullyVerifiedLayout(layout);
        // Drop an extra jar the manifest never named into the plugins dir.
        Files.write(layout.pluginsDir().resolve("rogue-plugin-1.0.0.jar"),
                "rogue".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(layout.isVerified(manifest),
                "a plugins directory with an unexpected jar must not verify");
    }

    @Test
    @DisplayName("manifestJarNames lists exactly the engine plus every manifest plugin jar")
    void manifestJarNamesCoversEngineAndPlugins(@TempDir Path base) throws Exception {
        RuntimeLayout layout = new RuntimeLayout(base, "10.24.0.81415");
        Manifest manifest = fullyVerifiedLayout(layout);
        java.util.Set<String> names = layout.manifestJarNames(manifest);
        assertTrue(names.contains("sonarlint-analysis-engine.jar"),
                "the engine jar must be in the manifest jar set: " + names);
        assertTrue(names.contains("sonar-java-plugin-8.15.0.39343.jar"),
                "the java plugin jar must be in the manifest jar set: " + names);
        assertEquals(2, names.size(),
                "the manifest jar set must hold exactly the engine and its plugins");
    }

    @Test
    @DisplayName("the base directory defaults to ~/.sonar")
    void defaultBaseIsHomeDotSonar() {
        RuntimeLayout layout = RuntimeLayout.forVersion("9.9.9");
        Path expected = Path.of(System.getProperty("user.home"), ".sonar", "9.9.9");
        assertEquals(expected, layout.versionDir(),
                "the default base directory must be ~/.sonar");
    }

    @Test
    @DisplayName("the base directory is overridable via the sonar.home property")
    void baseOverridableViaProperty(@TempDir Path custom) {
        String previous = System.getProperty(RuntimeLayout.HOME_PROPERTY);
        System.setProperty(RuntimeLayout.HOME_PROPERTY, custom.toString());
        try {
            RuntimeLayout layout = RuntimeLayout.forVersion("3.3.3");
            assertEquals(custom.resolve("3.3.3"), layout.versionDir(),
                    "the sonar.home property must override the base directory");
        } finally {
            if (previous == null) {
                System.clearProperty(RuntimeLayout.HOME_PROPERTY);
            } else {
                System.setProperty(RuntimeLayout.HOME_PROPERTY, previous);
            }
        }
    }
}
