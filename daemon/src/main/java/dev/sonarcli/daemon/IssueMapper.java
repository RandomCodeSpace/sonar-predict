package dev.sonarcli.daemon;

import java.nio.file.Path;

import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.analysis.api.Issue;
import org.sonarsource.sonarlint.core.commons.api.TextRange;

import dev.sonarcli.protocol.dto.RuleMetadata;

/**
 * Maps an engine {@link Issue} to the protocol {@link dev.sonarcli.protocol.dto.Issue}.
 *
 * <p><b>Engine-API note:</b> the analysis-engine {@code Issue} (engine
 * 10.24.0.81415) exposes only {@code getRuleKey}, {@code getMessage},
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
    public static dev.sonarcli.protocol.dto.Issue toDto(
            Issue engineIssue, Path baseDir, RuleCatalog catalog) {
        return map(
                engineIssue.getRuleKey(),
                resolveFilePath(engineIssue.getInputFile(), baseDir),
                engineIssue.getTextRange(),
                engineIssue.getMessage(),
                catalog);
    }

    /**
     * Pure mapping from primitive issue fields to a protocol DTO. A {@code null}
     * {@code range} (file-level issue) yields zero positions. Severity and type
     * are resolved from {@code catalog} by {@code ruleKey}, falling back to
     * {@link #DEFAULT_SEVERITY}/{@link #DEFAULT_TYPE} for unknown rules.
     */
    static dev.sonarcli.protocol.dto.Issue map(
            String ruleKey, String filePath, TextRange range, String message,
            RuleCatalog catalog) {
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

        return new dev.sonarcli.protocol.dto.Issue(
                ruleKey,
                filePath,
                startLine,
                startColumn,
                endLine,
                endColumn,
                severity,
                type,
                message);
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
