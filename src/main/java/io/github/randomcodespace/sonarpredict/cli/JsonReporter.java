package io.github.randomcodespace.sonarpredict.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.randomcodespace.sonarpredict.cli.coverage.CoverageReport;
import io.github.randomcodespace.sonarpredict.cli.coverage.FileCoverage;
import io.github.randomcodespace.sonarpredict.protocol.Json;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalysisWarning;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.FileEdit;
import io.github.randomcodespace.sonarpredict.protocol.dto.Issue;
import io.github.randomcodespace.sonarpredict.protocol.dto.QuickFix;
import io.github.randomcodespace.sonarpredict.protocol.dto.RuleMetadata;
import io.github.randomcodespace.sonarpredict.protocol.dto.TextEdit;

/**
 * Renders an {@link AnalyzeResponse} as compact, single-line JSON, parseable by
 * {@link Json#mapper()}. Issues are grouped by file under a {@code files}
 * array; field order is fixed, so the same response always renders identically.
 * When the {@link RuleMetadataIndex} carries metadata for a rule, the issue
 * gains a nested {@code rule} object (name and fix guidance). The rule's full
 * HTML description is intentionally omitted from this format to keep it
 * token-lean for agent context windows; it lives in the SARIF output and in
 * {@code sonar rules show <ruleKey>}.
 *
 * <p>Shape:
 * <pre>{@code
 * {"issueCount":N,"files":[{"file":"...","issues":[{...}]}],"warnings":[{...}]}
 * }</pre>
 */
public final class JsonReporter implements Reporter {

    @Override
    public String render(AnalyzeResponse response, RuleMetadataIndex index,
                         CoverageReport coverage) {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("issueCount", response.issues().size());

        ArrayNode files = root.putArray("files");
        Map<String, List<Issue>> byFile = IssueGrouping.byFile(response.issues());
        for (Map.Entry<String, List<Issue>> entry : byFile.entrySet()) {
            ObjectNode fileNode = files.addObject();
            fileNode.put("file", entry.getKey());
            ArrayNode issues = fileNode.putArray("issues");
            for (Issue issue : entry.getValue()) {
                issues.add(issueNode(issue, index.lookup(issue.ruleKey())));
            }
        }

        ArrayNode warnings = root.putArray("warnings");
        List<AnalysisWarning> responseWarnings = response.warnings() != null
                ? response.warnings() : new ArrayList<>();
        for (AnalysisWarning warning : responseWarnings) {
            ObjectNode warningNode = warnings.addObject();
            warningNode.put("file", warning.filePath());
            warningNode.put("message", warning.message());
        }

        if (coverage != null) {
            root.set("coverage", coverageNode(coverage));
        }

        try {
            return Json.mapper().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize the JSON report", e);
        }
    }

    /**
     * Builds the {@code coverage} object: an {@code overallPercent} and a
     * {@code files} array of {@code {file, coveredLines, coverableLines,
     * percent}} entries, in report order.
     */
    private static ObjectNode coverageNode(CoverageReport coverage) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("overallPercent", round(coverage.overallPercent()));
        ArrayNode files = node.putArray("files");
        for (FileCoverage file : coverage.files()) {
            ObjectNode fileNode = files.addObject();
            fileNode.put("file", file.path());
            fileNode.put("coveredLines", file.coveredLines());
            fileNode.put("coverableLines", file.coverableLines());
            fileNode.put("percent", round(file.percent()));
        }
        return node;
    }

    /** Rounds a percentage to two decimals for stable, compact JSON output. */
    private static double round(double percent) {
        return Math.round(percent * 100.0) / 100.0;
    }

    /**
     * Builds one issue node with a fixed, deterministic field order. When
     * {@code metadata} is non-null the issue gains a nested {@code rule} object.
     * When the issue carries any analyzer-supplied {@link QuickFix}es, the
     * issue gains a {@code quickFixes} array; the field is omitted (rather
     * than emitted as {@code []}) to keep the wire format token-lean for
     * the common case where rules don't supply machine-applicable fixes.
     */
    private static ObjectNode issueNode(Issue issue, RuleMetadata metadata) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("ruleKey", issue.ruleKey());
        node.put("severity", issue.severity());
        node.put("type", issue.type());
        node.put("startLine", issue.startLine());
        node.put("startColumn", issue.startColumn());
        node.put("endLine", issue.endLine());
        node.put("endColumn", issue.endColumn());
        node.put("message", issue.message());
        if (metadata != null) {
            // Token-lean by design: the rule name and fix guidance, but not the
            // multi-KB HTML description (that is in SARIF and `rules show`).
            ObjectNode rule = node.putObject("rule");
            rule.put("name", metadata.name());
            rule.put("language", metadata.language());
            rule.put("howToFix", metadata.howToFix());
        }
        if (!issue.quickFixes().isEmpty()) {
            ArrayNode quickFixes = node.putArray("quickFixes");
            for (QuickFix qf : issue.quickFixes()) {
                quickFixes.add(quickFixNode(qf));
            }
        }
        return node;
    }

    /**
     * Builds one {@code {message, fileEdits:[{filePath, edits:[…]}]}} node
     * for an analyzer-supplied quick fix. Field order matches the DTO so
     * the emitted JSON can roundtrip through {@code Json.mapper().readValue}.
     */
    private static ObjectNode quickFixNode(QuickFix qf) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("message", qf.message());
        ArrayNode fileEdits = node.putArray("fileEdits");
        for (FileEdit fe : qf.fileEdits()) {
            ObjectNode feNode = fileEdits.addObject();
            feNode.put("filePath", fe.filePath());
            ArrayNode edits = feNode.putArray("edits");
            for (TextEdit te : fe.edits()) {
                ObjectNode teNode = edits.addObject();
                teNode.put("startLine", te.startLine());
                teNode.put("startColumn", te.startColumn());
                teNode.put("endLine", te.endLine());
                teNode.put("endColumn", te.endColumn());
                teNode.put("replacement", te.replacement());
            }
        }
        return node;
    }
}
