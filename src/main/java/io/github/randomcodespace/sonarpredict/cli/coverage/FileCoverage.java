package io.github.randomcodespace.sonarpredict.cli.coverage;

import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Line coverage for a single source file: which of its coverable lines were
 * exercised by the test suite.
 *
 * <p>Coverage is held as two line-number sets — the coverable lines and the
 * subset of those that were covered — so that two reports covering the same
 * file can be merged losslessly: a line covered in <em>either</em> report
 * counts as covered (see {@link CoverageImporter}). Parsers that do not retain
 * line numbers (none currently) may use {@link #ofCounts} to build an entry
 * from bare covered/coverable counts; such an entry cannot be line-merged and
 * is treated as opaque.
 */
public final class FileCoverage {

    private final String path;
    private final NavigableSet<Integer> coverable;
    private final NavigableSet<Integer> covered;
    // Set only for count-based entries that carry no line identity.
    private final int opaqueCovered;
    private final int opaqueCoverable;
    private final boolean lineAware;

    /**
     * Builds a line-aware entry from explicit line-number sets.
     *
     * @param path      the source file path, as named by the coverage report
     * @param covered   the line numbers covered at least once
     * @param coverable the line numbers the report considers coverable
     */
    public FileCoverage(String path, NavigableSet<Integer> covered,
                         NavigableSet<Integer> coverable) {
        this.path = path;
        this.coverable = new TreeSet<>(coverable);
        this.covered = new TreeSet<>(covered);
        this.covered.retainAll(this.coverable);
        this.lineAware = true;
        this.opaqueCovered = 0;
        this.opaqueCoverable = 0;
    }

    private FileCoverage(String path, int covered, int coverable) {
        if (covered < 0 || coverable < 0) {
            throw new IllegalArgumentException(
                    "coverage counts cannot be negative: " + path);
        }
        if (covered > coverable) {
            throw new IllegalArgumentException(
                    "covered lines exceed coverable lines: " + path);
        }
        this.path = path;
        this.coverable = new TreeSet<>();
        this.covered = new TreeSet<>();
        this.lineAware = false;
        this.opaqueCovered = covered;
        this.opaqueCoverable = coverable;
    }

    /**
     * Builds an opaque, count-only entry — for callers and tests that have only
     * aggregate counts and never need line-level merge.
     */
    public static FileCoverage ofCounts(String path, int covered, int coverable) {
        return new FileCoverage(path, covered, coverable);
    }

    /** The source file path, as named by the coverage report. */
    public String path() {
        return path;
    }

    /** The count of coverable lines hit at least once. */
    public int coveredLines() {
        return lineAware ? covered.size() : opaqueCovered;
    }

    /** The count of lines the report considers coverable. */
    public int coverableLines() {
        return lineAware ? coverable.size() : opaqueCoverable;
    }

    /** Whether this entry carries line numbers and can be line-merged. */
    boolean isLineAware() {
        return lineAware;
    }

    /** The covered line numbers; empty for an opaque entry. */
    NavigableSet<Integer> coveredLineNumbers() {
        return new TreeSet<>(covered);
    }

    /** The coverable line numbers; empty for an opaque entry. */
    NavigableSet<Integer> coverableLineNumbers() {
        return new TreeSet<>(coverable);
    }

    /**
     * The covered fraction as a percentage in {@code [0, 100]}. A file with no
     * coverable lines is reported as fully covered (100%) so it never drags a
     * merged report below threshold.
     */
    public double percent() {
        int total = coverableLines();
        return total == 0 ? 100.0 : 100.0 * coveredLines() / total;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FileCoverage other)) {
            return false;
        }
        return path.equals(other.path)
                && coveredLines() == other.coveredLines()
                && coverableLines() == other.coverableLines();
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(path, coveredLines(), coverableLines());
    }

    @Override
    public String toString() {
        return "FileCoverage[" + path + ", " + coveredLines()
                + "/" + coverableLines() + "]";
    }
}
