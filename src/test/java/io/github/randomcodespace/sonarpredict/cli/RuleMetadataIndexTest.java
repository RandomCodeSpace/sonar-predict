package io.github.randomcodespace.sonarpredict.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.Issue;
import io.github.randomcodespace.sonarpredict.protocol.dto.RuleMetadata;

/**
 * Tests for {@link RuleMetadataIndex} and reporter enrichment: every reporter,
 * given the index, attaches the rule name, description, and how-to-fix to its
 * output. Missing metadata degrades gracefully.
 */
class RuleMetadataIndexTest {

    private static final RuleMetadata S1118 = new RuleMetadata(
            "java:S1118", "Utility classes should not have public constructors",
            "java", "MAJOR", "CODE_SMELL",
            "<p>Utility classes should not be instantiated.</p>",
            "Add a private constructor to hide the implicit public one.");

    private static final Issue ISSUE_KNOWN = new Issue(
            "java:S1118", "src/A.java", 3, 0, 3, 12,
            "MAJOR", "CODE_SMELL", "Add a private constructor.");
    private static final Issue ISSUE_UNKNOWN = new Issue(
            "java:S9999", "src/A.java", 9, 0, 9, 4,
            "MINOR", "CODE_SMELL", "Mystery finding.");

    private static final AnalyzeResponse RESPONSE = new AnalyzeResponse(
            List.of(ISSUE_KNOWN, ISSUE_UNKNOWN), List.of());

    private static RuleMetadataIndex index() {
        return new RuleMetadataIndex(List.of(S1118));
    }

    @Test
    @DisplayName("RuleMetadataIndex looks up metadata by rule key")
    void indexLooksUpByKey() {
        RuleMetadataIndex index = index();

        assertEquals("java:S1118", index.lookup("java:S1118").ruleKey());
        assertNull(index.lookup("java:S9999"), "an unknown key resolves to null");
        assertNull(index.lookup(null), "a null key resolves to null");
    }

    @Test
    @DisplayName("an empty index resolves every key to null without failing")
    void emptyIndexDegradesGracefully() {
        assertNull(RuleMetadataIndex.empty().lookup("java:S1118"));
    }

    @Test
    @DisplayName("TextReporter shows a rule rationale/fix line for issues with metadata")
    void textShowsRationale() {
        String text = new TextReporter().render(RESPONSE, index());

        assertTrue(text.contains("Add a private constructor to hide the implicit public one."),
                "the how-to-fix guidance must appear in text output, got:\n" + text);
        // The unknown rule still renders, just without enrichment.
        assertTrue(text.contains("java:S9999"), "an issue with no metadata must still render");
    }

    @Test
    @DisplayName("JsonReporter attaches a rule object to enriched issues")
    void jsonAttachesRuleObject() throws Exception {
        String json = new JsonReporter().render(RESPONSE, index());

        JsonNode root = Json.mapper().readTree(json);
        JsonNode issues = root.get("files").get(0).get("issues");
        JsonNode knownRule = null;
        for (JsonNode issue : issues) {
            if ("java:S1118".equals(issue.get("ruleKey").asText())) {
                knownRule = issue.get("rule");
            }
        }
        assertNotNull(knownRule, "an enriched issue must carry a 'rule' object");
        assertEquals("Utility classes should not have public constructors",
                knownRule.get("name").asText(), "the rule name must be attached");
        assertTrue(knownRule.has("howToFix"), "the rule object must carry the fix guidance");
    }

    @Test
    @DisplayName("SarifReporter populates tool.driver.rules[] with fullDescription and help")
    void sarifPopulatesDriverRules() throws Exception {
        String sarif = new SarifReporter().render(RESPONSE, index());

        JsonNode rules = Json.mapper().readTree(sarif)
                .get("runs").get(0).get("tool").get("driver").get("rules");
        JsonNode s1118 = null;
        for (JsonNode rule : rules) {
            if ("java:S1118".equals(rule.get("id").asText())) {
                s1118 = rule;
            }
        }
        assertNotNull(s1118, "the driver rules must include java:S1118");
        assertEquals("<p>Utility classes should not be instantiated.</p>",
                s1118.get("fullDescription").get("text").asText(),
                "the rule's fullDescription must come from the metadata");
        assertNotNull(s1118.get("help"), "the rule must carry help (how-to-fix)");
    }

    @Test
    @DisplayName("reporters render unchanged when given an empty index")
    void reportersWorkWithEmptyIndex() throws Exception {
        String json = new JsonReporter().render(RESPONSE, RuleMetadataIndex.empty());
        String sarif = new SarifReporter().render(RESPONSE, RuleMetadataIndex.empty());
        String text = new TextReporter().render(RESPONSE, RuleMetadataIndex.empty());

        assertNotNull(Json.mapper().readTree(json), "JSON must still parse with no metadata");
        assertNotNull(Json.mapper().readTree(sarif), "SARIF must still parse with no metadata");
        assertTrue(text.contains("java:S1118"), "text must still render with no metadata");
    }
}
