package dev.sonarcli.daemon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;

import dev.sonarcli.protocol.SocketPaths;

/**
 * Orchestrates the daemon's lifecycle: pidfile, warm {@link AnalysisService},
 * {@link RequestDispatcher}, and {@link DaemonServer}.
 *
 * <p><b>Atomic startup.</b> {@link #start} first acquires an exclusive OS file
 * lock on {@link SocketPaths#lockFile()} via {@link FileChannel#tryLock()}.
 * Only the holder of that lock proceeds to the live-daemon check, engine
 * construction, and socket bind; the lock is held for the daemon's whole
 * lifetime and released by {@link #stop}. A second starter — in this JVM or
 * another process — that cannot acquire the lock returns a no-op handle
 * ({@link #startedNewListener()} {@code == false}) and binds nothing. This
 * closes the race where two concurrent starters both passed the live-daemon
 * check, both built engines, both overwrote the pidfile, and either could
 * unlink the other's socket.
 *
 * <p><b>Single daemon per machine.</b> Under the lock, {@link #start} checks
 * whether a <em>live</em> daemon already owns the pidfile and socket; if so it
 * returns a no-op handle and binds nothing. Otherwise it claims the pidfile,
 * builds the warm engine, and starts the socket server. {@link #stop} on a real
 * listener removes the pidfile and socket, closes the engine, and releases the
 * startup lock; on a no-op handle it does nothing.
 *
 * <p>A "live" daemon means: the pidfile exists, holds a parseable PID, that
 * process is still running ({@link ProcessHandle#of}), and the socket file
 * exists. A stale pidfile (process gone) is silently overwritten.
 */
public final class Daemon {

    private final SocketPaths paths;
    private final boolean startedNewListener;
    private final AnalysisService analysisService; // null on a no-op handle
    private final DaemonServer server;             // null on a no-op handle
    private final FileChannel lockChannel;         // null on a no-op handle
    private final FileLock startupLock;            // null on a no-op handle

    private Daemon(
            SocketPaths paths,
            boolean startedNewListener,
            AnalysisService analysisService,
            DaemonServer server,
            FileChannel lockChannel,
            FileLock startupLock) {
        this.paths = paths;
        this.startedNewListener = startedNewListener;
        this.analysisService = analysisService;
        this.server = server;
        this.lockChannel = lockChannel;
        this.startupLock = startupLock;
    }

    /**
     * Starts the daemon, or returns a no-op handle if a live daemon already
     * owns {@code paths}.
     *
     * @param paths       the socket/pidfile locations
     * @param idleTimeout idle window before the daemon self-stops
     * @return a daemon handle; check {@link #startedNewListener()}
     */
    public static Daemon start(SocketPaths paths, Duration idleTimeout) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(idleTimeout, "idleTimeout");

        // Acquire the startup lock BEFORE the live-daemon check, engine
        // construction, and socket bind. Holding it serializes startup, so a
        // second starter cannot race past the check and unlink a live socket.
        FileChannel lockChannel = openLockChannel(paths.lockFile());
        FileLock startupLock = tryAcquire(lockChannel);
        if (startupLock == null) {
            // Another daemon owns startup (this JVM or another process). Close
            // our channel and return a no-op handle; touch nothing else.
            closeQuietly(lockChannel);
            return new Daemon(paths, false, null, null, null, null);
        }

        // From here the lock is held: the live-daemon check is race-free.
        if (liveDaemonExists(paths)) {
            releaseLock(startupLock, lockChannel);
            return new Daemon(paths, false, null, null, null, null);
        }

