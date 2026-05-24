package io.github.randomcodespace.sonarpredict.cli;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Objects;

import io.github.randomcodespace.sonarpredict.protocol.SocketPaths;

/**
 * Real {@link DaemonControl}: {@code start} delegates to {@link DaemonLauncher},
 * {@code stop} sends a {@code SHUTDOWN} over the socket via {@link DaemonClient}
 * and waits for the socket file to disappear, {@code isRunning} probes the
 * socket.
 */
public final class LauncherDaemonControl implements DaemonControl {

    /** Bounded wait for the socket file to vanish after a stop request. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final SocketPaths paths;
    private final DaemonLauncher launcher;

    public LauncherDaemonControl(SocketPaths paths, DaemonLauncher launcher) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    @Override
    public boolean isRunning() {
        return launcher.isDaemonRunning();
    }

    @Override
    public void start() {
        launcher.start();
    }

    @Override
    public boolean stop() {
        if (!launcher.isDaemonRunning()) {
            return true; // already stopped — nothing to do.
        }
        try {
            new DaemonClient(paths, launcher).shutdown();
        } catch (RuntimeException shutdownFailed) {
            // The SHUTDOWN RPC itself failed — the daemon may still be running.
            return false;
        }
        long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
        while (Files.exists(paths.socket()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        // Confirmed stopped only when the socket file is actually gone.
        return Files.notExists(paths.socket());
    }
}
