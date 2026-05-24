package io.github.randomcodespace.sonarpredict.cli.setup;

/**
 * Thrown when a {@link Downloader} fetch fails — an HTTP error, an I/O fault,
 * or a SHA-256 checksum mismatch. A failed fetch always leaves no partial file.
 */
public class DownloadException extends RuntimeException {

    /** @param message the failure detail */
    public DownloadException(String message) {
        super(message);
    }

    /**
     * @param message the failure detail
     * @param cause   the underlying fault
     */
    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
