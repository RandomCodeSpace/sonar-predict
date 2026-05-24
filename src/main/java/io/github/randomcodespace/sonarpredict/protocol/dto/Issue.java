package io.github.randomcodespace.sonarpredict.protocol.dto;

/**
 * A single analysis finding.
 *
 * @param filePath path relative to the analysis base directory
 * @param severity one of BLOCKER, CRITICAL, MAJOR, MINOR, INFO
 * @param type     one of BUG, CODE_SMELL, VULNERABILITY, SECURITY_HOTSPOT
 */
public record Issue(
        String ruleKey,
        String filePath,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String severity,
        String type,
        String message
) {
}
