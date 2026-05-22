package dev.sonarcli.cli.setup;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Function;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code sonar setup} — one-time provisioning of the analyzer runtime.
 *
 * <p>Downloads the analyzer plugins and the analysis engine into
 * {@code ~/.sonar/<version>/} with SHA-256 verification of every artifact. The
 * daemon runs on the system Java that launched the CLI — no JRE is provisioned.
 * After {@code setup} the tool makes no network calls.
 *
 * <p><b>Options.</b> {@code --repo <url>} substitutes a Maven mirror for the
 * plugin/engine downloads; {@code --offline <archive>} provisions the whole
 * runtime from a local {@code .tar.gz} bundle with no network at all.
 *
 * <p><b>Exit codes.</b> {@code 0} on success; {@code 2} on any provisioning
 * failure — a checksum mismatch surfaces the artifact name in the message.
 *
 * <p><b>Testability.</b> The provisioning work lives in {@link SetupRunner};
 * a {@code Function<RunnerInputs, SetupRunner>} factory is the seam tests use
 * to inject a runner wired to stubbed sources.
 */
@Command(name = "setup", mixinStandardHelpOptions = true,
        description = "Provision the analyzer runtime into ~/.sonar (one-time).")
public final class SetupCommand implements Callable<Integer> {

    /** Exit code for a clean provisioning run. */
    public static final int EXIT_OK = 0;
    /** Exit code for a provisioning failure (checksum, download, archive). */
    public static final int EXIT_ERROR = 2;

    /** The inputs a {@link SetupRunner} factory needs to build a runner. */
    public record RunnerInputs(Manifest manifest, String repoBase, RuntimeLayout layout) {}

    @Option(names = "--repo", paramLabel = "URL",
            description = "Maven repository base URL for plugin/engine downloads.")
    private String repo = PluginProvisioner.MAVEN_CENTRAL;

    @Option(names = "--offline", paramLabel = "ARCHIVE",
            description = "Provision from a local .tar.gz runtime bundle (no network).")
    private String offlineArchive;

    @Spec
    private CommandSpec spec;

    private final Function<RunnerInputs, SetupRunner> runnerFactory;

    /** Production constructor: builds a network-backed {@link SetupRunner}. */
    public SetupCommand() {
        this(SetupCommand::productionRunner);
    }

    /**
     * Test constructor: injects a runner factory wired to stubbed sources.
     *
     * @param runnerFactory builds the {@link SetupRunner} from the parsed inputs
     */
    public SetupCommand(Function<RunnerInputs, SetupRunner> runnerFactory) {
        this.runnerFactory = runnerFactory;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        Manifest manifest = Manifest.bundled();
        RuntimeLayout layout = RuntimeLayout.forVersion(manifest.version());
        SetupRunner runner = runnerFactory.apply(new RunnerInputs(manifest, repo, layout));
        try {
            if (offlineArchive != null) {
                runner.provisionOffline(Path.of(offlineArchive), out);
            } else {
                runner.provision(out);
            }
            return EXIT_OK;
        } catch (DownloadException e) {
            err.println("setup failed: " + e.getMessage());
            err.flush();
            return EXIT_ERROR;
        }
    }

    /** Builds the production runner with a real checksum-verifying downloader. */
    private static SetupRunner productionRunner(RunnerInputs inputs) {
        Downloader downloader = new Downloader();
        return new SetupRunner(
                inputs.manifest(), inputs.repoBase(), inputs.layout(),
                new PluginProvisioner(downloader));
    }
}
