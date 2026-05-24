package io.github.randomcodespace.sonarpredict.cli.coverage;

import java.util.List;

/**
 * An aggregate of {@link FileCoverage} entries — the unit a parser yields and
 * the unit {@link CoverageImporter} merges. The overall percentage is computed
 * across every file's coverable lines, not as a mean of per-file percentages.
 *
 * @param files per-file coverage; the list is defensively copied
 */
public record CoverageReport(List<FileCoverage> files) {

    public CoverageReport {
        files = List.copyOf(files);
    }

    /**
     * The covered fraction across all files as a percentage in {@code [0, 100]}.
     * An empty report is reported as fully covered (100%).
     */
    public double overallPercent() {
        long covered = 0;
        long coverable = 0;
        for (FileCoverage file : files) {
            covered += file.coveredLines();
            coverable += file.coverableLines();
        }
        return coverable == 0 ? 100.0 : 100.0 * covered / coverable;
    }
}
