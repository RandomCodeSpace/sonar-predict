package io.github.randomcodespace.sonarpredict.cli.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the line-oriented text coverage parsers — {@link LcovCoverageParser}
 * and {@link GoCoverageParser} — against small, self-contained fixtures.
 */
class TextCoverageParsersTest {

    private static final Path FIXTURES =
            Path.of("src/test/resources/cli/fixtures/coverage").toAbsolutePath();

    @Test
    @DisplayName("LCOV parser counts DA records: coverable = all, covered = hits > 0")
    void lcovParsesDaRecords() {
        CoverageReport report = new LcovCoverageParser().parse(FIXTURES.resolve("lcov.info"));

        Map<String, FileCoverage> byPath = index(report);
        FileCoverage calc = byPath.get("src/calc.js");
        assertEquals(3, calc.coveredLines(), "three of four DA records have hits > 0");
        assertEquals(4, calc.coverableLines());

        FileCoverage util = byPath.get("src/util.js");
        assertEquals(1, util.coveredLines());
        assertEquals(2, util.coverableLines());
    }

    @Test
    @DisplayName("LCOV overall percentage aggregates across records")
    void lcovOverallPercent() {
        CoverageReport report = new LcovCoverageParser().parse(FIXTURES.resolve("lcov.info"));
        // 4 covered of 6 coverable.
        assertEquals(66.6667, report.overallPercent(), 0.001);
    }

    @Test
    @DisplayName("Go parser sums numstmt per block: covered when count > 0")
    void goParsesProfileBlocks() {
        CoverageReport report = new GoCoverageParser().parse(FIXTURES.resolve("go-cover.out"));

        Map<String, FileCoverage> byPath = index(report);
        FileCoverage calc = byPath.get("example.com/demo/calc.go");
        // blocks: numstmt 2,3,1 with counts 1,1,0 -> covered 5, coverable 6.
        assertEquals(5, calc.coveredLines());
        assertEquals(6, calc.coverableLines());

        FileCoverage util = byPath.get("example.com/demo/util.go");
        // blocks: numstmt 2,1 with counts 0,1 -> covered 1, coverable 3.
        assertEquals(1, util.coveredLines());
        assertEquals(3, util.coverableLines());
    }

    @Test
    @DisplayName("Go overall percentage aggregates statements across files")
    void goOverallPercent() {
        CoverageReport report = new GoCoverageParser().parse(FIXTURES.resolve("go-cover.out"));
        // 6 covered of 9 coverable.
        assertEquals(66.6667, report.overallPercent(), 0.001);
    }

    @Test
    @DisplayName("a corrupt LCOV file (DA before SF) fails with a clear, file-naming error")
    void corruptLcovRejected() throws Exception {
        Path bad = Files.createTempFile("bad", ".info");
        Files.writeString(bad, "DA:1,3\nend_of_record\n");
        try {
            CoverageException ex = assertThrows(CoverageException.class,
                    () -> new LcovCoverageParser().parse(bad));
            assertTrue(ex.getMessage().contains(bad.getFileName().toString())
                            || ex.getMessage().contains(bad.toString()),
                    "the error must name the file, got: " + ex.getMessage());
        } finally {
            Files.deleteIfExists(bad);
        }
    }

    @Test
    @DisplayName("a Go profile missing its mode line fails with a clear error")
    void goWithoutModeRejected() throws Exception {
        Path bad = Files.createTempFile("bad", ".out");
        Files.writeString(bad, "example.com/p/a.go:3.1,4.2 1 1\n");
        try {
            assertThrows(CoverageException.class,
                    () -> new GoCoverageParser().parse(bad));
        } finally {
            Files.deleteIfExists(bad);
        }
    }

    private static Map<String, FileCoverage> index(CoverageReport report) {
        return report.files().stream()
                .collect(java.util.stream.Collectors.toMap(FileCoverage::path, f -> f));
    }
}
