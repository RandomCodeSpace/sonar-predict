package dev.sonarcli.protocol.dto;

import java.util.List;

/**
 * Request payload for {@code Method.ANALYZE}.
 *
 * @param baseDir         absolute path the {@code files} are relative to
 * @param files           file paths to analyze, relative to {@code baseDir}
 * @param languageHints   optional language keys; empty means auto-detect
 * @param profileRef      path to a SonarQube quality-profile XML, or {@code null}
 *                        to use each analyzer's default (SonarWay) profile
 * @param coverageReports paths to coverage reports to import; may be empty
 */
public record AnalyzeRequest(
        String baseDir,
        List<String> files,
        List<String> languageHints,
        String profileRef,
        List<String> coverageReports
) {
}
