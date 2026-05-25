package io.github.randomcodespace.sonarpredict.protocol.dto;

import java.util.List;

/**
 * An analyzer-supplied, machine-applicable remediation for an {@link Issue}.
 *
 * <p>A quick fix carries a human-readable {@code message} describing the change
 * and a list of {@link FileEdit}s — usually one file, but multi-file edits are
 * permitted by the SonarSource analyzer contract.
 *
 * <p>Not every issue has quick fixes — rule coverage is uneven across
 * analyzers. {@link Issue#quickFixes()} returns an empty list when none are
 * attached, so consumers can iterate uniformly.
 *
 * @param message    short, imperative remediation description (e.g.
 *                   "Add a private constructor")
 * @param fileEdits  per-file edits this fix applies; never {@code null}
 */
public record QuickFix(
        String message,
        List<FileEdit> fileEdits
) {
    public QuickFix {
        fileEdits = fileEdits == null ? List.of() : List.copyOf(fileEdits);
    }
}
