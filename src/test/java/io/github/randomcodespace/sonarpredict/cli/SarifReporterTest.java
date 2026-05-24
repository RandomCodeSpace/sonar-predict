package io.github.randomcodespace.sonarpredict.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalysisWarning;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.Issue;

/**
 * Unit tests for {@link SarifReporter}: renders an {@link AnalyzeResponse} as a
 * SARIF 2.1.0 log that parses as JSON and carries the required structure.
 */
class SarifReporterTest {

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
    @DisplayName("SARIF output parses as JSON and declares version 2.1.0")
    void sarifIsValidJsonWithVersion() throws Exception {
        String sarif = new SarifReporter().render(WITH_ISSUES);

        JsonNode root = Json.mapper().readTree(sarif);
        assertEquals("2.1.0", root.get("version").asText(), "SARIF version must be 2.1.0");
        assertNotNull(root.get("$schema"), "SARIF must declare a $schema");
        assertTrue(root.get("$schema").asText().contains("sarif"),
                "the $schema must reference the SARIF schema");
    }

    @Test
    @DisplayName("SARIF carries exactly one run with the sonar-predictor tool driver")
    void sarifHasOneRunWithDriver() throws Exception {
        String sarif = new SarifReporter().render(WITH_ISSUES);

        JsonNode runs = Json.mapper().readTree(sarif).get("runs");
        assertNotNull(runs, "SARIF must carry a 'runs' array");
        assertEquals(1, runs.size(), "exactly one run");

        JsonNode driver = runs.get(0).get("tool").get("driver");
        assertEquals("sonar-predictor", driver.get("name").asText(),
                "the driver name must be sonar-predictor");
        assertNotNull(driver.get("rules"), "the driver must carry a 'rules' array");
    }

    @Test
    @DisplayName("each result carries ruleId, mapped level, message and a physical location")
    void sarifResultsAreWellFormed() throws Exception {
        String sarif = new SarifReporter().render(WITH_ISSUES);

        JsonNode results = Json.mapper().readTree(sarif).get("runs").get(0).get("results");
        assertNotNull(results, "the run must carry a 'results' array");
        assertEquals(3, results.size(), "one result per issue");

        JsonNode first = results.get(0);
        assertEquals("java:S1118", first.get("ruleId").asText(), "ruleId must be the rule key");
        assertEquals("warning", first.get("level").asText(),
                "MAJOR must map to the SARIF 'warning' level");
        assertEquals("Add a private constructor.",
                first.get("message").get("text").asText(), "the message text must appear");

        JsonNode region = first.get("locations").get(0)
                .get("physicalLocation").get("region");
        assertEquals("src/A.java",
                first.get("locations").get(0).get("physicalLocation")
                        .get("artifactLocation").get("uri").asText(),
                "the artifact URI must be the file path");
        assertEquals(3, region.get("startLine").asInt(), "the region must carry the start line");
        assertEquals(7, results.get(1).get("locations").get(0)
                .get("physicalLocation").get("region").get("startLine").asInt());
    }

    @Test
    @DisplayName("severity maps to SARIF levels: BLOCKER/CRITICAL -> error, MAJOR -> warning, MINOR/INFO -> note")
    void sarifSeverityMapping() throws Exception {
        String sarif = new SarifReporter().render(WITH_ISSUES);
        JsonNode results = Json.mapper().readTree(sarif).get("runs").get(0).get("results");

        // ISSUE_A1 MAJOR, ISSUE_A2 MINOR, ISSUE_B1 BLOCKER, in input order.
        assertEquals("warning", results.get(0).get("level").asText());
        assertEquals("note", results.get(1).get("level").asText());
        assertEquals("error", results.get(2).get("level").asText());
    }

    @Test
    @DisplayName("a clean response renders a valid SARIF log with an empty results array")
    void sarifCleanResponse() throws Exception {
        String sarif = new SarifReporter().render(CLEAN);

        JsonNode root = Json.mapper().readTree(sarif);
        assertEquals("2.1.0", root.get("version").asText());
        assertEquals(0, root.get("runs").get(0).get("results").size(),
                "a clean response has no results");
    }
}
