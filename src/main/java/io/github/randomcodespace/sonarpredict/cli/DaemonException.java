package io.github.randomcodespace.sonarpredict.cli;

/**
 * Thrown when an RPC to the daemon fails: a socket-level failure, a malformed
 * response, or an {@code {"error": "..."}} payload returned by the daemon.
 *
 * <p>This is the CLI's single "tool error" signal — a command catching it maps
 * it to exit code 2 with a clear stderr message, distinct from "issues found".
 */
public class DaemonException extends RuntimeException {

    public DaemonException(String message) {
        super(message);
    }

    public DaemonException(String message, Throwable cause) {
        super(message, cause);
    }
}
