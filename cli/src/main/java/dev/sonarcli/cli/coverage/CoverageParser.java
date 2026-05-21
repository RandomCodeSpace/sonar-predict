package dev.sonarcli.cli.coverage;

import java.nio.file.Path;

/**
 * Parses one coverage-report file of a single {@link CoverageFormat} into a
 * {@link CoverageReport}. Implementations are stateless and reusable.
 */
public interface CoverageParser {

    /**
     * Parses a coverage report.
     *
     * @param path the report file, already known to match this parser's format
     * @return the per-file coverage it describes
     * @throws CoverageException if the file is unreadable or malformed; the
     *                           message names the offending file
     */
    CoverageReport parse(Path path);
}
