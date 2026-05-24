package io.github.randomcodespace.sonarpredict.cli.coverage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Imports one or more coverage reports — of any mix of the six supported
 * {@link CoverageFormat formats} — and merges them into a single
 * {@link CoverageReport}.
 *
 * <p>Each path is {@linkplain CoverageFormat#detect detected}, parsed by the
 * matching {@link CoverageParser}, and folded into the merge. When two reports
 * describe the same file path their coverage is unioned: a line covered in
 * <em>either</em> report counts as covered, and the coverable line set is the
 * union of both. Opaque, count-only entries (e.g. Go statement coverage) cannot
 * be line-unioned; for those the entry with the most coverable lines wins, so a
 * fuller report supersedes a partial one rather than double-counting.
 */
public final class CoverageImporter {

    /**
     * Imports and merges the given coverage reports.
     *
     * @param reportPaths the report files, in any mix of supported formats
     * @return one merged report
     * @throws CoverageException if any file is missing, unrecognized, or
     *                           malformed; the message names the offending file
     */
    public CoverageReport importReports(List<Path> reportPaths) {
        // Insertion-ordered so the merged report is deterministic.
        Map<String, FileCoverage> merged = new LinkedHashMap<>();
        for (Path path : reportPaths) {
            CoverageReport parsed = parseOne(path);
            for (FileCoverage file : parsed.files()) {
                merged.merge(file.path(), file, CoverageImporter::mergeFiles);
            }
        }
        return new CoverageReport(new ArrayList<>(merged.values()));
    }

    /** Detects a report's format and parses it with the matching parser. */
    private static CoverageReport parseOne(Path path) {
        CoverageFormat format = CoverageFormat.detect(path);
        CoverageParser parser = switch (format) {
            case JACOCO -> new JacocoCoverageParser();
            case COBERTURA -> new CoberturaCoverageParser();
            case LCOV -> new LcovCoverageParser();
            case GO -> new GoCoverageParser();
            case CLOVER -> new CloverCoverageParser();
            case SIMPLECOV -> new SimpleCovCoverageParser();
        };
        return parser.parse(path);
    }

    /**
     * Merges two {@link FileCoverage} entries for the same file path. Line-aware
     * entries are unioned line by line; if either side is opaque, the entry with
     * the larger coverable count is kept.
     */
    private static FileCoverage mergeFiles(FileCoverage a, FileCoverage b) {
        if (a.isLineAware() && b.isLineAware()) {
            NavigableSet<Integer> coverable = new TreeSet<>(a.coverableLineNumbers());
            coverable.addAll(b.coverableLineNumbers());
            NavigableSet<Integer> covered = new TreeSet<>(a.coveredLineNumbers());
            covered.addAll(b.coveredLineNumbers());
            return new FileCoverage(a.path(), covered, coverable);
        }
        // One side carries no line identity — keep the fuller report.
        return b.coverableLines() > a.coverableLines() ? b : a;
    }
}
