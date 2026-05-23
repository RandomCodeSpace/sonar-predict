package dev.sonarcli.cli;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.sonarcli.cli.coverage.CoverageReport;
import dev.sonarcli.cli.coverage.FileCoverage;
import dev.sonarcli.protocol.dto.AnalysisWarning;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.Issue;
import dev.sonarcli.protocol.dto.RuleMetadata;

/**
 * Renders an {@link AnalyzeResponse} as a human-readable report: issues grouped
 * under a per-file header, each line carrying the location, severity, rule key,
 * and message. When the {@link RuleMetadataIndex} carries metadata for a rule,
 * an indented rationale/fix line is added beneath the issue. A clean response
 * is stated plainly; analysis warnings are listed in their own trailing section.
 */
public final class TextReporter implements Reporter {

    @Override
    public String render(AnalyzeResponse response, RuleMetadataIndex index,
                         CoverageReport coverage) {
        StringBuilder out = new StringBuilder();
        Map<String, List<Issue>> byFile = IssueGrouping.byFile(response.issues());

        if (byFile.isEmpty()) {
            out.append("No issues found.\n");
        } else {
            appendIssues(out, byFile, index, response.issues().size());
        }

        appendWarnings(out, response.warnings());

        if (coverage != null) {
            appendCoverage(out, coverage);
        }
        return out.toString();
    }

    /** Renders every per-file issue block and the trailing summary line. */
    private static void appendIssues(StringBuilder out,
                                     Map<String, List<Issue>> byFile,
                                     RuleMetadataIndex index, int totalCount) {
        for (Map.Entry<String, List<Issue>> entry : byFile.entrySet()) {
            out.append(entry.getKey()).append('\n');
            for (Issue issue : entry.getValue()) {
                appendIssue(out, issue, index);
            }
        }
        out.append('\n')
                .append(totalCount).append(totalCount == 1 ? " issue" : " issues")
                .append(" in ").append(byFile.size())
                .append(byFile.size() == 1 ? " file.\n" : " files.\n");
    }

    /** Renders one issue line plus any indented rule guidance beneath it. */
    private static void appendIssue(StringBuilder out, Issue issue,
                                    RuleMetadataIndex index) {
        out.append("  ")
                .append(issue.startLine()).append(':').append(issue.startColumn())
                .append("  ").append(issue.severity())
                .append("  ").append(issue.ruleKey())
                .append("  ").append(issue.message())
                .append('\n');
        appendRuleGuidance(out, index.lookup(issue.ruleKey()));
    }

    /** Renders the analysis-warnings section, if there are any. */
    private static void appendWarnings(StringBuilder out, List<AnalysisWarning> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        out.append('\n').append("Warnings:\n");
        for (AnalysisWarning warning : warnings) {
            out.append("  ");
            if (warning.filePath() != null && !warning.filePath().isBlank()) {
                out.append(warning.filePath()).append(": ");
            }
            out.append(warning.message()).append('\n');
        }
    }

    /**
     * Appends a coverage summary: the overall percentage followed by a
     * per-file breakdown, each line {@code <pct>  <path>}.
     */
    private static void appendCoverage(StringBuilder out, CoverageReport coverage) {
        out.append('\n').append("Coverage: ")
                .append(formatPercent(coverage.overallPercent()))
                .append(" overall");
        List<FileCoverage> files = coverage.files();
        if (files.isEmpty()) {
            out.append(" (no files reported).\n");
            return;
        }
        out.append('\n');
        for (FileCoverage file : files) {
            out.append("  ").append(formatPercent(file.percent()))
                    .append("  ").append(file.path()).append('\n');
        }
    }

    /** Formats a percentage to one decimal place with a trailing {@code %}. */
    private static String formatPercent(double percent) {
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    /**
     * Appends an indented rule rationale and fix line beneath an issue, when
     * the metadata is present. The how-to-fix guidance is preferred; if the
     * rule provides none, a short plain-text rationale from the description is
     * shown instead.
     */
    private static void appendRuleGuidance(StringBuilder out, RuleMetadata metadata) {
        if (metadata == null) {
            return;
        }
        if (metadata.name() != null && !metadata.name().isBlank()) {
            out.append("      rule: ").append(metadata.name()).append('\n');
        }
        String guidance = metadata.howToFix() != null && !metadata.howToFix().isBlank()
                ? metadata.howToFix()
                : rationaleFrom(metadata.descriptionHtml());
        if (guidance != null && !guidance.isBlank()) {
            out.append("      fix:  ").append(guidance).append('\n');
        }
    }

    /** Strips HTML tags and trims the description to a single short sentence. */
    private static String rationaleFrom(String descriptionHtml) {
        if (descriptionHtml == null || descriptionHtml.isBlank()) {
            return null;
        }
        String plain = descriptionHtml
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .strip();
        int dot = plain.indexOf(". ");
        if (dot > 0 && dot < 200) {
            return plain.substring(0, dot + 1);
        }
        return plain.length() > 200 ? plain.substring(0, 200) + "…" : plain;
    }
}
