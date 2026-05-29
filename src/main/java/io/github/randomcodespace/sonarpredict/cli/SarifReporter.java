package io.github.randomcodespace.sonarpredict.cli;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.randomcodespace.sonarpredict.cli.coverage.CoverageReport;
import io.github.randomcodespace.sonarpredict.cli.coverage.FileCoverage;
import io.github.randomcodespace.sonarpredict.protocol.Json;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.Issue;

/**
 * Renders an {@link AnalyzeResponse} as a SARIF 2.1.0 log
 * (Static Analysis Results Interchange Format), the default {@code sonar check}
 * output. The log carries one {@code run} whose {@code tool.driver} is
 * {@code sonar-predictor}; every issue becomes one {@code result} with a
 * {@code ruleId}, a severity-mapped {@code level}, a {@code message.text}, and a
 * {@code physicalLocation} (artifact URI + region). Distinct rule keys are
 * collected into {@code tool.driver.rules[]}.
 *
 * <p>Output parses as JSON via {@link Json#mapper()}; field order is fixed so
 * the same response always renders identically.
 */
public final class SarifReporter implements Reporter {

    /** SARIF 2.1.0 JSON-schema URI. */
    private static final String SARIF_SCHEMA =
            "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String SARIF_VERSION = "2.1.0";
    private static final String SARIF_LEVEL_WARNING = "warning";

    @Override
    public String render(AnalyzeResponse response, RuleMetadataIndex index,
                         CoverageReport coverage) {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("$schema", SARIF_SCHEMA);
        root.put("version", SARIF_VERSION);

        ArrayNode runs = root.putArray("runs");
        ObjectNode run = runs.addObject();

        ObjectNode tool = run.putObject("tool");
        ObjectNode driver = tool.putObject("driver");
        driver.put("name", "sonar-predictor");
        driver.put("informationUri", "https://github.com/RandomCodeSpace/sonar-predict");
        driver.put("version", SonarVersionProvider.version());

        // Distinct rule keys, in first-seen order, become driver.rules[].
        Set<String> ruleKeys = new LinkedHashSet<>();
        for (Issue issue : response.issues()) {
            ruleKeys.add(issue.ruleKey());
        }
        ArrayNode rules = driver.putArray("rules");
        for (String ruleKey : ruleKeys) {
            rules.add(ruleNode(ruleKey, index));
        }

        ArrayNode results = run.putArray("results");
        for (Issue issue : response.issues()) {
            results.add(resultNode(issue));
        }

        // Coverage is not a SARIF "result"; it rides in run-level properties.
        if (coverage != null) {
            run.set("properties", coverageProperties(coverage));
        }

        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize the SARIF report", e);
        }
    }

    /**
     * Builds one {@code tool.driver.rules[]} entry for a rule key, populating
     * {@code shortDescription}/{@code fullDescription}/{@code help} when the
     * index carries metadata for the key.
     */
    private static ObjectNode ruleNode(String ruleKey, RuleMetadataIndex index) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("id", ruleKey);
        var metadata = index.lookup(ruleKey);
        if (metadata != null) {
            if (metadata.name() != null && !metadata.name().isBlank()) {
                node.put("name", metadata.name());
                node.putObject("shortDescription").put("text", metadata.name());
            }
            if (metadata.descriptionHtml() != null && !metadata.descriptionHtml().isBlank()) {
                node.putObject("fullDescription").put("text", metadata.descriptionHtml());
            }
            if (metadata.howToFix() != null && !metadata.howToFix().isBlank()) {
                node.putObject("help").put("text", metadata.howToFix());
            }
        }
        return node;
    }

    /**
     * Builds the run-level {@code properties} bag carrying imported coverage:
     * {@code coverage.overallPercent} and a {@code coverage.files} array.
     */
    private static ObjectNode coverageProperties(CoverageReport coverage) {
        ObjectNode properties = Json.mapper().createObjectNode();
        ObjectNode node = properties.putObject("coverage");
        node.put("overallPercent",
                Math.round(coverage.overallPercent() * 100.0) / 100.0);
        ArrayNode files = node.putArray("files");
        for (FileCoverage file : coverage.files()) {
            ObjectNode fileNode = files.addObject();
            fileNode.put("file", file.path());
            fileNode.put("coveredLines", file.coveredLines());
            fileNode.put("coverableLines", file.coverableLines());
            fileNode.put("percent", Math.round(file.percent() * 100.0) / 100.0);
        }
        return properties;
    }

    /** Builds one {@code results[]} entry for an issue. */
    private static ObjectNode resultNode(Issue issue) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("ruleId", issue.ruleKey());
        node.put("level", level(issue.severity()));
        node.putObject("message").put("text", issue.message());

        ArrayNode locations = node.putArray("locations");
        ObjectNode location = locations.addObject();
        ObjectNode physical = location.putObject("physicalLocation");
        physical.putObject("artifactLocation").put("uri", issue.filePath());

        ObjectNode region = physical.putObject("region");
        region.put("startLine", clampLine(issue.startLine()));
        region.put("startColumn", clampColumn(issue.startColumn()));
        region.put("endLine", clampLine(issue.endLine()));
        region.put("endColumn", clampColumn(issue.endColumn()));
        return node;
    }

    /** Maps an engine severity to a SARIF result level. */
    private static String level(String severity) {
        if (severity == null) {
            return SARIF_LEVEL_WARNING;
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case "BLOCKER", "CRITICAL" -> "error";
            case "MAJOR" -> SARIF_LEVEL_WARNING;
            case "MINOR", "INFO" -> "note";
            default -> SARIF_LEVEL_WARNING;
        };
    }

    /** SARIF line numbers are 1-based; the engine never reports below 1. */
    private static int clampLine(int line) {
        return Math.max(1, line);
    }

    /** SARIF column numbers are 1-based; the engine is 0-based, so shift by one. */
    private static int clampColumn(int column) {
        return Math.max(1, column + 1);
    }
}
