package io.github.randomcodespace.sonarpredict.protocol.dto;

import java.util.List;

/**
 * A single analysis finding.
 *
 * @param ruleKey    rule identifier, e.g. {@code java:S1118}
 * @param filePath   path relative to the analysis base directory
 * @param startLine  1-indexed inclusive
 * @param startColumn 0-indexed inclusive
 * @param endLine    1-indexed inclusive
 * @param endColumn  0-indexed exclusive
 * @param severity   one of BLOCKER, CRITICAL, MAJOR, MINOR, INFO
 * @param type       one of BUG, CODE_SMELL, VULNERABILITY, SECURITY_HOTSPOT
 * @param message    human-readable, often imperative ("Use isEmpty() to check…")
 * @param quickFixes analyzer-supplied machine-applicable remediations; never
 *                   {@code null} — empty list when none are attached
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
        String message,
        List<QuickFix> quickFixes
) {
    public Issue {
        quickFixes = quickFixes == null ? List.of() : List.copyOf(quickFixes);
    }

    /**
     * Backwards-compatible 9-arg constructor used by call sites that pre-date
     * the {@code quickFixes} field. Defaults the new field to an empty list.
     */
    public Issue(
            String ruleKey,
            String filePath,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn,
            String severity,
            String type,
            String message) {
        this(ruleKey, filePath, startLine, startColumn, endLine, endColumn,
                severity, type, message, List.of());
    }
}
