package io.github.randomcodespace.sonarpredict.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeRequest;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.Issue;
import io.github.randomcodespace.sonarpredict.protocol.dto.PingResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.RuleMetadata;

import picocli.CommandLine;

/**
 * Exercises the {@code --coverage} and {@code --coverage-min} CLI wiring against
 * a stub daemon: the coverage summary lands in text/JSON/SARIF output, and
 * {@code --coverage-min} drives the exit code.
 */
class CoverageCliTest {

    private static final Path COVERAGE_FIXTURES =
            Path.of("src/test/resources/cli/fixtures/coverage").toAbsolutePath();

    /** A stub daemon returning a fixed analyze result. */
    private static final class StubRpc implements DaemonRpc {
        private AnalyzeResponse analyzeResult = new AnalyzeResponse(List.of(), List.of());

        @Override
        public PingResponse ping() {
            return new PingResponse("0.1.0-test", 1L, List.of("java"));
        }

        @Override
        public AnalyzeResponse analyze(AnalyzeRequest request) {
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
            // Intentionally empty: the coverage tests do not exercise daemon
            // shutdown; the no-op satisfies the interface contract for this stub.
        }
    }

    /** A no-op stub daemon-process control. */
    private static final class StubControl implements DaemonControl {
        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void start() {
            // Intentionally empty: this stub reports the daemon as already
            // running, so start() is never expected to do anything meaningful.
        }

        @Override
        public boolean stop() {
            return true;
        }
    }

    private record Run(int exitCode, String out, String err) {
    }

