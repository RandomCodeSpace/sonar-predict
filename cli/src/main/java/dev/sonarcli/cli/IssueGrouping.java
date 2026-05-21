package dev.sonarcli.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.sonarcli.protocol.dto.Issue;

/**
 * Groups issues by file in a deterministic order — shared by both reporters so
 * text and JSON output present the same structure.
 *
 * <p>Files are ordered by path; within a file, issues are ordered by start
 * line then start column then rule key, so the same response always renders
 * identically.
 *
 * <p><b>Project-level issues.</b> An engine issue not bound to a single file
 * (e.g. a project-wide rule) carries a {@code null} {@link Issue#filePath()}.
 * Such issues are collected under {@link #PROJECT_LEVEL_KEY}, a stable bucket
 * that sorts after every real file path — so a null path is never fed to the
 * by-path comparator.
 */
final class IssueGrouping {

    /** Stable grouping key for issues with no file path (project-level). */
    static final String PROJECT_LEVEL_KEY = "(project)";

    private static final Comparator<Issue> BY_LOCATION = Comparator
            .comparingInt(Issue::startLine)
            .thenComparingInt(Issue::startColumn)
            .thenComparing(Issue::ruleKey);

    /**
     * Orders issues by file path with {@code null} paths last, then by
     * location — null-safe so a project-level issue cannot trip the sort.
     */
    private static final Comparator<Issue> BY_FILE_THEN_LOCATION = Comparator
            .comparing(Issue::filePath,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(BY_LOCATION);

    private IssueGrouping() {
    }

    /**
     * Groups {@code issues} by {@link Issue#filePath()}, collecting issues with
     * a {@code null} path under {@link #PROJECT_LEVEL_KEY}.
     *
     * @param issues the issues to group
     * @return an ordered map of file path to that file's sorted issues
     */
    static Map<String, List<Issue>> byFile(List<Issue> issues) {
        Map<String, List<Issue>> grouped = new LinkedHashMap<>();
        issues.stream()
                .sorted(BY_FILE_THEN_LOCATION)
                .forEach(issue -> grouped
                        .computeIfAbsent(groupingKey(issue), k -> new ArrayList<>())
                        .add(issue));
        return grouped;
    }

    /** The grouping key for {@code issue}: its file path, or the project bucket. */
    private static String groupingKey(Issue issue) {
        return issue.filePath() != null ? issue.filePath() : PROJECT_LEVEL_KEY;
    }
}
