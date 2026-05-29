package io.github.randomcodespace.sonarpredict.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeRequest;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.Issue;
import io.github.randomcodespace.sonarpredict.protocol.dto.PingResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.RuleMetadata;

import picocli.CommandLine;

/**
 * Exercises the picocli command tree against a stub {@link DaemonRpc} and a
 * stub {@link DaemonControl}, so no live daemon is needed. Verifies parsing,
 * output format selection, severity filtering, and the 0/1/2 exit codes.
 */
class CommandTest {

    /** A scriptable stub daemon: the analyze result is whatever is set. */
    private static final class StubRpc implements DaemonRpc {
        private AnalyzeResponse analyzeResult = new AnalyzeResponse(List.of(), List.of());
        private RuntimeException analyzeFailure;
        private final List<AnalyzeRequest> requests = new ArrayList<>();

        @Override
        public PingResponse ping() {
            return new PingResponse("0.1.0-test", 1234L, List.of("java"));
        }

        @Override
        public AnalyzeResponse analyze(AnalyzeRequest request) {
            requests.add(request);
            if (analyzeFailure != null) {
                throw analyzeFailure;
            }
            return analyzeResult;
        }

        @Override
        public RuleMetadata ruleMetadata(String ruleKey) {
            return new RuleMetadata(ruleKey, "n", "java", "MAJOR", "BUG", "<p>d</p>", null);
        }

        @Override
        public List<RuleMetadata> ruleCatalog() {
            return List.of();
        }

        @Override
        public void shutdown() {
            // Intentionally empty: these tests exercise ping/analyze/rule lookups;
            // the CLI never invokes shutdown on the RPC stub, so a no-op is the
            // intended behaviour and there is nothing to assert here.
        }
    }

    /** A scriptable stub daemon-process control. */
    private static final class StubControl implements DaemonControl {
        private boolean running;
        private int startCalls;
        private int stopCalls;
        /** Outcome stop() reports — true (confirmed stopped) by default. */
        private boolean stopSucceeds = true;

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void start() {
            startCalls++;
            running = true;
        }

        @Override
        public boolean stop() {
            stopCalls++;
            if (stopSucceeds) {
                running = false;
            }
            return stopSucceeds;
        }
    }

    private record Run(int exitCode, String out, String err) {
    }

