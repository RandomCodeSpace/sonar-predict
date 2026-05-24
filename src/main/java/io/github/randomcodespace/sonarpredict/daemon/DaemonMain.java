package io.github.randomcodespace.sonarpredict.daemon;

import java.time.Duration;

import io.github.randomcodespace.sonarpredict.protocol.SocketPaths;

/**
 * Process entry point for the sonar analysis daemon.
 *
 * <p>Resolves the {@link SocketPaths}, then delegates the whole lifecycle to
 * {@link Daemon#start}: pidfile, warm engine, socket server. A JVM shutdown
 * hook stops the daemon (removing the socket and pidfile) so {@code SIGTERM}
 * leaves no stale files. If a live daemon already owns the socket this process
 * exits cleanly without binding a second listener.
 *
 * <p>Packaged as a runnable jar (see {@code daemon/pom.xml}); the CLI plan
 * launches it as a detached child process.
 */
public final class DaemonMain {

    private DaemonMain() {
    }

    /**
     * Starts the daemon and blocks until it stops (via {@code SHUTDOWN}, idle
     * timeout, or process signal).
     *
     * @param args ignored in v1
     * @throws InterruptedException if the main thread is interrupted while
     *                              waiting for the daemon to stop
     */
    public static void main(String[] args) throws InterruptedException {
        SocketPaths paths = SocketPaths.resolve();
        Daemon daemon = Daemon.start(paths, DaemonServer.DEFAULT_IDLE_TIMEOUT);

        if (!daemon.startedNewListener()) {
            // Another live daemon already owns the socket — nothing to do.
            return;
        }

        Runtime.getRuntime().addShutdownHook(
                new Thread(daemon::stop, "daemon-shutdown-hook"));

        daemon.awaitListening();
        daemon.awaitStopped();
    }
}
