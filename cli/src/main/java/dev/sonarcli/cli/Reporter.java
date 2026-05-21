package dev.sonarcli.cli;

import dev.sonarcli.cli.coverage.CoverageReport;
import dev.sonarcli.protocol.dto.AnalyzeResponse;

/**
 * Renders an {@link AnalyzeResponse} to a string for display.
 *
 * <p>Implementations decide the format; all group issues by file. The CLI
 * selects one via {@code --format} ({@link SarifReporter} default,
 * {@link JsonReporter} for {@code json}, {@link TextReporter} for {@code text}).
 *
 * <p>Every reporter is given a {@link RuleMetadataIndex} so issue output can be
 * enriched with the rule's name, description, and fix guidance, and an optional
 * {@link CoverageReport} so the report can carry an imported coverage summary.
 * The shorter {@link #render(AnalyzeResponse, RuleMetadataIndex)} and
 * {@link #render(AnalyzeResponse)} overloads are conveniences that omit
 * coverage and, respectively, rule metadata.
 */
public interface Reporter {

    /**
     * Renders the response, enriching issues from the metadata index and
     * appending a coverage summary when one was imported.
     *
     * @param response the analysis result to render
     * @param index    rule metadata; missing entries degrade gracefully
     * @param coverage imported coverage to summarize, or {@code null} for none
     * @return the rendered report
     */
    String render(AnalyzeResponse response, RuleMetadataIndex index, CoverageReport coverage);

    /**
     * Renders the response with no coverage summary.
     *
     * @param response the analysis result to render
     * @param index    rule metadata; missing entries degrade gracefully
     * @return the rendered report
     */
    default String render(AnalyzeResponse response, RuleMetadataIndex index) {
        return render(response, index, null);
    }

    /**
     * Renders the response with no rule metadata and no coverage summary.
     *
     * @param response the analysis result to render
     * @return the rendered report
     */
    default String render(AnalyzeResponse response) {
        return render(response, RuleMetadataIndex.empty(), null);
    }
}