    private static Run run(StubRpc rpc, StubControl control, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        SonarCommand root = new SonarCommand(rpc, control);
        CommandLine cmd = SonarCommand.configure(new CommandLine(root))
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        int code = cmd.execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    private static StubRpc rpc() {
        return new StubRpc();
    }

    private static StubControl control() {
        return new StubControl();
    }

    /** Builds an Issue at line 1 for a file with a given rule key and severity. */
    private static Issue issue(String file, String ruleKey, String severity) {
        return new Issue(ruleKey, file, 1, 0, 1, 5, severity, "CODE_SMELL", "a finding");
    }

    @Test
    @DisplayName("version prints the runtime CLI version and exits 0")
    void versionExitsZero() {
        Run run = run(rpc(), control(), "version");

        assertEquals(0, run.exitCode(), "version must exit 0");
        assertFalse(run.out().isBlank(), "version must print something");
        assertTrue(run.out().contains(SonarVersionProvider.version()),
                "version must report the runtime build version, got: " + run.out());
        assertFalse(run.out().contains("0.1.0"),
                "version must not report the stale 0.1.0 literal, got: " + run.out());
    }

    @Test
    @DisplayName("--version reports the runtime CLI version (not the stale literal)")
    void versionFlagReportsRuntimeVersion() {
        Run run = run(rpc(), control(), "--version");

        assertEquals(0, run.exitCode(), "--version must exit 0");
        assertTrue(run.out().contains(SonarVersionProvider.version()),
                "--version must report the runtime build version, got: " + run.out());
        assertFalse(run.out().contains("0.1.0"),
                "--version must not report the stale 0.1.0 literal, got: " + run.out());
    }

    @Test
    @DisplayName("check on a clean file exits 0")
    void checkCleanFileExitsZero(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");
        StubRpc rpc = rpc();
        rpc.analyzeResult = new AnalyzeResponse(List.of(), List.of());

        Run run = run(rpc, control(), "--format", "text", "check", file.toString());

        assertEquals(0, run.exitCode(), "a clean file must exit 0");
        assertTrue(run.out().toLowerCase().contains("no issues"),
                "clean text output must say so, got: " + run.out());
    }

    @Test
    @DisplayName("check on a file with issues exits 1 and prints them")
    void checkWithIssuesExitsOne(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        StubRpc rpc = rpc();
        rpc.analyzeResult = new AnalyzeResponse(
                List.of(issue("Bad.java", "java:S1118", "MAJOR")), List.of());

        Run run = run(rpc, control(), "check", file.toString());

        assertEquals(1, run.exitCode(), "issues found must exit 1");
        assertTrue(run.out().contains("java:S1118"), "the issue must be printed");
    }

    @Test
    @DisplayName("check with --format json emits compact JSON")
    void checkJsonFormat(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        StubRpc rpc = rpc();
        rpc.analyzeResult = new AnalyzeResponse(
                List.of(issue("Bad.java", "java:S1118", "MAJOR")), List.of());

        Run run = run(rpc, control(), "--format", "json", "check", file.toString());

        assertEquals(1, run.exitCode());
        assertEquals(1, run.out().strip().lines().count(),
                "JSON output must be a single compact line");
        assertTrue(run.out().contains("\"issueCount\":1"), "JSON must report the issue count");
    }

    @Test
    @DisplayName("--save writes the report to PATH and emits a compact summary on stdout")
    void saveWritesReportAndPrintsSummary(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        Path target = dir.resolve(".sonar-predictor").resolve("scan.json");
        StubRpc rpc = rpc();
        rpc.analyzeResult = new AnalyzeResponse(
                List.of(
                        issue("Bad.java", "java:S1118", "CRITICAL"),
                        issue("Bad.java", "java:S100", "MAJOR")),
                List.of());

        Run run = run(rpc, control(),
                "--format", "json",
                "--save", target.toString(),
                "check", source.toString());

        assertEquals(1, run.exitCode(), "issues found must exit 1 regardless of --save");

        // The JSON report goes to the file, not stdout.
        assertTrue(Files.exists(target), "--save target file must be created");
        String written = Files.readString(target);
        assertTrue(written.contains("\"issueCount\":2"),
                "saved JSON must hold the full report, got: " + written.substring(0, Math.min(200, written.length())));
        assertTrue(written.contains("java:S1118"));

        // Stdout carries the compact native summary instead of the JSON.
        String stdout = run.out();
        assertFalse(stdout.contains("\"issueCount\""),
                "stdout must not contain raw JSON when --save is used, got: " + stdout);
        assertTrue(stdout.contains("sonar-predictor: 2 issues written to"),
                "stdout must announce the count and target, got: " + stdout);
        assertTrue(stdout.contains("severity:") && stdout.contains("CRITICAL=1") && stdout.contains("MAJOR=1"),
                "stdout must include the severity rollup, got: " + stdout);
        assertTrue(stdout.contains("type:") && stdout.contains("CODE_SMELL=2"),
                "stdout must include the type rollup, got: " + stdout);
    }

    @Test
    @DisplayName("--save summary rollups cover every severity + type preferred-order bucket")
    void saveSummaryRollupsCoverAllBuckets(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        Path target = dir.resolve(".sonar-predictor").resolve("scan.json");
        StubRpc rpc = rpc();
        // One issue per severity bucket and per type bucket — exercises every
        // branch of the rollup helper, including the per-bucket ordering and
        // the type-vs-severity distinction.
        rpc.analyzeResult = new AnalyzeResponse(
                List.of(
                        new Issue("java:Sb", "Bad.java", 1, 0, 1, 5, "BLOCKER", "BUG", "m1"),
                        new Issue("java:Sv", "Bad.java", 2, 0, 2, 5, "MINOR", "VULNERABILITY", "m2"),
                        new Issue("java:Sh", "Bad.java", 3, 0, 3, 5, "INFO", "SECURITY_HOTSPOT", "m3"),
                        new Issue("java:Sc", "Bad.java", 4, 0, 4, 5, "CRITICAL", "CODE_SMELL", "m4")),
                List.of());

        Run run = run(rpc, control(),
                "--format", "json",
                "--save", target.toString(),
                "check", source.toString());

        assertEquals(1, run.exitCode());
        String stdout = run.out();

        // Severity rollup — all four buckets we generated appear, in preferred
        // order (BLOCKER before CRITICAL before MINOR before INFO).
        int blocker = stdout.indexOf("BLOCKER=1");
        int critical = stdout.indexOf("CRITICAL=1");
        int minor = stdout.indexOf("MINOR=1");
        int info = stdout.indexOf("INFO=1");
        assertTrue(blocker >= 0 && critical >= 0 && minor >= 0 && info >= 0,
                "every severity bucket must appear in the rollup, got: " + stdout);
        assertTrue(blocker < critical && critical < minor && minor < info,
                "severity rollup must preserve preferred order, got: " + stdout);

        // Type rollup — BUG / CODE_SMELL / VULNERABILITY / SECURITY_HOTSPOT
        // each contribute one issue.
        assertTrue(stdout.contains("BUG=1") && stdout.contains("CODE_SMELL=1")
                        && stdout.contains("VULNERABILITY=1") && stdout.contains("SECURITY_HOTSPOT=1"),
                "every type bucket must appear in the rollup, got: " + stdout);

        // And the saved file is the full JSON, not the summary.
        String written = Files.readString(target);
        assertTrue(written.contains("\"issueCount\":4"),
                "saved JSON must hold the full report");
    }

    @Test
    @DisplayName("--save to an unwritable target exits 2 with a clear error")
    void saveToUnwritableTargetExitsTwo(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        // Pass the temp directory itself as the --save path. Files.writeString
        // refuses to write a regular file at a directory's path, which exercises
        // the IOException catch + rethrow-as-IllegalStateException branch.
        StubRpc rpc = rpc();
        rpc.analyzeResult = new AnalyzeResponse(
                List.of(issue("Bad.java", "java:S1118", "MAJOR")), List.of());

        Run run = run(rpc, control(),
                "--format", "json",
                "--save", dir.toString(),
                "check", source.toString());

        assertEquals(2, run.exitCode(),
                "an unwritable --save target must exit 2 (tool error)");
        assertTrue(run.err().toLowerCase().contains("could not write report"),
                "stderr must explain the write failure, got: " + run.err());
    }

    @Test
    @DisplayName("--save rollup appends unknown type-buckets in sorted order after the preferred ones")
    void saveRollupHandlesUnknownTypeBuckets(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        Path target = dir.resolve(".sonar-predictor").resolve("scan.json");
        StubRpc rpc = rpc();
        // BLOCKER severity passes the severity floor. Both issues carry a
        // 'type' that's outside the preferred-order list (BUG / CODE_SMELL /
        // VULNERABILITY / SECURITY_HOTSPOT), forcing the rollup to fall
        // through to its unknown-bucket sorting branch.
        rpc.analyzeResult = new AnalyzeResponse(
                List.of(
                        new Issue("custom:R1", "Bad.java", 1, 0, 1, 5, "BLOCKER", "WEIRDTYPE", "m1"),
                        new Issue("custom:R2", "Bad.java", 2, 0, 2, 5, "BLOCKER", "OTHER", "m2")),
                List.of());

        Run run = run(rpc, control(),
                "--format", "json",
                "--save", target.toString(),
                "check", source.toString());

        assertEquals(1, run.exitCode());
        String stdout = run.out();
        int other = stdout.indexOf("OTHER=1");
        int weird = stdout.indexOf("WEIRDTYPE=1");
        assertTrue(other >= 0 && weird >= 0,
                "unknown type buckets must appear in the rollup, got: " + stdout);
        assertTrue(other < weird,
                "unknown type buckets must be sorted alphabetically, got: " + stdout);
    }

    @Test
    @DisplayName("--save on a clean scan still writes the report and emits the summary, exit 0")
    void saveCleanScanWritesEmptyReport(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");
        Path target = dir.resolve(".sonar-predictor").resolve("scan.json");
        StubRpc rpc = rpc();
        rpc.analyzeResult = new AnalyzeResponse(List.of(), List.of());

        Run run = run(rpc, control(),
                "--format", "json",
                "--save", target.toString(),
                "check", source.toString());

        assertEquals(0, run.exitCode(), "no issues must exit 0 even with --save");
        assertTrue(Files.exists(target), "--save target file must be created even when clean");
        assertTrue(run.out().contains("sonar-predictor: 0 issues written to"),
                "stdout must still announce the 0-issue result, got: " + run.out());
        // Severity / type rollup lines are skipped when there are no issues —
        // assert their absence so the summary stays compact.
        assertFalse(run.out().contains("severity:"),
                "no severity rollup when there are no issues, got: " + run.out());
    }

    @Test
    @DisplayName("--timings prints round-trip to stderr and leaves stdout byte-identical")
    void timingsAreStderrOnlyAndStdoutUnchanged(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        // The same canned response drives both runs so any stdout difference
        // can only come from the --timings flag itself.
        List<Issue> issues = List.of(issue("Bad.java", "java:S1118", "MAJOR"));

        StubRpc baseline = rpc();
        baseline.analyzeResult = new AnalyzeResponse(issues, List.of());
        Run without = run(baseline, control(), "check", file.toString());

        StubRpc timed = rpc();
        timed.analyzeResult = new AnalyzeResponse(issues, List.of());
        Run with = run(timed, control(), "--timings", "check", file.toString());

        // Equivalence guarantee: stdout is byte-identical with and without the flag.
        assertEquals(without.out(), with.out(),
                "stdout must be identical whether or not --timings is set");
        assertEquals(without.exitCode(), with.exitCode(),
                "exit code must be unaffected by --timings");

        // Without the flag, no extra timing noise leaks to stderr.
        assertFalse(without.err().contains("analyze round-trip"),
                "no timing line without --timings, got: " + without.err());

        // With the flag, the timing line lands on stderr.
        assertTrue(with.err().contains("analyze round-trip"),
                "--timings must print the round-trip to stderr, got: " + with.err());
        assertTrue(with.err().contains("ms"),
                "--timings line must report milliseconds, got: " + with.err());
    }

    @Test
    @DisplayName("--severity filters out issues below the minimum before the exit-code decision")
    void severityFilterChangesExitCode(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        StubRpc rpc = rpc();
        rpc.analyzeResult = new AnalyzeResponse(
                List.of(issue("Bad.java", "java:S106", "MINOR")), List.of());

        // Only a MINOR issue; raising the floor to MAJOR filters it out -> exit 0.
        Run run = run(rpc, control(), "--severity", "MAJOR", "check", file.toString());

        assertEquals(0, run.exitCode(),
                "an issue below the severity floor must not trip exit 1");
    }

    @Test
    @DisplayName("a tool error (daemon failure) exits 2 with a stderr message")
    void toolErrorExitsTwo(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        StubRpc rpc = rpc();
        rpc.analyzeFailure = new DaemonException("daemon unreachable");

        Run run = run(rpc, control(), "check", file.toString());

        assertEquals(2, run.exitCode(), "a tool error must exit 2");
        assertFalse(run.err().isBlank(), "a tool error must explain itself on stderr");
    }

    @Test
    @DisplayName("check on a non-existent file is a tool error (exit 2)")
    void checkMissingFileExitsTwo() {
        Run run = run(rpc(), control(), "check", "/no/such/File.java");

        assertEquals(2, run.exitCode(), "a missing input file must exit 2");
    }

    @Test
    @DisplayName("analyze walks a project directory and analyzes its sources")
    void analyzeWalksProject(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Main.java"), "class Main {}");
        StubRpc rpc = rpc();
        rpc.analyzeResult = new AnalyzeResponse(List.of(), List.of());

        Run run = run(rpc, control(), "analyze", dir.toString());

        assertEquals(0, run.exitCode());
        assertEquals(1, rpc.requests.size(), "analyze must issue one ANALYZE request");
        assertTrue(rpc.requests.get(0).files().contains("Main.java"),
                "the project walk must have collected Main.java");
    }

    @Test
    @DisplayName("daemon status reports the running state")
    void daemonStatusReportsState() {
        StubControl running = control();
        running.running = true;
        Run up = run(rpc(), running, "daemon", "status");
        assertEquals(0, up.exitCode());
        assertTrue(up.out().toLowerCase().contains("running"),
                "status must report a running daemon");

        Run down = run(rpc(), control(), "daemon", "status");
        assertEquals(0, down.exitCode());
        assertTrue(down.out().toLowerCase().contains("not running")
                        || down.out().toLowerCase().contains("stopped"),
                "status must report a stopped daemon");
    }

    @Test
    @DisplayName("daemon start starts the daemon; daemon stop stops it")
    void daemonStartStop() {
        StubControl control = control();

        Run start = run(rpc(), control, "daemon", "start");
        assertEquals(0, start.exitCode());
        assertEquals(1, control.startCalls, "daemon start must start the daemon");
        assertTrue(control.running);

        Run stop = run(rpc(), control, "daemon", "stop");
        assertEquals(0, stop.exitCode());
        assertEquals(1, control.stopCalls, "daemon stop must stop the daemon");
        assertFalse(control.running);
        assertTrue(stop.out().toLowerCase().contains("stopped"),
                "a confirmed stop must report success, got: " + stop.out());
    }

    @Test
    @DisplayName("daemon stop reports failure and exits non-zero when the daemon did not stop")
    void daemonStopFailureExitsNonZero() {
        StubControl control = control();
        control.running = true;
        // The shutdown does not take effect — the daemon is still present when
        // the stop deadline expires.
        control.stopSucceeds = false;

        Run stop = run(rpc(), control, "daemon", "stop");

        assertEquals(SonarCommand.EXIT_TOOL_ERROR, stop.exitCode(),
                "a failed daemon stop must exit non-zero");
        assertFalse(stop.out().toLowerCase().contains("daemon stopped"),
                "a failed stop must not falsely claim the daemon stopped, got: " + stop.out());
        assertTrue(stop.err().toLowerCase().contains("fail"),
                "a failed stop must report a clear failure message, got: " + stop.err());
    }

    @Test
    @DisplayName("check --diff resolves changed files via git")
    void checkDiffResolvesChangedFiles(@TempDir Path dir) throws Exception {
        gitInit(dir);
        Files.writeString(dir.resolve("Tracked.java"), "class Tracked {}");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "base");
        Files.writeString(dir.resolve("Tracked.java"), "class Tracked { int x; }");

        StubRpc rpc = rpc();
        rpc.analyzeResult = new AnalyzeResponse(List.of(), List.of());

        Run run = run(rpc, control(), "check", "--diff", "--project", dir.toString());

        assertEquals(0, run.exitCode());
        assertEquals(1, rpc.requests.size(), "check --diff must issue one ANALYZE request");
        assertTrue(rpc.requests.get(0).files().contains("Tracked.java"),
                "the diff must have selected the changed file");
    }

    private static void gitInit(Path dir) throws Exception {
        git(dir, "init", "-q");
        git(dir, "config", "user.email", "t@example.com");
        git(dir, "config", "user.name", "T");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
    }
}
