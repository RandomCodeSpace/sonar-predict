package io.github.randomcodespace.sonarpredict.cli.coverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an LCOV tracefile — the {@code lcov.info} format JS/TS coverage tools
 * (Istanbul/nyc, Jest, c8) emit.
 *
 * <p>An LCOV file is a sequence of records, one per source file, delimited by
 * {@code end_of_record}. Within a record {@code SF:<path>} names the file and
 * each {@code DA:<line>,<hits>} is one coverable line — covered when
 * {@code hits > 0}. The {@code LF:}/{@code LH:} summary lines are ignored; the
 * {@code DA} records are counted directly so the result is self-consistent.
 * The same {@code SF} appearing twice has its lines summed.
 */
public final class LcovCoverageParser implements CoverageParser {

    @Override
    public CoverageReport parse(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new CoverageException(
                    "could not read LCOV report " + path + ": " + e.getMessage(), e);
        }

        // Collect line numbers per source file: SF may repeat across records.
        var coverableByFile = new java.util.LinkedHashMap<String,
                java.util.NavigableSet<Integer>>();
        var coveredByFile = new java.util.LinkedHashMap<String,
                java.util.NavigableSet<Integer>>();
        String current = null;
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("SF:")) {
                current = line.substring(3).strip();
                coverableByFile.computeIfAbsent(current, k -> new java.util.TreeSet<>());
                coveredByFile.computeIfAbsent(current, k -> new java.util.TreeSet<>());
            } else if (line.startsWith("DA:")) {
                if (current == null) {
                    throw new CoverageException(
                            "malformed LCOV report " + path + ": DA record at line "
                            + lineNo + " has no preceding SF");
                }
                long[] da = daRecord(line, path, lineNo);
                int sourceLine = (int) da[0];
                coverableByFile.get(current).add(sourceLine);
                if (da[1] > 0) {
                    coveredByFile.get(current).add(sourceLine);
                }
            } else if (line.equals("end_of_record")) {
                current = null;
            }
            // Other directives (FN, BRDA, LF, LH, TN, ...) are not line data.
        }

        List<FileCoverage> files = new ArrayList<>();
        coverableByFile.forEach((file, coverable) ->
                files.add(new FileCoverage(file, coveredByFile.get(file), coverable)));
        return new CoverageReport(files);
    }

    /**
     * Parses a {@code DA:<line>,<hits>} record into {@code [lineNumber, hits]}.
     * A trailing checksum field, if present, is ignored.
     */
    private static long[] daRecord(String line, Path path, int lineNo) {
        String payload = line.substring(3);
        String[] parts = payload.split(",");
        if (parts.length < 2) {
            throw new CoverageException(
                    "malformed LCOV report " + path + ": bad DA record at line " + lineNo);
        }
        try {
            long sourceLine = Long.parseLong(parts[0].strip());
            long hits = Long.parseLong(parts[1].strip());
            return new long[] {sourceLine, hits};
        } catch (NumberFormatException e) {
            throw new CoverageException(
                    "malformed LCOV report " + path + ": non-numeric DA record at line "
                    + lineNo, e);
        }
    }
}
