package dev.sonarcli.cli.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the XML coverage parsers — {@link JacocoCoverageParser} and
 * {@link CoberturaCoverageParser} — against small, self-contained fixtures.
 */
class XmlCoverageParsersTest {

    private static final Path FIXTURES =
            Path.of("src/test/resources/fixtures/coverage").toAbsolutePath();

    @Test
    @DisplayName("JaCoCo parser reads per-file LINE counters into covered/coverable counts")
    void jacocoParsesLineCounters() {
        CoverageReport report = new JacocoCoverageParser().parse(FIXTURES.resolve("jacoco.xml"));

        Map<String, FileCoverage> byPath = index(report);
        FileCoverage calc = byPath.get("com/example/Calculator.java");
        assertEquals(8, calc.coveredLines(), "covered = LINE counter 'covered'");
        assertEquals(10, calc.coverableLines(), "coverable = covered + missed");

        FileCoverage parser = byPath.get("com/example/Parser.java");
        assertEquals(5, parser.coveredLines());
        assertEquals(10, parser.coverableLines());
    }

    @Test
    @DisplayName("JaCoCo overall percentage aggregates across files")
    void jacocoOverallPercent() {
        CoverageReport report = new JacocoCoverageParser().parse(FIXTURES.resolve("jacoco.xml"));
        // 13 covered of 20 coverable.
        assertEquals(65.0, report.overallPercent(), 0.0001);
    }

    @Test
    @DisplayName("Cobertura parser counts <line> elements: coverable = all, covered = hits > 0")
    void coberturaParsesLines() {
        CoverageReport report =
                new CoberturaCoverageParser().parse(FIXTURES.resolve("cobertura.xml"));

        Map<String, FileCoverage> byPath = index(report);
        FileCoverage module = byPath.get("app/module.py");
        assertEquals(3, module.coveredLines(), "three of four lines have hits > 0");
        assertEquals(4, module.coverableLines());

        FileCoverage util = byPath.get("app/util.py");
        assertEquals(1, util.coveredLines());
        assertEquals(2, util.coverableLines());
    }

    @Test
    @DisplayName("Cobertura overall percentage aggregates across files")
    void coberturaOverallPercent() {
        CoverageReport report =
                new CoberturaCoverageParser().parse(FIXTURES.resolve("cobertura.xml"));
        // 4 covered of 6 coverable.
        assertEquals(66.6667, report.overallPercent(), 0.001);
    }

    @Test
    @DisplayName("a corrupt XML report fails with a clear, file-naming error")
    void corruptXmlRejected() throws Exception {
        Path bad = java.nio.file.Files.createTempFile("bad", ".xml");
        java.nio.file.Files.writeString(bad, "<report><sourcefile name=");
        try {
            CoverageException ex = assertThrows(CoverageException.class,
                    () -> new JacocoCoverageParser().parse(bad));
            org.junit.jupiter.api.Assertions.assertTrue(
                    ex.getMessage().contains(bad.toString())
                            || ex.getMessage().contains(bad.getFileName().toString()),
                    "the error must name the file, got: " + ex.getMessage());
        } finally {
            java.nio.file.Files.deleteIfExists(bad);
        }
    }

    private static Map<String, FileCoverage> index(CoverageReport report) {
        Function<FileCoverage, String> key = FileCoverage::path;
        return report.files().stream()
                .collect(java.util.stream.Collectors.toMap(key, f -> f));
    }
}
