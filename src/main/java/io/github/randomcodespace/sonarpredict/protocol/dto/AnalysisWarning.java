package io.github.randomcodespace.sonarpredict.protocol.dto;

/**
 * A non-fatal problem encountered during analysis (e.g. one unparseable file).
 *
 * @param filePath path relative to the base directory, or {@code null} for a
 *                 project-level warning not tied to a specific file
 */
public record AnalysisWarning(
        String filePath,
        String message
) {
}
