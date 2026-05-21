package dev.sonarcli.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

import dev.sonarcli.protocol.SocketPaths;
import dev.sonarcli.protocol.dto.AnalysisWarning;
import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.Issue;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * The {@code sonar} root command — a picocli command tree that drives the
 * analysis daemon and reports its findings.
 *
 * <p><b>Wiring.</b> The root owns the collaborators every subcommand shares: a
 * {@link DaemonRpc} (socket calls) and a {@link DaemonControl} (daemon process
 * lifecycle). The no-arg constructor builds the real, socket-backed pair; the
 * test constructor injects stubs. Subcommands reach the root via
 * {@code @ParentCommand} and route work through {@link #analyzeAndReport}.
 *
 * <p><b>Exit codes.</b> {@code 0} = no issues at or above the severity floor;
 * {@code 1} = issues found; {@code 2} = tool error (bad arguments, daemon
 * unreachable, a missing input file). Subcommands return the code; a thrown
 * {@link DaemonException} or {@link IllegalArgumentException} is mapped to
 * {@code 2} by the execution-exception handler installed in {@link #main}.
 */
@Command(
        name = "sonar",
        mixinStandardHelpOptions = true,
        version = "sonar " + SonarCommand.VERSION,
        description = "Run the sonar analysis quality gate.",
        exitCodeOnInvalidInput = SonarCommand.EXIT_TOOL_ERROR,
        exitCodeOnExecutionException = SonarCommand.EXIT_TOOL_ERROR,
        subcommands = {
                SonarCommand.VersionCommand.class,
                SonarCommand.CheckCommand.class,
                SonarCommand.AnalyzeCommand.class,
                SonarCommand.RulesCommand.class,
                SonarCommand.InstallHookCommand.class,
                SonarCommand.DaemonCommand.class,
                dev.sonarcli.cli.setup.SetupCommand.class
        })
public final class SonarCommand implements Runnable {

    /** Exit code for a clean run — no issues at or above the severity floor. */
    public static final int EXIT_CLEAN = 0;
    /** Exit code when issues were found. */
    public static final int EXIT_ISSUES = 1;
    /** Exit code for a tool error — bad input, daemon unreachable. */
    public static final int EXIT_TOOL_ERROR = 2;

    static final String VERSION = "0.1.0-SNAPSHOT";

    @Option(names = "--format",
            description = "Output format: sarif, json, or text. Default: sarif.")
    private OutputFormat format = OutputFormat.SARIF;

    @Option(names = "--severity",
            description = "Minimum severity to report: "
                    + "BLOCKER, CRITICAL, MAJOR, MINOR, INFO. Default: INFO.")
    private Severity severity = Severity.INFO;

    @Option(names = "--config", paramLabel = "PROFILE_XML",
            description = "A SonarQube quality-profile XML to drive rule selection.")
    private String configProfile;

    @Spec
    private CommandSpec spec;

    private final DaemonRpc rpc;
    private final DaemonControl control;
    private final FileResolver fileResolver = new FileResolver();

    /** Production constructor: real socket-backed collaborators. */
    public SonarCommand() {
        SocketPaths paths = SocketPaths.resolve();
        DaemonLauncher launcher = new DaemonLauncher(paths);
        this.rpc = new DaemonClient(paths, launcher);
        this.control = new LauncherDaemonControl(paths, launcher);
    }

    /** Test constructor: injected stubs, no live daemon required. */
    public SonarCommand(DaemonRpc rpc, DaemonControl control) {
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.control = Objects.requireNonNull(control, "control");
    }

    /** With no subcommand, print usage. */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** Output formats selectable via {@code --format}. */
    public enum OutputFormat {
        TEXT,
        JSON,
        SARIF
    }

    /**
     * Analyzes the resolved files and writes a report, returning the exit code.
     * Shared by {@code check} and {@code analyze}.
     */
    private int analyzeAndReport(FileResolver.ResolvedFiles resolved, PrintWriter out,
                                 CoverageOptions coverageOptions) {
        // Import coverage first: a coverage parse failure is a tool error
        // (exit 2) and should surface before the daemon is even contacted.
        dev.sonarcli.cli.coverage.CoverageReport coverage =
                coverageOptions.importReports();

        AnalyzeRequest request = new AnalyzeRequest(
                resolved.baseDir().toString(),
                resolved.relativePaths(),
                List.of(), resolveProfileRef(), List.of());
        AnalyzeResponse response = rpc.analyze(request);

        List<Issue> filtered = response.issues().stream()
                .filter(issue -> severity.accepts(issue.severity()))
                .toList();
        AnalyzeResponse reported = new AnalyzeResponse(filtered, response.warnings());

        RuleMetadataIndex index = new RuleMetadataIndex(rpc.ruleCatalog());
        Reporter reporter = switch (format) {
            case JSON -> new JsonReporter();
            case TEXT -> new TextReporter();
            case SARIF -> new SarifReporter();
        };
        out.print(reporter.render(reported, index, coverage));
        out.flush();

        boolean issuesFail = !filtered.isEmpty();
        boolean coverageFail = coverage != null && coverageOptions.min() != null
                && coverage.overallPercent() < coverageOptions.min();
        return issuesFail || coverageFail ? EXIT_ISSUES : EXIT_CLEAN;
    }

    /**
     * The coverage-import knobs a subcommand collects from {@code --coverage}
     * and {@code --coverage-min}. A picocli {@code @ArgGroup}-style mixin would
     * be heavier than warranted; the {@code check}/{@code analyze} commands each
     * declare the two options and hand them here.
     */
    static final class CoverageOptions {

        @Option(names = "--coverage", paramLabel = "REPORT", arity = "1",
                description = "A coverage report to import (repeatable). Supported: "
                        + "JaCoCo, Cobertura, LCOV, Go profile, Clover, SimpleCov.")
        private List<String> reports = new java.util.ArrayList<>();

        @Option(names = "--coverage-min", paramLabel = "PCT",
                description = "Fail the run (exit 1) when merged coverage is below "
                        + "this percentage.")
        private Double min;

        /** The {@code --coverage-min} threshold, or {@code null} if unset. */
        Double min() {
            return min;
        }

        /**
         * Imports and merges every {@code --coverage} report, or returns
         * {@code null} when none was requested.
         *
         * @throws dev.sonarcli.cli.coverage.CoverageException if a report is
         *         missing, unrecognized, or malformed (mapped to exit 2)
         */
        dev.sonarcli.cli.coverage.CoverageReport importReports() {
            if (reports == null || reports.isEmpty()) {
                return null;
            }
            List<java.nio.file.Path> paths = reports.stream()
                    .map(p -> java.nio.file.Path.of(p).toAbsolutePath().normalize())
                    .toList();
            return new dev.sonarcli.cli.coverage.CoverageImporter().importReports(paths);
        }
    }

    /**
     * Resolves {@code --config} to an absolute profile path the daemon can
     * open, or {@code null} when no profile was requested.
     *
     * @throws IllegalArgumentException if the named profile file does not exist
     */
    private String resolveProfileRef() {
        if (configProfile == null) {
            return null;
        }
        java.nio.file.Path profile =
                java.nio.file.Path.of(configProfile).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(profile)) {
            throw new IllegalArgumentException(
                    "quality-profile file not found: " + profile);
        }
        return profile.toString();
    }

    /** {@code sonar version} — print the CLI version. */
    @Command(name = "version", description = "Print the sonar CLI version.")
    static final class VersionCommand implements Runnable {
        @CommandLine.ParentCommand
        private SonarCommand parent;

        @Override
        public void run() {
            parent.spec.commandLine().getOut().println("sonar " + VERSION);
        }
    }

    /** {@code sonar check} — analyze explicit files, or files changed in git. */
    @Command(name = "check",
            description = "Analyze specific files (or files changed in git via --diff).")
    static final class CheckCommand implements java.util.concurrent.Callable<Integer> {
        @CommandLine.ParentCommand
        private SonarCommand parent;

        @CommandLine.Parameters(paramLabel = "FILE", arity = "0..*",
                description = "Files to analyze.")
        private List<String> files = List.of();

        @Option(names = "--diff", arity = "0..1", paramLabel = "REF", fallbackValue = "HEAD",
                description = "Analyze files changed against a git ref (default HEAD).")
        private String diffRef;

        @Option(names = "--project", paramLabel = "DIR",
                description = "Project directory for --diff. Default: current directory.")
        private String projectDir = ".";

        @CommandLine.Mixin
        private CoverageOptions coverage = new CoverageOptions();

        @Override
        public Integer call() {
            boolean useDiff = diffRef != null;
            if (!useDiff && files.isEmpty()) {
                throw new IllegalArgumentException(
                        "check needs FILE arguments, or --diff to use git-changed files");
            }
            FileResolver.ResolvedFiles resolved = useDiff
                    ? parent.fileResolver.resolveDiff(
                            java.nio.file.Path.of(projectDir), diffRef)
                    : parent.fileResolver.resolveExplicit(files);
            return parent.analyzeAndReport(
                    resolved, parent.spec.commandLine().getOut(), coverage);
        }
    }

    /** {@code sonar analyze} — walk a project directory and analyze its sources. */
    @Command(name = "analyze",
            description = "Walk a project directory and analyze every source file.")
    static final class AnalyzeCommand implements java.util.concurrent.Callable<Integer> {
        @CommandLine.ParentCommand
        private SonarCommand parent;

        @CommandLine.Parameters(paramLabel = "DIR", arity = "0..1",
                description = "Project directory. Default: current directory.")
        private String projectDir = ".";

        @CommandLine.Mixin
        private CoverageOptions coverage = new CoverageOptions();

        @Override
        public Integer call() {
            FileResolver.ResolvedFiles resolved =
                    parent.fileResolver.resolveProject(java.nio.file.Path.of(projectDir));
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException(
                        "no analyzable source files under " + projectDir);
            }
            return parent.analyzeAndReport(
                    resolved, parent.spec.commandLine().getOut(), coverage);
        }
    }

    /** {@code sonar rules} — inspect the analyzer rule catalog. */
    @Command(name = "rules", description = "Inspect the analyzer rule catalog.",
            subcommands = {
                    RulesCommand.ListCommand.class,
                    RulesCommand.ShowCommand.class
            })
    static final class RulesCommand implements Runnable {
        @CommandLine.ParentCommand
        private SonarCommand parent;

        @Override
        public void run() {
            parent.spec.commandLine().usage(parent.spec.commandLine().getOut());
        }

        /** {@code sonar rules list} — list every known rule key and name. */
        @Command(name = "list", description = "List every known rule key and name.")
        static final class ListCommand implements java.util.concurrent.Callable<Integer> {
            @CommandLine.ParentCommand
            private RulesCommand rules;

            @Override
            public Integer call() {
                SonarCommand parent = rules.parent;
                PrintWriter out = parent.spec.commandLine().getOut();
                List<dev.sonarcli.protocol.dto.RuleMetadata> catalog = parent.rpc.ruleCatalog();
                List<dev.sonarcli.protocol.dto.RuleMetadata> sorted = catalog.stream()
                        .sorted(java.util.Comparator.comparing(
                                dev.sonarcli.protocol.dto.RuleMetadata::ruleKey))
                        .toList();
                for (dev.sonarcli.protocol.dto.RuleMetadata rule : sorted) {
                    out.println(rule.ruleKey() + "  " + rule.name());
                }
                out.println();
                out.println(sorted.size() + (sorted.size() == 1 ? " rule." : " rules."));
                out.flush();
                return EXIT_CLEAN;
            }
        }

        /** {@code sonar rules show <ruleKey>} — print one rule's full metadata. */
        @Command(name = "show", description = "Print the full metadata for one rule.")
        static final class ShowCommand implements java.util.concurrent.Callable<Integer> {
            @CommandLine.ParentCommand
            private RulesCommand rules;

            @CommandLine.Parameters(paramLabel = "RULE_KEY", arity = "1",
                    description = "The rule key, e.g. java:S1118.")
            private String ruleKey;

            @Override
            public Integer call() {
                SonarCommand parent = rules.parent;
                PrintWriter out = parent.spec.commandLine().getOut();
                // An unknown key throws DaemonException -> mapped to exit 2.
                dev.sonarcli.protocol.dto.RuleMetadata rule = parent.rpc.ruleMetadata(ruleKey);
                out.println("rule:        " + rule.ruleKey());
                out.println("name:        " + rule.name());
                out.println("language:    " + rule.language());
                out.println("severity:    " + rule.severity());
                out.println("type:        " + rule.type());
                out.println();
                out.println("description:");
                out.println(plainText(rule.descriptionHtml()));
                out.println();
                out.println("how to fix:");
                out.println(rule.howToFix() != null && !rule.howToFix().isBlank()
                        ? rule.howToFix()
                        : "  (no dedicated fix guidance; see the description)");
                out.flush();
                return EXIT_CLEAN;
            }

            /** Strips HTML tags from a rule description for terminal display. */
            private static String plainText(String html) {
                if (html == null || html.isBlank()) {
                    return "  (no description)";
                }
                return html
                        .replaceAll("(?i)<br\\s*/?>", "\n")
                        .replaceAll("(?i)</p>", "\n")
                        .replaceAll("<[^>]+>", "")
                        .replaceAll("[ \\t]+", " ")
                        .strip();
            }
        }
    }

    /**
     * {@code sonar install-hook} — install a git {@code pre-push} hook that runs
     * {@code sonar check --diff}. Refuses outside a git repository, and never
     * silently clobbers a foreign pre-push hook: an existing hook that this
     * command did not write is backed up first.
     */
    @Command(name = "install-hook",
            description = "Install a git pre-push hook that runs sonar check --diff.")
    static final class InstallHookCommand implements java.util.concurrent.Callable<Integer> {

        /** Marker line identifying a hook this command generated. */
        private static final String MARKER = "# sonar-predictor pre-push hook";

        private static final String HOOK_BODY = "#!/bin/sh\n"
                + MARKER + "\n"
                + "# Installed by `sonar install-hook`. Runs the quality gate on\n"
                + "# files changed against the push target before allowing the push.\n"
                + "exec sonar check --diff\n";

        @CommandLine.ParentCommand
        private SonarCommand parent;

        @Option(names = "--project", paramLabel = "DIR",
                description = "The git repository. Default: current directory.")
        private String projectDir = ".";

        @Override
        public Integer call() throws java.io.IOException {
            PrintWriter out = parent.spec.commandLine().getOut();
            java.nio.file.Path repo = java.nio.file.Path.of(projectDir)
                    .toAbsolutePath().normalize();
            java.nio.file.Path gitDir = repo.resolve(".git");
            if (!java.nio.file.Files.isDirectory(gitDir)) {
                throw new IllegalArgumentException(
                        "not a git repository: " + repo + " (no .git directory)");
            }

            java.nio.file.Path hooksDir = gitDir.resolve("hooks");
            java.nio.file.Files.createDirectories(hooksDir);
            java.nio.file.Path hook = hooksDir.resolve("pre-push");

            if (java.nio.file.Files.exists(hook)) {
                String existing = java.nio.file.Files.readString(hook);
                if (!existing.contains(MARKER)) {
                    // A foreign hook — back it up rather than overwrite it silently.
                    java.nio.file.Path backup = hooksDir.resolve(
                            "pre-push.bak-" + System.currentTimeMillis());
                    java.nio.file.Files.move(hook, backup);
                    out.println("backed up the existing pre-push hook to "
                            + backup.getFileName());
                }
            }

            java.nio.file.Files.writeString(hook, HOOK_BODY);
            makeExecutable(hook);
            out.println("installed pre-push hook at " + hook);
            out.flush();
            return EXIT_CLEAN;
        }

        /** Adds owner/group/other execute permission, where the FS supports it. */
        private static void makeExecutable(java.nio.file.Path file) throws java.io.IOException {
            try {
                var perms = new java.util.HashSet<>(
                        java.nio.file.Files.getPosixFilePermissions(file));
                perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
                perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
                java.nio.file.Files.setPosixFilePermissions(file, perms);
            } catch (UnsupportedOperationException notPosix) {
                // Non-POSIX filesystem — fall back to the File API.
                if (!file.toFile().setExecutable(true)) {
                    throw new java.io.IOException(
                            "could not make the hook executable: " + file);
                }
            }
        }
    }

    /** {@code sonar daemon} — manage the analysis daemon process. */
    @Command(name = "daemon", description = "Manage the analysis daemon.",
            subcommands = {
                    DaemonCommand.StartCommand.class,
                    DaemonCommand.StopCommand.class,
                    DaemonCommand.StatusCommand.class
            })
    static final class DaemonCommand implements Runnable {
        @CommandLine.ParentCommand
        private SonarCommand parent;

        @Override
        public void run() {
            parent.spec.commandLine().usage(parent.spec.commandLine().getOut());
        }

        @Command(name = "start", description = "Start the analysis daemon.")
        static final class StartCommand implements Runnable {
            @CommandLine.ParentCommand
            private DaemonCommand daemon;

            @Override
            public void run() {
                SonarCommand parent = daemon.parent;
                parent.control.start();
                parent.spec.commandLine().getOut().println("daemon started");
            }
        }

        @Command(name = "stop", description = "Stop the analysis daemon.")
        static final class StopCommand implements java.util.concurrent.Callable<Integer> {
            @CommandLine.ParentCommand
            private DaemonCommand daemon;

            @Override
            public Integer call() {
                SonarCommand parent = daemon.parent;
                boolean stopped = parent.control.stop();
                if (stopped) {
                    parent.spec.commandLine().getOut().println("daemon stopped");
                    return 0;
                }
                parent.spec.commandLine().getErr().println(
                        "daemon stop failed: the daemon did not stop within the "
                                + "stop deadline");
                return EXIT_TOOL_ERROR;
            }
        }

        @Command(name = "status", description = "Report whether the daemon is running.")
        static final class StatusCommand implements Runnable {
            @CommandLine.ParentCommand
            private DaemonCommand daemon;

            @Override
            public void run() {
                SonarCommand parent = daemon.parent;
                boolean running = parent.control.isRunning();
                parent.spec.commandLine().getOut()
                        .println("daemon is " + (running ? "running" : "not running"));
            }
        }
    }

    /**
     * Applies the CLI's shared execution policy: a tool error
     * ({@link DaemonException} or an {@link IllegalArgumentException} from input
     * resolution) and bad arguments both map to exit code {@link #EXIT_TOOL_ERROR}.
     *
     * @param cmd the command line to configure
     * @return the same {@code cmd}, for chaining
     */
    public static CommandLine configure(CommandLine cmd) {
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            commandLine.getErr().println("error: " + ex.getMessage());
            return EXIT_TOOL_ERROR;
        });
        // Accept --format json / --severity major as well as the uppercase forms.
        cmd.setCaseInsensitiveEnumValuesAllowed(true);
        return cmd;
    }

    /**
     * Process entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        CommandLine cmd = configure(new CommandLine(new SonarCommand()));
        System.exit(cmd.execute(args));
    }
}
