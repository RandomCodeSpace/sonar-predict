package io.github.randomcodespace.sonarpredict.protocol.dto;

import java.util.List;

/**
 * Request payload for {@code Method.ANALYZE}.
 *
 * @param baseDir             absolute path the {@code files} are relative to
 * @param files               file paths to analyze, relative to {@code baseDir}
 * @param languageHints       optional language keys; empty means auto-detect
 * @param profileRef          path to a SonarQube quality-profile XML, or
 *                            {@code null} to use each analyzer's default
 *                            (SonarWay) profile
 * @param coverageReports     paths to coverage reports to import; may be empty
 * @param additionalTestPaths glob patterns (relative to {@code baseDir}) that
 *                            mark extra paths as test code on top of the
 *                            built-in language-aware test-path detection;
 *                            useful when an agent/skill knows the project's
 *                            test layout is non-standard. May be empty.
 */
public record AnalyzeRequest(
        String baseDir,
        List<String> files,
        List<String> languageHints,
        String profileRef,
        List<String> coverageReports,
        List<String> additionalTestPaths
) {
    /**
     * Backward-compatible 5-arg constructor — callers that don't override
     * test-path classification fall through to the built-in detector only.
     */
    public AnalyzeRequest(String baseDir, List<String> files, List<String> languageHints,
                          String profileRef, List<String> coverageReports) {
        this(baseDir, files, languageHints, profileRef, coverageReports, List.of());
    }
}
