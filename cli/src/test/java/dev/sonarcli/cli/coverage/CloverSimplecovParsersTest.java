package dev.sonarcli.cli.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CloverCoverageParser} and {@link SimpleCovCoverageParser}
 * against small, self-contained fixtures.
 */
class CloverSimplecovParsersTest {

    private static final Path FIXTURES =
            Path.of("src/test/resources/fixtures/coverage").toAbsolutePath();

    @Test
    @DisplayName("Clover parser counts only type=stmt lines; covered when count > 0")
    void cloverParsesStatementLines() {
        CoverageReport report = new CloverCoverageParser().parse(FIXTURES.resolve("clover.xml"));

        Map<String, FileCoverage> byPath = index(report);
        FileCoverage order = byPath.get("src/Order.php");
        // stmt lines have counts 5,0,2 -> covered 2, coverable 3 (method/cond ignored).
        assertEquals(2, order.coveredLines());
        assertEquals(3, order.coverableLines());

        FileCoverage cart = byPath.get("src/Cart.php");
        assertEquals(1, cart.coveredLines());
        assertEquals(2, cart.coverableLines());
    }

    @Test
    @DisplayName("Clover overall percentage aggregates across files")
    void cloverOverallPercent() {
        CoverageReport report = new CloverCoverageParser().parse(FIXTURES.resolve("clover.xml"));
        // 3 covered of 5 coverable.
        assertEquals(60.0, report.overallPercent(), 0.0001);
    }

    @Test
    @DisplayName("SimpleCov parser reads the lines array: null skipped, 0 uncovered, >0 covered")
    void simplecovParsesLinesArray() {
        CoverageReport report =
                new SimpleCovCoverageParser().parse(FIXTURES.resolve(".resultset.json"));

        Map<String, FileCoverage> byPath = index(report);
        FileCoverage order = byPath.get("lib/order.rb");
        // [null,1,1,0,null,4] -> coverable 4, covered 3.
        assertEquals(3, order.coveredLines());
        assertEquals(4, order.coverableLines());

        FileCoverage cart = byPath.get("lib/cart.rb");
        // [null,0,2] -> coverable 2, covered 1.
        assertEquals(1, cart.coveredLines());
        assertEquals(2, cart.coverableLines());
    }

    @Test
    @DisplayName("SimpleCov overall percentage aggregates across files")
    void simplecovOverallPercent() {
        CoverageReport report =
                new SimpleCovCoverageParser().parse(FIXTURES.resolve(".resultset.json"));
        // 4 covered of 6 coverable.
        assertEquals(66.6667, report.overallPercent(), 0.001);
    }

    @Test
    @DisplayName("a corrupt Clover file fails with a clear, file-naming error")
    void corruptCloverRejected() throws Exception {
        Path bad = Files.createTempFile("bad", ".xml");
        Files.writeString(bad, "<coverage><project><file path=");
        try {
            CoverageException ex = assertThrows(CoverageException.class,
                    () -> new CloverCoverageParser().parse(bad));
            assertTrue(ex.getMessage().contains(bad.getFileName().toString())
                            || ex.getMessage().contains(bad.toString()),
                    "the error must name the file, got: " + ex.getMessage());
        } finally {
            Files.deleteIfExists(bad);
        }
    }

    @Test
    @DisplayName("a corrupt SimpleCov JSON file fails with a clear error")
    void corruptSimplecovRejected() throws Exception {
        Path bad = Files.createTempFile("bad", ".resultset.json");
        Files.writeString(bad, "{not valid json");
        try {
            assertThrows(CoverageException.class,
                    () -> new SimpleCovCoverageParser().parse(bad));
        } finally {
            Files.deleteIfExists(bad);
        }
    }

    private static Map<String, FileCoverage> index(CoverageReport report) {
        return report.files().stream()
                .collect(java.util.stream.Collectors.toMap(FileCoverage::path, f -> f));
    }
}
