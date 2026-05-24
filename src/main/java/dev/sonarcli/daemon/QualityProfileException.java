package dev.sonarcli.daemon;

/**
 * Thrown when a SonarQube quality-profile XML file cannot be read or parsed —
 * a missing file, malformed XML, or a structurally invalid profile.
 */
public final class QualityProfileException extends RuntimeException {

    /**
     * @param message a human-readable description of the failure
     */
    public QualityProfileException(String message) {
        super(message);
    }

    /**
     * @param message a human-readable description of the failure
     * @param cause   the underlying error
     */
    public QualityProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
