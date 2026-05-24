package dev.sonarcli.cli.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link CoverageImporter}: detect each report's format, parse
 * with the right parser, and merge multiple reports into one {@link CoverageReport}.
 */
class CoverageImporterTest {

    private static final Path FIXTURES =
            Path.of("src/test/resources/cli/fixtures/coverage").toAbsolutePath();

    @Test
    @DisplayName("imports a single report by detecting and dispatching to its parser")
    void importsSingleReport() {
        CoverageReport report = new CoverageImporter()
                .importReports(List.of(FIXTURES.resolve("lcov.info")));
        // lcov.info: 4 covered of 6 coverable.
        assertEquals(66.6667, report.overallPercent(), 0.001);
    }

    @Test
    @DisplayName("imports reports of mixed formats and unions their files")
    void importsMixedFormats() {
        CoverageReport report = new CoverageImporter().importReports(List.of(
                FIXTURES.resolve("lcov.info"),       // src/calc.js, src/util.js
                FIXTURES.resolve("go-cover.out")));   // two .go files

        Map<String, FileCoverage> byPath = index(report);
        assertTrue(byPath.containsKey("src/calc.js"), "LCOV files must be present");
        assertTrue(byPath.containsKey("example.com/demo/calc.go"),
                "Go files must be present");
        // 4/6 (lcov) + 6/9 (go) = 10 covered of 15 coverable.
        assertEquals(66.6667, report.overallPercent(), 0.001);
    }

    @Test
    @DisplayName("merging the same file from two reports unions covered lines")
    void mergesSameFileByLineUnion(@TempDir Path dir) throws IOException {
        // Report A covers lines 1,2 of shared.js (line 3 uncovered).
        Path a = dir.resolve("a.info");
        Files.writeString(a, "SF:src/shared.js\nDA:1,5\nDA:2,3\nDA:3,0\nend_of_record\n");
        // Report B covers lines 2,3 of shared.js (line 1 uncovered).
        Path b = dir.resolve("b.info");
        Files.writeString(b, "SF:src/shared.js\nDA:1,0\nDA:2,1\nDA:3,4\nend_of_record\n");

        CoverageReport report = new CoverageImporter().importReports(List.of(a, b));

        assertEquals(1, report.files().size(), "the shared file must be merged into one");
        FileCoverage shared = report.files().get(0);
        assertEquals("src/shared.js", shared.path());
        // Lines 1,2,3 all covered in at least one report -> 3 of 3.
        assertEquals(3, shared.coveredLines(), "a line covered in either report counts");
        assertEquals(3, shared.coverableLines());
        assertEquals(100.0, shared.percent(), 0.0001);
    }

    @Test
    @DisplayName("an empty report list yields an empty, fully-covered report")
    void importsNothing() {
        CoverageReport report = new CoverageImporter().importReports(List.of());
        assertTrue(report.files().isEmpty());
        assertEquals(100.0, report.overallPercent(), 0.0001);
    }

    @Test
    @DisplayName("an unknown/corrupt report fails with an error naming the file")
    void corruptReportRejected(@TempDir Path dir) throws IOException {
        Path good = FIXTURES.resolve("lcov.info");
        Path bad = dir.resolve("mystery.dat");
        Files.writeString(bad, "this is not any coverage format\n");

        CoverageException ex = assertThrows(CoverageException.class,
                () -> new CoverageImporter().importReports(List.of(good, bad)));
        assertTrue(ex.getMessage().contains("mystery.dat"),
                "the error must name the offending file, got: " + ex.getMessage());
    }

    private static Map<String, FileCoverage> index(CoverageReport report) {
        return report.files().stream()
                .collect(java.util.stream.Collectors.toMap(FileCoverage::path, f -> f));
    }
}