        // The warm engine and pidfile are acquired before the socket bind; if
        // anything from here to a successful server.start() throws, they must
        // all be torn down again — see the catch block. Tracked in locals so
        // the failure path can reach exactly what was created.
        AnalysisService analysisService = null;
        boolean pidFileWritten = false;
        try {
            analysisService = new AnalysisService();
            DaemonServer server = new DaemonServer(paths.socket(), null, idleTimeout);

            final AnalysisService engine = analysisService;
            RequestDispatcher dispatcher = new RequestDispatcher(
                    engine, engine.ruleCatalog(), server::stop);
            server.setDispatcher(dispatcher::dispatch);

            // Every stop path — a SHUTDOWN request, the idle timeout, or a JVM
            // shutdown hook — routes through DaemonServer.stop(), which runs
            // this onStop callback before releasing awaitStopped(). The
            // callback closes the warm engine (deleting its work directory),
            // removes the pidfile, and releases the startup lock, so no exit
            // path can leave any of them behind or strand the lock held.
            server.setOnStop(() -> {
                engine.close();
                try {
                    Files.deleteIfExists(paths.pidFile());
                } catch (IOException ignored) {
                    // Best effort — a stale pidfile is detected and overwritten
                    // on the next start().
                }
                releaseLock(startupLock, lockChannel);
            });

            writePidFile(paths.pidFile());
            pidFileWritten = true;
            server.start();
            return new Daemon(
                    paths, true, engine, server, lockChannel, startupLock);
        } catch (RuntimeException startupFailure) {
            // Engine construction, the pidfile write, or the socket bind
            // failed. Every resource already acquired must be reclaimed: the
            // warm engine (its scheduler thread + work directory), the pidfile
            // and any partial socket file, and the startup lock — otherwise no
            // daemon could ever start again.
            if (analysisService != null) {
                try {
                    analysisService.close();
                } catch (RuntimeException ignored) {
                    // Best effort — do not mask the original startup failure.
                }
            }
            if (pidFileWritten) {
                deleteQuietly(paths.pidFile());
            }
            deleteQuietly(paths.socket());
            releaseLock(startupLock, lockChannel);
            throw startupFailure;
        }
    }

    /** Best-effort delete of a path; failures are swallowed. */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort — a leftover is detected and overwritten on the next
            // start(); a startup failure must not be masked by a cleanup fault.
        }
    }

    /** Whether this handle owns a real listening server (vs a no-op handle). */
    public boolean startedNewListener() {
        return startedNewListener;
    }

    /** Blocks until the socket is bound; no-op on a non-listener handle. */
    public void awaitListening() throws InterruptedException {
        if (server != null) {
            server.awaitListening();
        }
    }

    /** Blocks until the server has stopped; no-op on a non-listener handle. */
    public void awaitStopped() throws InterruptedException {
        if (server != null) {
            server.awaitStopped();
        }
    }

    /**
     * Stops the daemon: the socket server, the warm engine (and its work
     * directory), and the pidfile. Idempotent — {@link DaemonServer#stop}
     * collapses repeat calls and runs the engine/pidfile teardown exactly
     * once. A no-op on a non-listener handle.
     */
    public void stop() {
        if (!startedNewListener) {
            return;
        }
        // server.stop() runs the onStop callback wired in start() — closing
        // the engine and removing the pidfile — before awaitStopped() unblocks.
        server.stop();
    }

    /**
     * The warm engine's work directory, for tests asserting lifecycle cleanup.
     *
     * @return the work directory path, or {@code null} on a non-listener handle
     */
    Path workDir() {
        return analysisService != null ? analysisService.workDir() : null;
    }

    private static boolean liveDaemonExists(SocketPaths paths) {
        Path pidFile = paths.pidFile();
        if (!Files.exists(pidFile) || !Files.exists(paths.socket())) {
            return false;
        }
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            return ProcessHandle.of(pid).isPresent();
        } catch (IOException | NumberFormatException e) {
            // Unreadable or garbage pidfile — treat as no live daemon.
            return false;
        }
    }

    private static void writePidFile(Path pidFile) {
        try {
            Files.writeString(
                    pidFile, Long.toString(ProcessHandle.current().pid()));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write pidfile: " + pidFile, e);
        }
    }

    /** Opens (creating if absent) the lock file's channel for locking. */
    private static FileChannel openLockChannel(Path lockFile) {
        try {
            return FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not open the daemon startup lock file: " + lockFile, e);
        }
    }

    /**
     * Tries to take the exclusive startup lock without blocking.
     *
     * @return the acquired {@link FileLock}, or {@code null} if another holder
     *         owns it — another process ({@code tryLock} returns {@code null})
     *         or this same JVM ({@link OverlappingFileLockException})
     */
    private static FileLock tryAcquire(FileChannel lockChannel) {
        try {
            return lockChannel.tryLock();
        } catch (OverlappingFileLockException alreadyHeldInThisJvm) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("could not acquire the daemon startup lock", e);
        }
    }

    /** Releases the startup lock and closes its channel; best effort. */
    private static void releaseLock(FileLock lock, FileChannel channel) {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // Releasing the OS lock is best effort: closing the channel below
            // also drops it, and process exit drops it unconditionally.
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best effort — the OS reclaims the descriptor on process exit.
        }
    }
}
