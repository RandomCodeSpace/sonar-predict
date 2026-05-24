package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.randomcodespace.sonarpredict.protocol.SocketPaths;

class DaemonMainTest {

    private static SocketPaths paths(Path dir) {
        return SocketPaths.resolve(Map.of("XDG_RUNTIME_DIR", dir.toString()));
    }

    @Test
    @DisplayName("starting writes a pidfile containing this process's PID")
    void startWritesPidFile(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        Daemon daemon = Daemon.start(paths, Duration.ofMinutes(10));
        try {
            daemon.awaitListening();
            assertTrue(Files.exists(paths.pidFile()), "pidfile must exist while running");
            long written = Long.parseLong(Files.readString(paths.pidFile()).trim());
            assertEquals(ProcessHandle.current().pid(), written,
                    "pidfile must contain this process's PID");
        } finally {
            daemon.stop();
            daemon.awaitStopped();
        }
    }

    @Test
    @DisplayName("stopping removes both the socket file and the pidfile")
    void stopRemovesSocketAndPidFile(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        Daemon daemon = Daemon.start(paths, Duration.ofMinutes(10));
        daemon.awaitListening();
        assertTrue(Files.exists(paths.socket()), "socket must exist while running");

        daemon.stop();
        daemon.awaitStopped();

        assertFalse(Files.exists(paths.socket()), "socket file must be removed on stop");
        assertFalse(Files.exists(paths.pidFile()), "pidfile must be removed on stop");
    }

    @Test
    @DisplayName("starting when a live daemon already holds the pidfile does not start a second listener")
    void doesNotStartSecondListenerWhenLiveDaemonExists(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        Daemon first = Daemon.start(paths, Duration.ofMinutes(10));
        try {
            first.awaitListening();

            // A live daemon (this process's PID in the pidfile, socket bound).
            Daemon second = Daemon.start(paths, Duration.ofMinutes(10));
            assertFalse(second.startedNewListener(),
                    "second start must detect the live daemon and not bind a second listener");
            assertTrue(first.startedNewListener(),
                    "the first start must have been the real listener");
        } finally {
            first.stop();
            first.awaitStopped();
        }
    }

    @Test
    @DisplayName("a graceful stop deletes the engine work directory")
    void stopDeletesWorkDir(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        Daemon daemon = Daemon.start(paths, Duration.ofMinutes(10));
        daemon.awaitListening();
        Path workDir = daemon.workDir();
        assertTrue(Files.isDirectory(workDir),
                "work dir must exist while the daemon runs: " + workDir);

        daemon.stop();
        daemon.awaitStopped();

        assertFalse(Files.exists(workDir),
                "work dir must be deleted after a graceful stop: " + workDir);
    }

    @Test
    @DisplayName("an idle-timeout self-stop deletes the engine work directory")
    void idleTimeoutDeletesWorkDir(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        // A short idle window: with no client traffic the daemon self-stops.
        Daemon daemon = Daemon.start(paths, Duration.ofMillis(200));
        daemon.awaitListening();
        Path workDir = daemon.workDir();
        assertTrue(Files.isDirectory(workDir),
                "work dir must exist while the daemon runs: " + workDir);

        // The idle timer must run the full daemon teardown, not just the
        // socket server stop — awaitStopped blocks on the real terminal state.
        daemon.awaitStopped();

        assertFalse(Files.exists(workDir),
                "work dir must be deleted when the daemon self-stops on idle: " + workDir);
        assertFalse(Files.exists(paths.pidFile()),
                "pidfile must be removed when the daemon self-stops on idle");
    }

    @Test
    @DisplayName("starting then stopping leaves no extra sonar-daemon-work directories")
    void startStopLeavesNoWorkDirs(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        long before = workDirCount();

        Daemon daemon = Daemon.start(paths, Duration.ofMinutes(10));
        daemon.awaitListening();
        daemon.stop();
        daemon.awaitStopped();

        assertEquals(before, workDirCount(),
                "a start/stop cycle must leave the sonar-daemon-work directory count unchanged");
    }

