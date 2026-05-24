package dev.sonarcli.cli.coverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Go coverage profile — the file {@code go test -coverprofile} writes.
 *
 * <p>The first line is {@code mode: set|count|atomic}. Every subsequent line is
 * one profiled block:
 * <pre>{@code <file>:<startLine>.<startCol>,<endLine>.<endCol> <numStmt> <count>}</pre>
 * Coverage is measured in statements: a block contributes {@code numStmt} to the
 * file's coverable count, and the same {@code numStmt} to the covered count when
 * its execution {@code count > 0}. Blocks are summed per file.
 */
public final class GoCoverageParser implements CoverageParser {

    @Override
    public CoverageReport parse(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new CoverageException(
                    "could not read Go coverage profile " + path + ": " + e.getMessage(), e);
        }

        if (lines.isEmpty() || !lines.get(0).strip().startsWith("mode:")) {
            throw new CoverageException(
                    "not a Go coverage profile " + path + " (expected a 'mode:' first line)");
        }

        // Sum per file: profile blocks for one file are interleaved arbitrarily.
        var counts = new java.util.LinkedHashMap<String, int[]>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            parseBlock(line, path, i + 1, counts);
        }

        // Go coverage is statement-based: a profile block has no per-line
        // identity, so these entries are opaque and cannot be line-merged.
        List<FileCoverage> files = new ArrayList<>();
        counts.forEach((file, c) -> files.add(FileCoverage.ofCounts(file, c[0], c[1])));
        return new CoverageReport(files);
    }

    /** Parses one {@code <file>:<range> <numStmt> <count>} block line. */
    private static void parseBlock(String line, Path path, int lineNo,
                                   java.util.Map<String, int[]> counts) {
        // The file path may itself contain ':' on Windows-style paths; the
        // block range is the LAST colon-separated segment, so split on the
        // final ':' that precedes a "<digits>.<digits>," range.
        int colon = lastRangeColon(line);
        int firstSpace = line.indexOf(' ', colon < 0 ? 0 : colon);
        if (colon < 0 || firstSpace < 0) {
            throw new CoverageException(
                    "malformed Go coverage profile " + path + ": bad block at line " + lineNo);
        }
        String file = line.substring(0, colon);
        String[] tail = line.substring(firstSpace).strip().split("\\s+");
        if (tail.length < 2) {
            throw new CoverageException(
                    "malformed Go coverage profile " + path + ": block at line " + lineNo
                    + " is missing numStmt or count");
        }
        try {
            int numStmt = Integer.parseInt(tail[tail.length - 2]);
            long count = Long.parseLong(tail[tail.length - 1]);
            int[] c = counts.computeIfAbsent(file, k -> new int[2]);
            c[1] += numStmt;
            if (count > 0) {
                c[0] += numStmt;
            }
        } catch (NumberFormatException e) {
            throw new CoverageException(
                    "malformed Go coverage profile " + path + ": non-numeric numStmt/count "
                    + "at line " + lineNo, e);
        }
    }

    /**
     * Index of the ':' that separates the file path from the position range.
     * The range begins with a digit, so the separator is the last ':' that is
     * immediately followed by a digit.
     */
    private static int lastRangeColon(String line) {
        for (int i = line.length() - 1; i >= 0; i--) {
            if (line.charAt(i) == ':' && i + 1 < line.length()
                    && Character.isDigit(line.charAt(i + 1))) {
                return i;
            }
        }
        return -1;
    }
}
