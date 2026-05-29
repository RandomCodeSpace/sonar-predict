package io.github.randomcodespace.sonarpredict.daemon;

import java.nio.file.Path;
import java.util.List;

import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFileEdit;
import org.sonarsource.sonarlint.core.analysis.api.Issue;
import org.sonarsource.sonarlint.core.commons.api.TextRange;

import io.github.randomcodespace.sonarpredict.protocol.dto.FileEdit;
import io.github.randomcodespace.sonarpredict.protocol.dto.QuickFix;
import io.github.randomcodespace.sonarpredict.protocol.dto.RuleMetadata;
import io.github.randomcodespace.sonarpredict.protocol.dto.TextEdit;

/**
 * Maps an engine {@link Issue} to the protocol {@link io.github.randomcodespace.sonarpredict.protocol.dto.Issue}.
 *
 * <p><b>Engine-API note:</b> the analysis-engine {@code Issue} (engine
 * 11.3.0.85510) exposes only {@code getRuleKey}, {@code getMessage},
 * {@code getInputFile}, {@code getTextRange} and {@code getOverriddenImpacts}.
 * It carries <em>no</em> severity or type getter. Those are resolved here from
 * the {@link RuleCatalog}, which extracts them from the analyzer plugins'
 * bundled rule metadata. If a rule key is absent from the catalog, severity
 * and type fall back to {@code MAJOR}/{@code CODE_SMELL}.
 */
public final class IssueMapper {

    /** Fallback severity for a rule key the catalog does not know. */
    static final String DEFAULT_SEVERITY = "MAJOR";
    /** Fallback type for a rule key the catalog does not know. */
    static final String DEFAULT_TYPE = "CODE_SMELL";

    private IssueMapper() {
    }

    /**
     * Maps a live engine issue, resolving its file path relative to {@code baseDir}
     * and its severity/type via the {@code catalog}.
     *
     * @param engineIssue the engine-produced issue
     * @param baseDir     the analysis base directory
     * @param catalog     the rule-metadata catalog
     * @return the protocol DTO
     */
    public static io.github.randomcodespace.sonarpredict.protocol.dto.Issue toDto(
            Issue engineIssue, Path baseDir, RuleCatalog catalog) {
        return map(
                engineIssue.getRuleKey().toString(),
                resolveFilePath(engineIssue.getInputFile(), baseDir),
                engineIssue.getTextRange(),
                engineIssue.getMessage(),
                catalog,
                mapQuickFixes(engineIssue.quickFixes(), baseDir));
    }

    /**
     * Pure mapping from primitive issue fields to a protocol DTO with no quick
     * fixes. A {@code null} {@code range} (file-level issue) yields zero
     * positions. Severity and type are resolved from {@code catalog} by
     * {@code ruleKey}, falling back to {@link #DEFAULT_SEVERITY}/{@link #DEFAULT_TYPE}
     * for unknown rules.
     */
    static io.github.randomcodespace.sonarpredict.protocol.dto.Issue map(
            String ruleKey, String filePath, TextRange range, String message,
            RuleCatalog catalog) {
        return map(ruleKey, filePath, range, message, catalog, List.of());
    }

    /**
     * Pure mapping from primitive issue fields to a protocol DTO with the
     * supplied {@code quickFixes}. Used by {@link #toDto} after extracting
     * the engine's quick fixes; tests can call this overload directly to
     * exercise the full DTO shape without spinning up the engine.
     */
    static io.github.randomcodespace.sonarpredict.protocol.dto.Issue map(
            String ruleKey, String filePath, TextRange range, String message,
            RuleCatalog catalog, List<QuickFix> quickFixes) {
        int startLine = range != null ? range.getStartLine() : 0;
        int startColumn = range != null ? range.getStartLineOffset() : 0;
        int endLine = range != null ? range.getEndLine() : 0;
        int endColumn = range != null ? range.getEndLineOffset() : 0;

        String severity = DEFAULT_SEVERITY;
        String type = DEFAULT_TYPE;
        RuleMetadata metadata = catalog != null ? catalog.lookup(ruleKey) : null;
        if (metadata != null) {
            severity = metadata.severity();
            type = metadata.type();
        }

        return new io.github.randomcodespace.sonarpredict.protocol.dto.Issue(
                ruleKey,
                filePath,
                startLine,
                startColumn,
                endLine,
                endColumn,
                severity,
                type,
                message,
                quickFixes);
    }

    /**
     * Maps the engine's {@link org.sonarsource.sonarlint.core.analysis.api.QuickFix}
     * list to the protocol's {@link QuickFix} DTOs.
     *
     * <p>Engine {@code QuickFix.message()} is preserved verbatim. Each
     * {@link ClientInputFileEdit}'s target is path-resolved the same way the
     * primary issue's input file is (via {@link #resolveFilePath}), so quick-fix
     * targets share the {@code baseDir}-relative, '/'-separated convention with
     * {@link io.github.randomcodespace.sonarpredict.protocol.dto.Issue#filePath}.
     */
    static List<QuickFix> mapQuickFixes(
            List<org.sonarsource.sonarlint.core.analysis.api.QuickFix> engineQuickFixes,
            Path baseDir) {
        return engineQuickFixes.stream()
                .map(qf -> new QuickFix(
                        qf.message(),
                        qf.inputFileEdits().stream()
                                .map(ife -> new FileEdit(
                                        resolveFilePath(ife.target(), baseDir),
                                        ife.textEdits().stream()
                                                .map(te -> new TextEdit(
                                                        te.range().getStartLine(),
                                                        te.range().getStartLineOffset(),
                                                        te.range().getEndLine(),
                                                        te.range().getEndLineOffset(),
                                                        te.newText()))
                                                .toList()))
                                .toList()))
                .toList();
    }

    private static String resolveFilePath(ClientInputFile inputFile, Path baseDir) {
        if (inputFile == null) {
            return null;
        }
        // FileInputFile already exposes a baseDir-relative, '/'-separated path.
        String relative = inputFile.relativePath();
        if (relative != null && !relative.isBlank()) {
            return relative;
        }
        Path absolute = Path.of(inputFile.getPath()).toAbsolutePath().normalize();
        Path base = baseDir.toAbsolutePath().normalize();
        return base.relativize(absolute).toString().replace('\\', '/');
    }
}
