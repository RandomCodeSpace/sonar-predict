package io.github.randomcodespace.sonarpredict.cli.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The directory map of a provisioned runtime under {@code ~/.sonar/<version>/}.
 *
 * <p>A provisioned runtime holds the analyzer plugin jars plus the analysis
 * engine jar in {@link #pluginsDir()}. The daemon runs on the system Java that
 * launched the CLI — no JRE is provisioned.
 *
 * <p><b>Base directory.</b> The default base is {@code ~/.sonar}; the
 * {@code sonar.home} system property overrides it (the seam tests use to point
 * at a temp directory). The {@code <version>} segment keys the layout so
 * multiple pinned runtimes can coexist.
 */
public final class RuntimeLayout {

    /** System property overriding the {@code ~/.sonar} base directory. */
    public static final String HOME_PROPERTY = "sonar.home";

    private final Path versionDir;

    /**
     * @param baseDir the {@code ~/.sonar}-equivalent base directory
     * @param version the runtime version segment
     */
    public RuntimeLayout(Path baseDir, String version) {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(version, "version");
        this.versionDir = baseDir.resolve(version);
    }

    /**
     * Builds a layout for {@code version} under the default base directory —
     * {@code ~/.sonar}, or the {@code sonar.home} override if set.
     *
     * @param version the runtime version segment
     * @return the layout
     */
    public static RuntimeLayout forVersion(String version) {
        return new RuntimeLayout(defaultBase(), version);
    }

    private static Path defaultBase() {
        String override = System.getProperty(HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".sonar");
    }

    /** The {@code <base>/<version>} root of this runtime. */
    public Path versionDir() {
        return versionDir;
    }

    /** The directory holding the analyzer plugin jars and the engine jar. */
    public Path pluginsDir() {
        return versionDir.resolve("plugins");
    }

    /** The analysis engine jar inside {@link #pluginsDir()}. */
    public Path engineJar() {
        return pluginsDir().resolve("sonarlint-analysis-engine.jar");
    }

    /**
     * The complete set of jar file names a verified plugins directory must
     * contain — the engine jar plus every manifest plugin jar, and nothing
     * else.
     *
     * @param manifest the pinned runtime manifest
     * @return the exact expected jar file-name set
     */
    public Set<String> manifestJarNames(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        Set<String> names = new LinkedHashSet<>();
        names.add(engineJar().getFileName().toString());
        for (Manifest.Artifact plugin : manifest.plugins()) {
            names.add(plugin.jarName());
        }
        return names;
    }

    /**
     * Whether this runtime is fully provisioned: the engine jar exists and the
     * plugins directory holds at least one analyzer jar.
     *
     * <p>This is a cheap <em>presence</em> check only — it does not verify the
     * jars' contents. For a launch-trust decision use {@link #isVerified}.
     *
     * @return {@code true} if every expected artifact is in place
     */
    public boolean isProvisioned() {
        return Files.isRegularFile(engineJar())
                && hasAnyPluginJar();
    }

    /**
     * Whether this runtime is provisioned <em>and trustworthy</em>: every
     * engine + plugin jar named in {@code manifest} is present and its content
     * hashes to the manifest's pinned SHA-256, and the plugins directory holds
     * <em>exactly</em> that jar set (no missing analyzers and no extra,
     * unaccounted-for jars). Only a layout that passes this should be launched.
     *
     * <p><b>Exact plugin set.</b> The plugins directory must contain the
     * manifest jar set and nothing more. An extra jar that is not engine and
     * not a manifest plugin fails verification — it could be an attacker-placed
     * analyzer that {@code PluginRuntime} would otherwise load.
     *
     * @param manifest the pinned runtime manifest to verify against
     * @return {@code true} only if every artifact is present and matches
     */
    public boolean isVerified(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (!jarMatches(engineJar(), manifest.engine().sha256())) {
            return false;
        }
        for (Manifest.Artifact plugin : manifest.plugins()) {
            Path pluginJar = pluginsDir().resolve(plugin.jarName());
            if (!jarMatches(pluginJar, plugin.sha256())) {
                return false;
            }
        }
        return pluginsDirHoldsExactlyManifestJars(manifest);
    }

    /**
     * Whether the plugins directory contains exactly the manifest jar set —
     * no missing jars and no extra ones. The per-jar checksum match is checked
     * separately by {@link #isVerified}; this guards against an <em>extra</em>
     * jar slipping into the directory.
     */
    private boolean pluginsDirHoldsExactlyManifestJars(Manifest manifest) {
        Set<String> expected = manifestJarNames(manifest);
        Path plugins = pluginsDir();
        if (!Files.isDirectory(plugins)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(plugins)) {
            Set<String> actual = new LinkedHashSet<>();
            entries.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".jar"))
                    .forEach(actual::add);
            return actual.equals(expected);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether {@code jar} exists and its content's SHA-256 equals
     * {@code expectedSha256} (case-insensitive hex). A missing or unreadable
     * file, or any mismatch, returns {@code false}.
     */
    private static boolean jarMatches(Path jar, String expectedSha256) {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(jar)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            return actual.equalsIgnoreCase(expectedSha256);
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }

    private boolean hasAnyPluginJar() {
        Path plugins = pluginsDir();
        if (!Files.isDirectory(plugins)) {
            return false;
        }
        try (var entries = Files.list(plugins)) {
            return entries.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".jar") && name.startsWith("sonar-");
            });
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
