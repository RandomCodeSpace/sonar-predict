package io.github.randomcodespace.sonarpredict.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.randomcodespace.sonarpredict.protocol.SocketPaths;

import picocli.CommandLine;

/**
 * End-to-end: the real picocli command tree, the real {@link DaemonClient} and
 * {@link LauncherDaemonControl}, and a real daemon JVM spawned by
 * {@link DaemonLauncher} — only the {@link SocketPaths} are pointed at an
 * isolated temp directory so the test never collides with a developer's daemon.
 *
 * <p>Deterministic: the daemon auto-starts and the launcher returns only once
 * the socket is accepting; teardown stops the daemon and waits for the socket
 * file to vanish — no sleeps.
 */
class CliIntegrationTest {

    private static final Path FIXTURES =
            Path.of("src/test/resources/cli/fixtures").toAbsolutePath();

    private SocketPaths paths;
    private DaemonLauncher launcher;

    private record Run(int exitCode, String out, String err) {
    }

    /** Builds the real CLI wired to a daemon isolated under {@code runtimeDir}. */
    private Run run(Path runtimeDir, String... args) {
        paths = SocketPaths.resolve(Map.of("XDG_RUNTIME_DIR", runtimeDir.toString()));
        launcher = new DaemonLauncher(paths, Duration.ofSeconds(90));
        DaemonClient client = new DaemonClient(paths, launcher);
        LauncherDaemonControl control = new LauncherDaemonControl(paths, launcher);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = SonarCommand.configure(
                        new CommandLine(new SonarCommand(client, control)))
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        int code = cmd.execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    @AfterEach
    void stopDaemon() throws Exception {
        if (paths != null && launcher != null && launcher.isDaemonRunning()) {
            new LauncherDaemonControl(paths, launcher).stop();
        }
    }

    @Test
    @DisplayName("version exits 0 and prints the CLI version")
    void versionExitsZero(@TempDir Path runtimeDir) {
        Run run = run(runtimeDir, "version");

        assertEquals(0, run.exitCode(), "version must exit 0");
        assertTrue(run.out().contains("sonar"), "version output must name the tool");
    }

    @Test
    @DisplayName("check on a buggy file auto-starts the daemon, prints java:S1118, exits 1")
    void checkBuggyFileExitsOne(@TempDir Path runtimeDir) {
        Path buggy = FIXTURES.resolve("java/UtilityClass.java");

        Run run = run(runtimeDir, "check", buggy.toString());

        assertEquals(1, run.exitCode(), "a file with issues must exit 1, err: " + run.err());
        assertTrue(run.out().contains("java:S1118"),
                "the analysis must raise java:S1118, got: " + run.out());
    }

    @Test
    @DisplayName("check --format text on a clean file exits 0 and says so")
    void checkCleanFileExitsZero(@TempDir Path runtimeDir) {
        Path clean = FIXTURES.resolve("java/Clean.java");

        Run run = run(runtimeDir, "--format", "text", "check", clean.toString());

        assertEquals(0, run.exitCode(), "a clean file must exit 0, err: " + run.err());
        assertTrue(run.out().toLowerCase().contains("no issues"),
                "clean output must say so, got: " + run.out());
    }

    @Test
    @DisplayName("check with --format json emits compact, parseable JSON")
    void checkJsonOutput(@TempDir Path runtimeDir) throws Exception {
        Path buggy = FIXTURES.resolve("java/UtilityClass.java");

        Run run = run(runtimeDir, "--format", "json", "check", buggy.toString());

        assertEquals(1, run.exitCode());
        assertEquals(1, run.out().strip().lines().count(),
                "JSON output must be a single compact line");
        assertTrue(io.github.randomcodespace.sonarpredict.protocol.Json.mapper()
                        .readTree(run.out()).get("issueCount").asInt() >= 1,
                "JSON must report at least one issue");
    }

    @Test
    @DisplayName("a tool error (missing input file) exits 2")
    void missingFileExitsTwo(@TempDir Path runtimeDir) {
        Run run = run(runtimeDir, "check", "/no/such/Ghost.java");

        assertEquals(2, run.exitCode(), "a missing input file must exit 2");
        assertFalse(run.err().isBlank(), "a tool error must explain itself on stderr");
    }

    @Test
    @DisplayName("daemon status reflects the running and stopped states")
    void daemonStatusReflectsState(@TempDir Path runtimeDir) {
        Run stopped = run(runtimeDir, "daemon", "status");
        assertEquals(0, stopped.exitCode());
        assertTrue(stopped.out().toLowerCase().contains("not running"),
                "status must report a stopped daemon before any start");

        Run started = run(runtimeDir, "daemon", "start");
        assertEquals(0, started.exitCode(), "daemon start must succeed");

        Run running = run(runtimeDir, "daemon", "status");
        assertEquals(0, running.exitCode());
        assertTrue(running.out().toLowerCase().contains("running")
                        && !running.out().toLowerCase().contains("not running"),
                "status must report a running daemon after start, got: " + running.out());
    }

    @Test
    @DisplayName("check emits valid SARIF 2.1.0 by default and parses as JSON")
    void checkDefaultsToSarif(@TempDir Path runtimeDir) throws Exception {
        Path buggy = FIXTURES.resolve("java/UtilityClass.java");

        Run run = run(runtimeDir, "check", buggy.toString());

        assertEquals(1, run.exitCode(), "a file with issues must exit 1, err: " + run.err());
        var sarif = io.github.randomcodespace.sonarpredict.protocol.Json.mapper().readTree(run.out());
        assertEquals("2.1.0", sarif.get("version").asText(),
                "the default output must be SARIF 2.1.0, got:\n" + run.out());
        assertEquals("sonar-predictor",
                sarif.get("runs").get(0).get("tool").get("driver").get("name").asText(),
                "the SARIF driver must be sonar-predictor");
        assertTrue(sarif.get("runs").get(0).get("results").size() >= 1,
                "the SARIF run must carry at least one result");
    }

    @Test
    @DisplayName("check --config drives analysis with the imported quality profile")
    void checkWithConfigProfile(@TempDir Path runtimeDir) throws Exception {
        Path profile = FIXTURES.resolve("profiles/only-s1118.xml");
        Path buggy = FIXTURES.resolve("java/UtilityClass.java");

        Run run = run(runtimeDir, "--format", "json",
                "--config", profile.toString(), "check", buggy.toString());

        assertEquals(1, run.exitCode(),
                "the profiled rule still fires, so exit 1, err: " + run.err());
        // The profile activates java:S1118 — it must be reported.
        assertTrue(run.out().contains("java:S1118"),
                "the profiled rule java:S1118 must fire, got: " + run.out());
    }

    @Test
    @DisplayName("check --config with a missing profile file exits 2")
    void checkWithMissingConfigExitsTwo(@TempDir Path runtimeDir) {
        Path buggy = FIXTURES.resolve("java/UtilityClass.java");

        Run run = run(runtimeDir, "--config", "/no/such/profile.xml",
                "check", buggy.toString());

        assertEquals(2, run.exitCode(), "a missing profile must exit 2");
        assertFalse(run.err().isBlank(), "the error must be explained on stderr");
    }

    @Test
    @DisplayName("rules show returns real rule metadata end-to-end")
    void rulesShowEndToEnd(@TempDir Path runtimeDir) {
        Run run = run(runtimeDir, "rules", "show", "java:S1118");

        assertEquals(0, run.exitCode(), "rules show must exit 0, err: " + run.err());
        assertTrue(run.out().contains("java:S1118"), "the rule key must appear");
        assertTrue(run.out().toLowerCase().contains("constructor"),
                "the real S1118 metadata must mention a constructor, got:\n" + run.out());
    }

    @Test
    @DisplayName("rules list returns the real rule catalog end-to-end")
    void rulesListEndToEnd(@TempDir Path runtimeDir) {
        Run run = run(runtimeDir, "rules", "list");

        assertEquals(0, run.exitCode(), "rules list must exit 0, err: " + run.err());
        assertTrue(run.out().contains("java:S1118"),
                "the real catalog must list java:S1118, got:\n" + run.out());
    }

    @Test
    @DisplayName("check --coverage imports a report and shows the summary end-to-end")
    void checkWithCoverageSummary(@TempDir Path runtimeDir) {
        Path clean = FIXTURES.resolve("java/Clean.java");
        Path lcov = FIXTURES.resolve("coverage/lcov.info");

        Run run = run(runtimeDir, "--format", "text",
                "check", "--coverage", lcov.toString(), clean.toString());

        assertEquals(0, run.exitCode(),
                "a clean file with coverage and no threshold exits 0, err: " + run.err());
        assertTrue(run.out().toLowerCase().contains("coverage"),
                "the coverage summary must appear, got: " + run.out());
    }

    @Test
    @DisplayName("check --coverage-min below merged coverage fails the run (exit 1)")
    void checkCoverageMinBelowThresholdFails(@TempDir Path runtimeDir) {
        Path clean = FIXTURES.resolve("java/Clean.java");
        Path lcov = FIXTURES.resolve("coverage/lcov.info"); // ~66.67%

        Run run = run(runtimeDir, "--format", "text",
                "check", "--coverage", lcov.toString(),
                "--coverage-min", "95", clean.toString());

        assertEquals(1, run.exitCode(),
                "coverage below the threshold must fail the run, err: " + run.err());
    }

    @Test
    @DisplayName("check --coverage carries coverage in SARIF run-level properties")
    void checkCoverageInSarif(@TempDir Path runtimeDir) throws Exception {
        Path clean = FIXTURES.resolve("java/Clean.java");
        Path lcov = FIXTURES.resolve("coverage/lcov.info");

        Run run = run(runtimeDir, "check", "--coverage", lcov.toString(), clean.toString());

        assertEquals(0, run.exitCode(), "err: " + run.err());
        var coverage = io.github.randomcodespace.sonarpredict.protocol.Json.mapper().readTree(run.out())
                .get("runs").get(0).path("properties").path("coverage");
        assertFalse(coverage.isMissingNode(),
                "SARIF must carry run.properties.coverage, got:\n" + run.out());
        assertTrue(coverage.get("overallPercent").asDouble() > 0,
                "the SARIF coverage properties must report a percentage");
    }

    @Test
    @DisplayName("the fixtures the integration test relies on exist")
    void fixturesExist() {
        assertTrue(Files.isRegularFile(FIXTURES.resolve("java/UtilityClass.java")),
                "the buggy fixture must exist");
        assertTrue(Files.isRegularFile(FIXTURES.resolve("java/Clean.java")),
                "the clean fixture must exist");
        assertTrue(Files.isRegularFile(FIXTURES.resolve("profiles/only-s1118.xml")),
                "the quality-profile fixture must exist");
        assertTrue(Files.isRegularFile(FIXTURES.resolve("coverage/lcov.info")),
                "the coverage fixture must exist");
    }
}
