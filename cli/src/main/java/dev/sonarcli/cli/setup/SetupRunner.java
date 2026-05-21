package dev.sonarcli.cli.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Orchestrates {@code sonar setup}: provisions the analyzer plugins and the
 * analysis engine into a {@link RuntimeLayout}, with SHA-256 verification at
 * every step.
 *
 * <p>The daemon runs on the system Java that launched the CLI — no JRE is
 * downloaded, verified, or bundled.
 *
 * <p>This class holds the whole provisioning policy and is directly unit
 * testable — every collaborator (manifest, repo base URL, layout, the plugin
 * provisioner) is injected. {@code SetupCommand} is the thin picocli shell
 * that builds the production wiring and maps the outcome to an exit code.
 *
 * <p><b>Online ({@link #provision}).</b> {@link PluginProvisioner} fetches the
 * engine + plugins from the Maven repository base.
 *
 * <p><b>Offline ({@link #provisionOffline}).</b> No network at all: a single
 * {@code .tar.gz} bundle whose contents mirror the provisioned layout
 * ({@code plugins/} with every jar and the engine) is extracted straight into
 * the version directory.
 */
public final class SetupRunner {

    private final Manifest manifest;
    private final String repoBase;
    private final RuntimeLayout layout;
    private final PluginProvisioner pluginProvisioner;

    /**
     * @param manifest          the pinned runtime manifest
     * @param repoBase          the Maven repository base URL
     * @param layout            the target runtime layout
     * @param pluginProvisioner provisions the engine + plugin jars
     */
    public SetupRunner(Manifest manifest, String repoBase, RuntimeLayout layout,
            PluginProvisioner pluginProvisioner) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.repoBase = Objects.requireNonNull(repoBase, "repoBase");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.pluginProvisioner = Objects.requireNonNull(pluginProvisioner, "pluginProvisioner");
    }

    /** The runtime layout this runner provisions into. */
    public RuntimeLayout layout() {
        return layout;
    }

    /**
     * Provisions the runtime from the network: plugins + engine from the Maven
     * repository. Idempotent — an already-complete layout is left untouched.
     *
     * @param out the writer success/progress lines are reported to
     */
    public void provision(PrintWriter out) {
        out.println("provisioning runtime " + manifest.version()
                + " into " + layout.versionDir());

        out.println("  plugins + engine ...");
        pluginProvisioner.provision(manifest, repoBase, layout);

        out.println("runtime ready: " + layout.versionDir());
        out.flush();
    }

    /**
     * Provisions the runtime from a local {@code .tar.gz} bundle — no network.
     * The bundle's contents are extracted directly into the version directory
     * and must mirror the provisioned layout ({@code plugins/}).
     *
     * @param bundle the local archive bundling the whole runtime
     * @param out    the writer success lines are reported to
     * @throws DownloadException if the bundle is missing or does not yield a
     *                           complete runtime
     */
    public void provisionOffline(Path bundle, PrintWriter out) {
        if (!Files.isRegularFile(bundle)) {
            throw new DownloadException("offline archive not found: " + bundle);
        }
        out.println("provisioning runtime " + manifest.version()
                + " from offline archive " + bundle);
        try (InputStream in = Files.newInputStream(bundle)) {
            Tar.extractTarGz(in, layout.versionDir(), false);
        } catch (IOException e) {
            throw new DownloadException(
                    "could not extract the offline archive: " + e.getMessage(), e);
        }
        if (!layout.isVerified(manifest)) {
            throw new DownloadException(
                    "the offline archive did not yield a complete, verified runtime at "
                            + layout.versionDir());
        }
        out.println("runtime ready: " + layout.versionDir());
        out.flush();
    }
}
