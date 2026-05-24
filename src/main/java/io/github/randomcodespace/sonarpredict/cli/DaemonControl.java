package io.github.randomcodespace.sonarpredict.cli;

/**
 * The daemon-process lifecycle surface the {@code daemon} command depends on,
 * kept separate from {@link DaemonRpc} so the command can be tested with a
 * stub. {@link LauncherDaemonControl} is the real implementation backed by
 * {@link DaemonLauncher} and a socket {@code SHUTDOWN}.
 */
public interface DaemonControl {

    /** Whether the daemon is currently running. */
    boolean isRunning();

    /** Starts the daemon if it is not already running. */
    void start();

    /**
     * Stops the daemon if it is running and reports whether it actually
     * stopped.
     *
     * @return {@code true} if the daemon is confirmed gone (it stopped within
     *         the stop deadline, or was not running to begin with);
     *         {@code false} if the shutdown request failed or the daemon was
     *         still present when the deadline expired
     */
    boolean stop();
}