    private static Run run(StubRpc rpc, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = SonarCommand.configure(
                        new CommandLine(new SonarCommand(rpc, new StubControl())))
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        int code = cmd.execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    private static StubRpc cleanRpc() {
        StubRpc rpc = new StubRpc();
        rpc.analyzeResult = new AnalyzeResponse(List.of(), List.of());
        return rpc;
    }

    private static Issue issue(String file) {
        return new Issue("java:S1118", file, 1, 0, 1, 5,
                "MAJOR", "CODE_SMELL", "a finding");
    }

    @Test
    @DisplayName("check --coverage shows a coverage summary in text output")
    void coverageSummaryInText(@TempDir Path dir) throws Exception {
        Path src = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");
        Path lcov = COVERAGE_FIXTURES.resolve("lcov.info");

        Run run = run(cleanRpc(), "--format", "text",
                "check", "--coverage", lcov.toString(), src.toString());

        assertEquals(0, run.exitCode(), "no issues, no threshold -> exit 0, err: " + run.err());
        assertTrue(run.out().toLowerCase().contains("coverage"),
                "text output must carry a coverage summary, got: " + run.out());
        assertTrue(run.out().contains("66.7%") || run.out().contains("66.6%"),
                "the overall coverage percentage must appear, got: " + run.out());
        assertTrue(run.out().contains("src/calc.js"),
                "per-file coverage must appear, got: " + run.out());
    }

    @Test
    @DisplayName("check --coverage shows a coverage object in JSON output")
    void coverageSummaryInJson(@TempDir Path dir) throws Exception {
        Path src = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");
        Path lcov = COVERAGE_FIXTURES.resolve("lcov.info");

        Run run = run(cleanRpc(), "--format", "json",
                "check", "--coverage", lcov.toString(), src.toString());

        assertEquals(0, run.exitCode());
        JsonNode root = Json.mapper().readTree(run.out());
        JsonNode coverage = root.get("coverage");
        assertFalse(coverage == null || coverage.isNull(),
                "JSON must carry a 'coverage' object, got: " + run.out());
        assertTrue(coverage.get("overallPercent").asDouble() > 66.0
                        && coverage.get("overallPercent").asDouble() < 67.0,
                "overallPercent must be ~66.67");
        assertEquals(2, coverage.get("files").size(), "two files in the merged report");
    }

    @Test
    @DisplayName("check --coverage adds run-level coverage properties to SARIF")
    void coverageInSarifProperties(@TempDir Path dir) throws Exception {
        Path src = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");
        Path lcov = COVERAGE_FIXTURES.resolve("lcov.info");

        Run run = run(cleanRpc(), "check", "--coverage", lcov.toString(), src.toString());

        assertEquals(0, run.exitCode());
        JsonNode run0 = Json.mapper().readTree(run.out()).get("runs").get(0);
        JsonNode coverage = run0.path("properties").path("coverage");
        assertFalse(coverage.isMissingNode(),
                "SARIF run.properties.coverage must be present, got: " + run.out());
        assertTrue(coverage.get("overallPercent").asDouble() > 66.0,
                "the coverage properties must carry the overall percentage");
    }

    @Test
    @DisplayName("--coverage-min above actual coverage fails the run (exit 1)")
    void coverageMinAboveActualFails(@TempDir Path dir) throws Exception {
        Path src = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");
        Path lcov = COVERAGE_FIXTURES.resolve("lcov.info"); // ~66.67%

        Run run = run(cleanRpc(), "--format", "text",
                "check", "--coverage", lcov.toString(),
                "--coverage-min", "90", src.toString());

        assertEquals(1, run.exitCode(),
                "coverage below the threshold must fail the run, err: " + run.err());
    }

    @Test
    @DisplayName("--coverage-min below actual coverage passes when there are no issues")
    void coverageMinBelowActualPasses(@TempDir Path dir) throws Exception {
        Path src = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");
        Path lcov = COVERAGE_FIXTURES.resolve("lcov.info"); // ~66.67%

        Run run = run(cleanRpc(), "--format", "text",
                "check", "--coverage", lcov.toString(),
                "--coverage-min", "50", src.toString());

        assertEquals(0, run.exitCode(),
                "coverage above the threshold must not fail the run, err: " + run.err());
    }

    @Test
    @DisplayName("issues still trip exit 1 even when coverage clears the threshold")
    void issuesFailEvenWithGoodCoverage(@TempDir Path dir) throws Exception {
        Path src = Files.writeString(dir.resolve("Bad.java"), "class Bad {}");
        Path lcov = COVERAGE_FIXTURES.resolve("lcov.info");
        StubRpc rpc = new StubRpc();
        rpc.analyzeResult = new AnalyzeResponse(List.of(issue("Bad.java")), List.of());

        Run run = run(rpc, "check", "--coverage", lcov.toString(),
                "--coverage-min", "50", src.toString());

        assertEquals(1, run.exitCode(), "an issue must fail the run regardless of coverage");
    }

    @Test
    @DisplayName("multiple --coverage reports of mixed formats are merged")
    void multipleCoverageReportsMerged(@TempDir Path dir) throws Exception {
        Path src = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");
        Path lcov = COVERAGE_FIXTURES.resolve("lcov.info");
        Path go = COVERAGE_FIXTURES.resolve("go-cover.out");

        Run run = run(cleanRpc(), "--format", "json",
                "check", "--coverage", lcov.toString(),
                "--coverage", go.toString(), src.toString());

        assertEquals(0, run.exitCode());
        JsonNode coverage = Json.mapper().readTree(run.out()).get("coverage");
        assertEquals(4, coverage.get("files").size(),
                "two LCOV files and two Go files must all appear");
    }

    @Test
    @DisplayName("a corrupt --coverage report is a tool error (exit 2)")
    void corruptCoverageReportExitsTwo(@TempDir Path dir) throws Exception {
        Path src = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");
        Path bad = Files.writeString(dir.resolve("mystery.dat"), "not a coverage report");

        Run run = run(cleanRpc(), "check", "--coverage", bad.toString(), src.toString());

        assertEquals(2, run.exitCode(), "a coverage parse error must exit 2");
        assertFalse(run.err().isBlank(), "the tool error must be explained on stderr");
    }

    @Test
    @DisplayName("without --coverage there is no coverage summary and exit is unchanged")
    void noCoverageNoSummary(@TempDir Path dir) throws Exception {
        Path src = Files.writeString(dir.resolve("Clean.java"), "class Clean {}");

        Run run = run(cleanRpc(), "--format", "json", "check", src.toString());

        assertEquals(0, run.exitCode());
        JsonNode root = Json.mapper().readTree(run.out());
        assertTrue(root.get("coverage") == null || root.get("coverage").isNull(),
                "no --coverage means no coverage object, got: " + run.out());
    }
}
