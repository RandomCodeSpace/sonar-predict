package io.github.randomcodespace.sonarpredict.protocol.dto;

/**
 * A single replacement edit inside one file — the smallest unit of a {@link QuickFix}.
 *
 * <p>Positions are 1-indexed lines and 0-indexed columns to match the convention
 * used by {@link Issue#startLine}/{@link Issue#startColumn} so consumers that
 * already render issue positions can render edits the same way.
 *
 * @param startLine    1-indexed inclusive start line
 * @param startColumn  0-indexed inclusive start column on {@code startLine}
 * @param endLine      1-indexed inclusive end line
 * @param endColumn    0-indexed exclusive end column on {@code endLine}
 * @param replacement  the text to substitute for the range; the empty string
 *                     means "delete the range"
 */
public record TextEdit(
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String replacement
) {
}
