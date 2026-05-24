package dev.sonarcli.protocol.dto;

import java.util.List;

/**
 * Response payload for {@code Method.ANALYZE}.
 *
 * <p>Plan 6 (coverage import) adds a coverage-summary component to this record.
 */
public record AnalyzeResponse(
        List<Issue> issues,
        List<AnalysisWarning> warnings
) {
}