    private static long workDirCount() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var entries = Files.list(tmp)) {
            return entries
                    .filter(p -> p.getFileName().toString().startsWith("sonar-daemon-work"))
                    .count();
        }
    }

    @Test
    @DisplayName("start() is a no-op and does not unlink a live socket when the startup lock is already held")
    void startUnderHeldLockIsNoOpAndKeepsSocket(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);

        // Simulate a live daemon that owns the runtime directory: its socket
        // file exists AND another holder owns the startup lock. Driving the
        // test via the held OS lock makes it fully deterministic — no timing,
        // no sleeps; the lock is the observable gate.
        Files.createFile(paths.socket());
        try (var lockChannel = java.nio.channels.FileChannel.open(
                        paths.lockFile(),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE);
                var heldLock = lockChannel.lock()) {

            // The startup lock cannot be acquired -> start() must return a
            // no-op handle and must NOT delete the (pretend-live) socket.
            Daemon second = Daemon.start(paths, Duration.ofMinutes(10));
            assertFalse(second.startedNewListener(),
                    "start() under a held startup lock must not bind a listener");
            assertTrue(Files.exists(paths.socket()),
                    "a no-op start() must not unlink the live daemon's socket");
        }
    }

    @Test
    @DisplayName("a second start while the first daemon is live is a no-op and keeps the live socket")
    void secondStartWhileLiveIsNoOpAndKeepsSocket(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        Daemon first = Daemon.start(paths, Duration.ofMinutes(10));
        try {
            first.awaitListening();
            assertTrue(first.startedNewListener(), "the first start must be the real listener");

            Daemon second = Daemon.start(paths, Duration.ofMinutes(10));
            assertFalse(second.startedNewListener(),
                    "a second start while a daemon is live must not bind a second listener");
            assertTrue(Files.exists(paths.socket()),
                    "a no-op second start must not unlink the live daemon's socket");

            try (var ch = java.nio.channels.SocketChannel.open(
                    java.net.UnixDomainSocketAddress.of(paths.socket()))) {
                assertTrue(ch.isConnected(), "the live socket must still accept connections");
            }
        } finally {
            first.stop();
            first.awaitStopped();
        }
    }

    @Test
    @DisplayName("the startup lock file is released after a graceful stop, so a fresh start succeeds")
    void startupLockReleasedOnStop(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        Daemon first = Daemon.start(paths, Duration.ofMinutes(10));
        first.awaitListening();
        first.stop();
        first.awaitStopped();

        // The lock must be released on stop; a fresh start reacquires it.
        Daemon second = Daemon.start(paths, Duration.ofMinutes(10));
        try {
            second.awaitListening();
            assertTrue(second.startedNewListener(),
                    "after the first daemon stops and releases the lock, a fresh start must succeed");
        } finally {
            second.stop();
            second.awaitStopped();
        }
    }

    @Test
    @DisplayName("a daemon startup that fails after the pidfile is written leaks no resources")
    void startupFailureCleansUpEverything(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);

        // Force server.start() to fail deterministically: the socket path is
        // pre-created as a NON-EMPTY directory, so DaemonServer.start()'s
        // Files.deleteIfExists(socket) throws DirectoryNotEmptyException. This
        // failure lands AFTER the engine is built and the pidfile is written —
        // exactly the leak window the fix must close.
        Files.createDirectory(paths.socket());
        Files.createFile(paths.socket().resolve("blocker"));

        long workDirsBefore = workDirCount();

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> Daemon.start(paths, Duration.ofMinutes(10)),
                "a socket-bind failure must propagate out of Daemon.start()");
        org.junit.jupiter.api.Assertions.assertNotNull(thrown);

        assertFalse(Files.exists(paths.pidFile()),
                "a failed startup must not leave the pidfile behind");
        assertEquals(workDirsBefore, workDirCount(),
                "a failed startup must close the engine and leave no work directory");

        // The startup lock must have been released: a fresh start (after the
        // blocking directory is cleared) must succeed.
        Files.delete(paths.socket().resolve("blocker"));
        Files.delete(paths.socket());
        Daemon recovered = Daemon.start(paths, Duration.ofMinutes(10));
        try {
            recovered.awaitListening();
            assertTrue(recovered.startedNewListener(),
                    "after a failed startup releases the lock, a fresh start must succeed");
        } finally {
            recovered.stop();
            recovered.awaitStopped();
        }
    }

    @Test
    @DisplayName("a stale pidfile (no live process) does not block a fresh start")
    void stalePidFileDoesNotBlockStart(@TempDir Path dir) throws Exception {
        SocketPaths paths = paths(dir);
        // A PID that is almost certainly not a running process.
        Files.writeString(paths.pidFile(), "999999999");

        Daemon daemon = Daemon.start(paths, Duration.ofMinutes(10));
        try {
            daemon.awaitListening();
            assertTrue(daemon.startedNewListener(),
                    "a stale pidfile must not block a fresh daemon from starting");
            long written = Long.parseLong(Files.readString(paths.pidFile()).trim());
            assertEquals(ProcessHandle.current().pid(), written,
                    "fresh start must overwrite the stale pidfile with the real PID");
        } finally {
            daemon.stop();
            daemon.awaitStopped();
        }
    }
}
