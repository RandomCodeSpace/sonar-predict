package dev.sonarcli.cli.coverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import dev.sonarcli.protocol.Json;

/**
 * Parses a SimpleCov {@code .resultset.json} coverage report — the format Ruby
 * projects produce.
 *
 * <p>The shape is a map of test-suite name to a result object:
 * <pre>{@code
 * {"RSpec": {"coverage": {"lib/a.rb": {"lines": [null, 1, 0, ...]}}}}
 * }</pre>
 * Each file's {@code lines} array is indexed by source line: {@code null} means
 * the line is not coverable, {@code 0} means coverable but unexercised, and any
 * value {@code > 0} means covered. Older SimpleCov versions store the array
 * directly as the file's value (no {@code lines} wrapper) — both are accepted.
 * Multiple suites covering the same file are merged: a line covered in any
 * suite counts as covered.
 */
public final class SimpleCovCoverageParser implements CoverageParser {

    @Override
    public CoverageReport parse(Path path) {
        JsonNode root;
        try {
            root = Json.mapper().readTree(Files.readString(path));
        } catch (IOException e) {
            throw new CoverageException(
                    "could not read SimpleCov report " + path + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new CoverageException(
                    "not a SimpleCov resultset: " + path + " (expected a JSON object)");
        }

        // Per file: index -> covered? Merge across suites by OR-ing coverage.
        var fileLines = new java.util.LinkedHashMap<String, boolean[][]>();
        root.fields().forEachRemaining(suite -> {
            JsonNode coverage = suite.getValue().path("coverage");
            if (!coverage.isObject()) {
                return;
            }
            coverage.fields().forEachRemaining(file ->
                    mergeFile(fileLines, file.getKey(), linesArray(file.getValue(), path)));
        });

        List<FileCoverage> files = new ArrayList<>();
        fileLines.forEach((file, cells) -> {
            // Cell index i is source line i + 1 (the lines array is 0-based).
            java.util.NavigableSet<Integer> coverable = new java.util.TreeSet<>();
            java.util.NavigableSet<Integer> covered = new java.util.TreeSet<>();
            for (int i = 0; i < cells.length; i++) {
                boolean[] cell = cells[i];
                if (cell != null && cell.length == 2) {
                    coverable.add(i + 1);
                    if (cell[1]) {
                        covered.add(i + 1);
                    }
                }
            }
            files.add(new FileCoverage(file, covered, coverable));
        });
        return new CoverageReport(files);
    }

    /** The {@code lines} array, accepting both the wrapped and bare shapes. */
    private static JsonNode linesArray(JsonNode fileNode, Path path) {
        if (fileNode.isArray()) {
            return fileNode;
        }
        JsonNode lines = fileNode.path("lines");
        if (lines.isArray()) {
            return lines;
        }
        throw new CoverageException(
                "malformed SimpleCov report " + path + ": a file entry has no lines array");
    }

    /**
     * Merges one file's {@code lines} array into the accumulator. Each cell is
     * {@code null} (not coverable) or a {@code [coverable=true, covered]} pair;
     * a later suite can only flip {@code covered} on, never off.
     */
    private static void mergeFile(java.util.Map<String, boolean[][]> acc,
                                  String file, JsonNode lines) {
        boolean[][] cells = acc.get(file);
        int size = lines.size();
        if (cells == null || cells.length < size) {
            boolean[][] grown = new boolean[size][];
            if (cells != null) {
                System.arraycopy(cells, 0, grown, 0, cells.length);
            }
            cells = grown;
            acc.put(file, cells);
        }
        for (int i = 0; i < size; i++) {
            JsonNode cell = lines.get(i);
            if (cell == null || cell.isNull()) {
                continue; // not coverable in this suite
            }
            boolean covered = cell.asLong(0) > 0;
            if (cells[i] == null) {
                cells[i] = new boolean[] {true, covered};
            } else {
                cells[i][0] = true;
                cells[i][1] = cells[i][1] || covered;
            }
        }
    }
}
