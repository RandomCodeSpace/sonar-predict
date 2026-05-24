package io.github.randomcodespace.sonarpredict.cli.setup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Provisions the analysis engine jar and every analyzer plugin jar into a
 * {@link RuntimeLayout}'s plugins directory, verifying each download against
 * the manifest's pinned SHA-256.
 *
 * <p><b>Maven layout.</b> Each artifact is fetched from
 * {@code <base>/<group-path>/<artifactId>/<version>/<artifactId>-<version>.jar}
 * — the standard Maven repository layout. The {@code base} is Maven Central by
 * default; {@code setup --repo} substitutes a mirror.
 *
 * <p><b>Idempotence and re-verification.</b> A pre-existing jar is kept only
 * when its content still hashes to the manifest's pinned SHA-256, so
 * re-running {@code setup} on an intact layout makes no network calls. A jar
 * whose content does <em>not</em> match — a corrupted, truncated, or tampered
 * artifact — is deleted and re-fetched; it is never silently trusted.
 */
public final class PluginProvisioner {

    /** The default Maven repository base URL. */
    public static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2";

    private final Downloader downloader;

    /** @param downloader the checksum-verifying downloader to fetch jars with */
    public PluginProvisioner(Downloader downloader) {
        this.downloader = Objects.requireNonNull(downloader, "downloader");
    }

    /**
     * Downloads the engine jar and every plugin jar named in {@code manifest}
     * into {@code layout.pluginsDir()}, verifying each checksum.
     *
     * @param manifest the pinned runtime manifest
     * @param repoBase the Maven repository base URL (no trailing slash)
     * @param layout   the target runtime layout
     * @throws DownloadException if any download or checksum verification fails
     */
    public void provision(Manifest manifest, String repoBase, RuntimeLayout layout) {
        String base = stripTrailingSlash(repoBase);

        // The engine jar is stored under the layout's fixed engine name so the
        // daemon can resolve it without knowing the pinned version.
        fetchIfAbsent(base, manifest.engine(), layout.engineJar());

        for (Manifest.Artifact plugin : manifest.plugins()) {
            Path target = layout.pluginsDir().resolve(plugin.jarName());
            fetchIfAbsent(base, plugin, target);
        }
    }

    private void fetchIfAbsent(String base, Manifest.Artifact artifact, Path target) {
        if (Files.isRegularFile(target) && jarMatches(target, artifact.sha256())) {
            // Present AND its content still matches the pinned checksum — skip.
            return;
        }
        if (Files.isRegularFile(target)) {
            // A present but checksum-mismatching jar (corrupt/tampered/stale):
            // delete it and re-fetch rather than trust it.
            deleteQuietly(target);
        }
        URI source = URI.create(base + "/" + artifact.mavenPath());
        downloader.fetch(source, target, artifact.sha256());
    }

    /**
     * Whether {@code jar}'s content hashes to {@code expectedSha256}
     * (case-insensitive hex). An unreadable file or any mismatch is
     * {@code false}, so the caller re-fetches.
     */
    private static boolean jarMatches(Path jar, String expectedSha256) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(jar)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest())
                    .equalsIgnoreCase(expectedSha256);
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort — the fetch below overwrites a leftover anyway.
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
