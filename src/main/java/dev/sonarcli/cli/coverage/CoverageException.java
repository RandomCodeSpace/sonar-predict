package dev.sonarcli.cli.coverage;

/**
 * Raised when a coverage report cannot be detected, read, or parsed. The CLI
 * maps it to a tool error (exit code 2): a coverage import failure is never a
 * gate failure, it means the tool could not do its job.
 */
public final class CoverageException extends RuntimeException {

    /**
     * @param message a human-readable explanation, naming the offending file
     */
    public CoverageException(String message) {
        super(message);
    }

    /**
     * @param message a human-readable explanation, naming the offending file
     * @param cause   the underlying failure
     */
    public CoverageException(String message, Throwable cause) {
        super(message, cause);
    }
}
