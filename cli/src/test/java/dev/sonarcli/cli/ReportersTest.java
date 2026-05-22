package dev.sonarcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.sonarcli.protocol.Json;
import dev.sonarcli.protocol.dto.AnalysisWarning;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.Issue;
import dev.sonarcli.protocol.dto.RuleMetadata;

/**
 * Unit tests for the {@link Reporter} implementations: {@link TextReporter}
 * renders a human-readable report; {@link JsonReporter} renders compact,
 * parseable JSON. Both group issues by file.
 */
class ReportersTest {

    private static final Issue ISSUE_A1 = new Issue(
            "java:S1118", "src/A.java", 3, 0, 3, 12,
            "MAJOR", "CODE_SMELL", "Add a private constructor.");
    private static final Issue ISSUE_A2 = new Issue(
            "java:S106", "src/A.java", 7, 4, 7, 20,
            "MINOR", "CODE_SMELL", "Replace System.out with a logger.");
    private static final Issue ISSUE_B1 = new Issue(
            "java:S2189", "src/B.java", 1, 0, 1, 5,
            "BLOCKER", "BUG", "Add an exit condition to this loop.");

    private static final AnalyzeResponse WITH_ISSUES = new AnalyzeResponse(
            List.of(ISSUE_A1, ISSUE_A2, ISSUE_B1),
            List.of(new AnalysisWarning("src/C.java", "could not parse file")));
    private static final AnalyzeResponse CLEAN =
            new AnalyzeResponse(List.of(), List.of());

    @Test
    @DisplayName("TextReporter renders each issue with its rule key, location, severity, message")
    void textRendersIssueDetail() {
        String text = new TextReporter().render(WITH_ISSUES);

        assertTrue(text.contains("java:S1118"), "rule key must appear");
        assertTrue(text.contains("MAJOR"), "severity must appear");
        assertTrue(text.contains("Add a private constructor."), "message must appear");
        assertTrue(text.contains("3"), "the start line must appear");
    }

    @Test
    @DisplayName("TextReporter groups issues under their file")
    void textGroupsByFile() {
        String text = new TextReporter().render(WITH_ISSUES);

        assertTrue(text.contains("src/A.java"), "file A header must appear");
        assertTrue(text.contains("src/B.java"), "file B header must appear");
        // A.java's two issues both precede B.java's single issue in the output.
        int fileA = text.indexOf("src/A.java");
        int fileB = text.indexOf("src/B.java");
        int s106 = text.indexOf("java:S106");
        assertTrue(fileA < s106 && s106 < fileB,
                "java:S106 must be rendered under src/A.java, before src/B.java");
    }

    @Test
    @DisplayName("TextReporter reports a clean response clearly")
    void textReportsClean() {
        String text = new TextReporter().render(CLEAN);

        assertTrue(text.toLowerCase().contains("no issues"),
                "a clean response must say there are no issues, got: " + text);
    }

    @Test
    @DisplayName("TextReporter surfaces analysis warnings")
    void textShowsWarnings() {
        String text = new TextReporter().render(WITH_ISSUES);

        assertTrue(text.toLowerCase().contains("warning"), "warnings section must appear");
        assertTrue(text.contains("could not parse file"), "the warning message must appear");
    }

    @Test
    @DisplayName("JsonReporter emits compact, single-line JSON")
    void jsonIsCompact() {
        String json = new JsonReporter().render(WITH_ISSUES);

        assertEquals(1, json.lines().count(),
                "compact JSON must be a single line, got:\n" + json);
    }

    @Test
    @DisplayName("JsonReporter output is parseable and groups issues by file")
    void jsonIsParseableAndGrouped() throws Exception {
        String json = new JsonReporter().render(WITH_ISSUES);

        JsonNode root = Json.mapper().readTree(json);
        JsonNode files = root.get("files");
        assertNotNull(files, "JSON must carry a 'files' grouping");
        assertEquals(2, files.size(), "two distinct files must be grouped");

        JsonNode fileA = null;
        for (JsonNode file : files) {
            if ("src/A.java".equals(file.get("file").asText())) {
                fileA = file;
            }
        }
        assertNotNull(fileA, "src/A.java group must be present");
        assertEquals(2, fileA.get("issues").size(),
                "src/A.java must carry both of its issues");
    }

    @Test
    @DisplayName("JsonReporter output is deterministic for the same input")
    void jsonIsDeterministic() {
        JsonReporter reporter = new JsonReporter();

        assertEquals(reporter.render(WITH_ISSUES), reporter.render(WITH_ISSUES),
                "the same response must always render to identical JSON");
    }

    @Test
    @DisplayName("JsonReporter renders a clean response with empty groupings")
    void jsonReportsClean() throws Exception {
        String json = new JsonReporter().render(CLEAN);

        JsonNode root = Json.mapper().readTree(json);
        assertEquals(0, root.get("files").size(), "a clean response has no file groups");
        assertEquals(0, root.get("issueCount").asInt(), "a clean response has zero issues");
    }

    @Test
    @DisplayName("JsonReporter rule block omits the heavy HTML description (token-lean)")
    void jsonRuleBlockOmitsHtmlDescription() throws Exception {
        String hugeHtml = "<p>" + "Z".repeat(4000) + "</p>";
        RuleMetadata meta = new RuleMetadata(
                "java:S1118", "Utility classes should not have public constructors",
                "java", "MAJOR", "CODE_SMELL", hugeHtml, "Add a private constructor.");
        RuleMetadataIndex index = new RuleMetadataIndex(List.of(meta));

        String json = new JsonReporter().render(WITH_ISSUES, index, null);

        assertFalse(json.contains("ZZZZZ"),
                "compact JSON must not embed the multi-KB HTML rule description");

        JsonNode root = Json.mapper().readTree(json);
        JsonNode ruleBlock = null;
        for (JsonNode file : root.get("files")) {
            for (JsonNode issue : file.get("issues")) {
                if ("java:S1118".equals(issue.get("ruleKey").asText())) {
                    ruleBlock = issue.get("rule");
                }
            }
        }
        assertNotNull(ruleBlock, "the java:S1118 issue must carry a rule block");
        assertNull(ruleBlock.get("description"),
                "the compact JSON rule block must not carry an HTML 'description'");
        assertNotNull(ruleBlock.get("name"), "the rule name stays in the compact JSON");
    }
}
