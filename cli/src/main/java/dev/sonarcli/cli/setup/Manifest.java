package dev.sonarcli.cli.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sonarcli.protocol.Json;

/**
 * The pinned runtime manifest — the single source of truth for {@code setup}.
 *
 * <p>It names a {@link #version} (the {@code ~/.sonar/<version>/} directory
 * key), the analysis {@link #engine} coordinate, and the ten analyzer
 * {@link #plugins}. Every downloadable {@link Artifact} carries its pinned
 * SHA-256 so {@code setup} can verify each download. The daemon runs on the
 * system Java that launched the CLI — no JRE is provisioned.
 *
 * <p><b>Format.</b> The manifest is JSON, parsed by the shared protocol
 * {@link Json#mapper()}. JSON was chosen over TOML deliberately: the project
 * already depends on Jackson through the {@code protocol} module, so a JSON
 * manifest needs <em>zero</em> new dependencies, whereas a TOML manifest would
 * pull in a parser library purely for this one file.
 *
 * <p><b>Engine checksum.</b> {@code daemon/plugins/CHECKSUMS.txt} pins the ten
 * plugin jars; the analysis engine jar is not listed there. The bundled
 * manifest carries a placeholder engine SHA-256 (all zeros) until the real
 * published checksum is pinned. {@code setup} verifies whatever the manifest
 * declares; tests inject a manifest whose engine checksum matches the fake
 * artifact they serve.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Manifest(String version, Artifact engine, List<Artifact> plugins) {

    /** Classpath location of the bundled manifest. */
    public static final String RESOURCE = "/manifest.json";

    /** Canonicalises and defensively copies on construction. */
    public Manifest {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(engine, "engine");
        plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
    }

    /**
     * A single downloadable artifact: a Maven coordinate plus the pinned
     * SHA-256 of its jar.
     *
     * @param groupId    the Maven group id
     * @param artifactId the Maven artifact id
     * @param version    the pinned version
     * @param sha256     the lowercase hex SHA-256 of the jar
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artifact(String groupId, String artifactId, String version, String sha256) {
        public Artifact {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(sha256, "sha256");
        }

        /** The jar file name: {@code <artifactId>-<version>.jar}. */
        public String jarName() {
            return artifactId + "-" + version + ".jar";
        }

        /**
         * The Maven repository path of this jar relative to a repository base
         * URL: {@code <group-path>/<artifactId>/<version>/<jarName>}.
         */
        public String mavenPath() {
            return groupId.replace('.', '/') + "/" + artifactId + "/" + version
                    + "/" + jarName();
        }
    }

    /**
     * Loads the manifest bundled on the CLI classpath at {@link #RESOURCE}.
     *
     * @return the parsed bundled manifest
     * @throws IllegalStateException if the resource is missing
     * @throws UncheckedIOException  if the resource cannot be parsed
     */
    public static Manifest bundled() {
        try (InputStream in = Manifest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "bundled manifest not found on the classpath at " + RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the bundled manifest", e);
        }
    }

    /**
     * Parses a manifest from an arbitrary JSON stream — used by tests to inject
     * a manifest whose checksums match their fake artifacts.
     *
     * @param in the JSON stream
     * @return the parsed manifest
     * @throws IOException if the stream cannot be parsed as a manifest
     */
    public static Manifest load(InputStream in) throws IOException {
        ObjectMapper mapper = Json.mapper();
        return mapper.readValue(in, Manifest.class);
    }
}
