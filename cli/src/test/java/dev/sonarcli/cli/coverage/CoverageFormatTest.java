package dev.sonarcli.cli.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the coverage model ({@link FileCoverage}, {@link CoverageReport})
 * and content-sniffing format detection ({@link CoverageFormat#detect(Path)}).
 */
class CoverageFormatTest {

    @Test
    @DisplayName("FileCoverage percent() is covered/coverable as a percentage")
    void fileCoveragePercent() {
        FileCoverage fc = FileCoverage.ofCounts("src/A.java", 3, 4);
        assertEquals(75.0, fc.percent(), 0.0001);
    }

    @Test
    @DisplayName("FileCoverage percent() is 100 when there are no coverable lines")
    void fileCoverageEmptyPercent() {
        FileCoverage fc = FileCoverage.ofCounts("src/Empty.java", 0, 0);
        assertEquals(100.0, fc.percent(), 0.0001);
    }

    @Test
    @DisplayName("CoverageReport overallPercent() aggregates across all files")
    void reportOverallPercent() {
        CoverageReport report = new CoverageReport(List.of(
                FileCoverage.ofCounts("src/A.java", 3, 4),
                FileCoverage.ofCounts("src/B.java", 1, 4)));
        // 4 covered of 8 coverable.
        assertEquals(50.0, report.overallPercent(), 0.0001);
    }

    @Test
    @DisplayName("CoverageReport overallPercent() is 100 for an empty report")
    void reportEmptyOverallPercent() {
        assertEquals(100.0, new CoverageReport(List.of()).overallPercent(), 0.0001);
    }

    @Test
    @DisplayName("detect identifies a JaCoCo XML report")
    void detectJacoco(@TempDir Path dir) throws IOException {
        Path f = write(dir, "jacoco.xml",
                "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">\n"
                + "<report name=\"x\"><sourcefile name=\"A.java\">"
                + "<counter type=\"LINE\" missed=\"1\" covered=\"3\"/></sourcefile></report>");
        assertEquals(CoverageFormat.JACOCO, CoverageFormat.detect(f));
    }

    @Test
    @DisplayName("detect identifies a Cobertura XML report")
    void detectCobertura(@TempDir Path dir) throws IOException {
        Path f = write(dir, "coverage.xml",
                "<?xml version=\"1.0\"?>\n"
                + "<coverage line-rate=\"0.5\"><packages><package name=\"p\"/>"
                + "</packages></coverage>");
        assertEquals(CoverageFormat.COBERTURA, CoverageFormat.detect(f));
    }

    @Test
    @DisplayName("detect identifies an LCOV report")
    void detectLcov(@TempDir Path dir) throws IOException {
        Path f = write(dir, "lcov.info",
                "TN:\nSF:src/a.js\nDA:1,3\nDA:2,0\nend_of_record\n");
        assertEquals(CoverageFormat.LCOV, CoverageFormat.detect(f));
    }

    @Test
    @DisplayName("detect identifies a Go coverage profile")
    void detectGo(@TempDir Path dir) throws IOException {
        Path f = write(dir, "cover.out",
                "mode: set\nexample.com/p/a.go:3.13,5.2 2 1\n");
        assertEquals(CoverageFormat.GO, CoverageFormat.detect(f));
    }

    @Test
    @DisplayName("detect identifies a Clover XML report")
    void detectClover(@TempDir Path dir) throws IOException {
        Path f = write(dir, "clover.xml",
                "<?xml version=\"1.0\"?>\n"
                + "<coverage generated=\"123\" clover=\"3.2.0\"><project>"
                + "<file path=\"a.php\"><line num=\"1\" count=\"1\" type=\"stmt\"/>"
                + "</file></project></coverage>");
        assertEquals(CoverageFormat.CLOVER, CoverageFormat.detect(f));
    }

    @Test
    @DisplayName("detect identifies a SimpleCov resultset JSON report")
    void detectSimpleCov(@TempDir Path dir) throws IOException {
        Path f = write(dir, ".resultset.json",
                "{\"RSpec\":{\"coverage\":{\"a.rb\":{\"lines\":[null,1,0]}}}}");
        assertEquals(CoverageFormat.SIMPLECOV, CoverageFormat.detect(f));
    }

    @Test
    @DisplayName("detect rejects an unknown file with a clear, file-naming error")
    void detectUnknownRejected(@TempDir Path dir) throws IOException {
        Path f = write(dir, "mystery.txt", "this is not a coverage report\n");
        CoverageException ex = assertThrows(CoverageException.class,
                () -> CoverageFormat.detect(f));
        assertTrue(ex.getMessage().contains("mystery.txt"),
                "the error must name the offending file, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("detect rejects a missing file with a clear error")
    void detectMissingRejected(@TempDir Path dir) {
        assertThrows(CoverageException.class,
                () -> CoverageFormat.detect(dir.resolve("ghost.xml")));
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }
}
