package io.github.randomcodespace.sonarpredict.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.randomcodespace.sonarpredict.protocol.dto.Issue;

/**
 * Verifies {@link IssueGrouping} — the deterministic by-file grouping shared
 * by the text and JSON reporters.
 */
class IssueGroupingTest {

    private static Issue issue(String filePath, int line, String ruleKey) {
        return new Issue(ruleKey, filePath, line, 0, line, 0,
                "MAJOR", "CODE_SMELL", "msg");
    }

    @Test
    @DisplayName("groups issues by file path in a deterministic order")
    void groupsByFileInOrder() {
        List<Issue> issues = List.of(
                issue("b/Two.java", 5, "java:S100"),
                issue("a/One.java", 3, "java:S101"),
                issue("a/One.java", 1, "java:S102"));

        Map<String, List<Issue>> grouped = IssueGrouping.byFile(issues);

        assertEquals(List.of("a/One.java", "b/Two.java"),
                List.copyOf(grouped.keySet()),
                "files must be ordered by path");
        assertEquals(List.of(1, 3),
                grouped.get("a/One.java").stream().map(Issue::startLine).toList(),
                "within a file, issues must be ordered by start line");
    }

    @Test
    @DisplayName("an issue with a null file path groups and sorts without throwing")
    void nullFilePathDoesNotThrow() {
        List<Issue> issues = List.of(
                issue("src/Real.java", 4, "java:S100"),
                issue(null, 0, "java:S2095"));

        Map<String, List<Issue>> grouped = IssueGrouping.byFile(issues);

        // A null-path (project-level) issue must land in a stable bucket and
        // never trip the by-path comparator with a NullPointerException.
        assertTrue(grouped.containsKey(IssueGrouping.PROJECT_LEVEL_KEY),
                "a null-path issue must land in the project-level bucket: "
                        + grouped.keySet());
        assertEquals(1, grouped.get(IssueGrouping.PROJECT_LEVEL_KEY).size(),
                "the null-path issue must be grouped under the project-level key");
        assertTrue(grouped.containsKey("src/Real.java"),
                "the real-path issue must still be grouped under its file");
    }

    @Test
    @DisplayName("project-level (null-path) issues sort after real file paths")
    void nullPathBucketSortsLast() {
        List<Issue> issues = List.of(
                issue(null, 0, "java:S2095"),
                issue("z/Last.java", 1, "java:S100"),
                issue("a/First.java", 1, "java:S101"));

        Map<String, List<Issue>> grouped = IssueGrouping.byFile(issues);

        assertEquals(
                List.of("a/First.java", "z/Last.java", IssueGrouping.PROJECT_LEVEL_KEY),
                List.copyOf(grouped.keySet()),
                "the project-level bucket must sort after every real file path");
    }
}
