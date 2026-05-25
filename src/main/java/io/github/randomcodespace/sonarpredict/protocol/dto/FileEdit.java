package io.github.randomcodespace.sonarpredict.protocol.dto;

import java.util.List;

/**
 * All {@link TextEdit}s a {@link QuickFix} applies to one specific file.
 *
 * <p>The compact constructor null-normalises and defensively copies {@code edits}
 * so the record stays immutable regardless of how callers (Jackson, tests,
 * direct construction) hand it in.
 *
 * @param filePath path relative to the analysis base directory, matching the
 *                 convention used by {@link Issue#filePath}
 * @param edits    the in-file edits to apply; never {@code null}
 */
public record FileEdit(
        String filePath,
        List<TextEdit> edits
) {
    public FileEdit {
        edits = edits == null ? List.of() : List.copyOf(edits);
    }
}
