package io.github.randomcodespace.sonarpredict.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import io.github.randomcodespace.sonarpredict.cli.setup.Manifest;

/**
 * Builds the verified allow-list of analyzer plugin jars the daemon may load.
 *
 * <p>This moves the trust decision the launcher's
 * {@code RuntimeLayout.isVerified} makes into the daemon process itself: rather
 * than blindly globbing every {@code *.jar} in the plugins directory (which the
 * launcher's verification cannot vouch for, since it runs in a different
 * process), the daemon hands {@link PluginRuntime#loadFrom} an explicit
 * allow-list. A jar that is neither a manifest-pinned analyzer (matched by name
 * <em>and</em> SHA-256) nor one of the project's own co-distributed jars causes
 * a refuse-to-start, so an attacker-placed analyzer dropped into the plugins
 * directory is never loaded into the engine.
 *
 * <p>Unlike {@code RuntimeLayout.isVerified} it tolerates the daemon's two real
 * compositions instead of demanding the exact provisioned set:
 * <ul>
 *   <li><b>dev / bundle</b> — the ten analyzer plugins (SHA-verified) plus the
 *       {@code sonar-predictor} host plugin; the analysis engine is shaded into
 *       the daemon fat jar, so no engine jar is present.</li>
 *   <li><b>setup-provisioned</b> — the ten analyzer plugins (SHA-verified) plus
 *       the {@code sonarlint-analysis-engine.jar} the launcher already
 *       SHA-verified against the manifest; no host plugin.</li>
 * </ul>
 * Both the engine jar and the host plugin are the project's own artifacts
 * (allow-listed by name), not third-party analyzers, so they carry no separate
 * manifest pin here.
 */
final class PluginVerifier {

    private static final String ENGINE_JAR = "sonarlint-analysis-engine.jar";
    private static final String HOST_PLUGIN_PREFIX = "sonar-predictor-";

    private PluginVerifier() {
    }

    /**
     * Returns the verified set of plugin jars in {@code pluginsDir} to hand to
     * {@link PluginRuntime#loadFrom}.
     *
     * @param pluginsDir the directory holding the analyzer plugin jars
     * @param manifest   the pinned runtime manifest (per-plugin SHA-256)
     * @return the exact, verified jar set the daemon may load
     * @throws IllegalStateException if a manifest-pinned analyzer fails its
     *                               SHA-256 check, an unaccounted-for jar is
     *                               present, or no loadable jar is found
     */
    static Set<Path> verifiedJars(Path pluginsDir, Manifest manifest) {
        Map<String, String> pinnedSha = new HashMap<>();
        for (Manifest.Artifact plugin : manifest.plugins()) {
            pinnedSha.put(plugin.jarName(), plugin.sha256());
        }

        Set<Path> allowed = new LinkedHashSet<>();
        for (Path jar : listJars(pluginsDir)) {
            String name = jar.getFileName().toString();
            String pinned = pinnedSha.get(name);
            if (pinned != null) {
                if (!sha256(jar).equalsIgnoreCase(pinned)) {
                    throw new IllegalStateException(
                            "refusing to start: analyzer plugin failed SHA-256 verification: "
                                    + name + " in " + pluginsDir.toAbsolutePath());
                }
                allowed.add(jar);
            } else if (isProjectJar(name)) {
                allowed.add(jar);
            } else {
                throw new IllegalStateException(
                        "refusing to start: unaccounted jar in the plugins directory "
                                + "(neither a manifest-pinned analyzer nor a project jar): "
                                + name + " in " + pluginsDir.toAbsolutePath());
            }
        }
        if (allowed.isEmpty()) {
            throw new IllegalStateException(
                    "no analyzer plugin jars found in " + pluginsDir.toAbsolutePath());
        }
        return allowed;
    }

    /**
     * Whether {@code jarName} is one of the project's own co-distributed jars —
     * the embedded analysis engine jar or the {@code sonar-predictor} host
     * plugin — allow-listed by name rather than by a third-party manifest pin.
     */
    private static boolean isProjectJar(String jarName) {
        return jarName.equals(ENGINE_JAR)
                || (jarName.startsWith(HOST_PLUGIN_PREFIX) && jarName.endsWith(".jar"));
    }

    private static Set<Path> listJars(Path pluginsDir) {
        try (Stream<Path> entries = Files.list(pluginsDir)) {
            Set<Path> jars = new LinkedHashSet<>();
            entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .map(p -> p.toAbsolutePath().normalize())
                    .forEach(jars::add);
            return jars;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not list plugins directory: " + pluginsDir.toAbsolutePath(), e);
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("could not hash analyzer plugin jar: " + file, e);
        }
    }
}
