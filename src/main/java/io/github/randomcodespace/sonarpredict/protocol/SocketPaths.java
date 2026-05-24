package io.github.randomcodespace.sonarpredict.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the filesystem locations of the daemon's Unix domain socket and its
 * pidfile.
 *
 * <p>One shared daemon per machine: the names are fixed ({@code sonar-daemon}),
 * so any CLI process resolves the same pair and finds the running daemon.
 *
 * <p><b>Directory choice.</b> On Linux a logged-in session has
 * {@code $XDG_RUNTIME_DIR} — a per-user, {@code 0700}, tmpfs-backed directory
 * cleaned up on logout; it is the correct home for runtime sockets. When it is
 * absent (cron jobs, minimal containers, macOS) the fallback is <em>not</em>
 * the world-traversable system temp directory itself, but a per-user
 * subdirectory of it — {@code <java.io.tmpdir>/sonar-predictor-<user>/} —
 * created with POSIX {@code rwx------} (0700) permissions so that no other
 * local user can reach the socket and issue {@code SHUTDOWN}/{@code ANALYZE}/
 * {@code RULE_METADATA} requests. The socket, pidfile, and lock file always
 * sit in the same directory so they are removed together.
 *
 * <p><b>Non-POSIX residual risk.</b> On a non-POSIX filesystem (e.g. Windows)
 * the {@code 0700} permissions cannot be enforced; the per-user subdirectory
 * is still created (its user-specific name avoids collisions) but its access
 * control falls back to the filesystem's defaults. A Windows port should set
 * an owner-only ACL explicitly — see the {@code TODO (Windows)} note below.
 *
 * <p><b>TODO (Windows).</b> Windows has no {@code $XDG_RUNTIME_DIR} and, before
 * recent builds, no AF_UNIX support in the JDK's {@code SocketChannel}. v1
 * targets Unix domain sockets only; a Windows port should fall back to a
 * loopback TCP socket bound to {@code 127.0.0.1} on an ephemeral port recorded
 * in a sidecar file next to the pidfile. Not implemented here.
 */
public final class SocketPaths {

    /** Shared base name for the socket/pidfile pair — one daemon per machine. */
    private static final String BASE_NAME = "sonar-daemon";
    private static final String SOCKET_NAME = BASE_NAME + ".sock";
    private static final String PID_NAME = BASE_NAME + ".pid";
    private static final String LOCK_NAME = BASE_NAME + ".lock";

    private final Path socket;
    private final Path pidFile;
    private final Path lockFile;

    private SocketPaths(Path socket, Path pidFile, Path lockFile) {
        this.socket = socket;
        this.pidFile = pidFile;
        this.lockFile = lockFile;
    }

    /** Resolves the paths from the current process environment. */
    public static SocketPaths resolve() {
        return resolve(System.getenv());
    }

    /**
     * Resolves the paths from the given environment map (test seam).
     *
     * @param env the environment to read {@code XDG_RUNTIME_DIR} from
     * @return the resolved socket/pidfile pair
     */
    public static SocketPaths resolve(Map<String, String> env) {
        Path dir = runtimeDir(env);
        return new SocketPaths(
                dir.resolve(SOCKET_NAME), dir.resolve(PID_NAME), dir.resolve(LOCK_NAME));
    }

    private static Path runtimeDir(Map<String, String> env) {
        String xdg = env.get("XDG_RUNTIME_DIR");
        if (xdg != null && !xdg.isBlank()) {
            // $XDG_RUNTIME_DIR is already a per-user 0700 directory.
            return Path.of(xdg);
        }
        return secureTempFallbackDir();
    }

    /**
     * Resolves (creating if absent) the per-user {@code 0700} fallback
     * directory under the system temp directory. If it already exists its
     * permissions are re-asserted to {@code 0700} — a directory left
     * world-readable by a prior buggy run is corrected, not trusted.
     */
    private static Path secureTempFallbackDir() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Path dir = tmp.resolve("sonar-predictor-" + userSegment());
        try {
            boolean posix = dir.getFileSystem()
                    .supportedFileAttributeViews().contains("posix");
            if (!Files.isDirectory(dir)) {
                if (posix) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
                } else {
                    // Non-POSIX (e.g. Windows): 0700 cannot be expressed as a
                    // creation attribute. The directory is still created with
                    // a user-specific name; its access control falls back to
                    // the filesystem default. See the class TODO (Windows).
                    Files.createDirectories(dir);
                }
            } else if (posix) {
                // Re-assert 0700 in case a prior run created it too openly.
                Files.setPosixFilePermissions(
                        dir, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not create the per-user daemon runtime directory: " + dir, e);
        }
        return dir;
    }

    /**
     * A filesystem-safe, user-specific directory-name segment. The OS user
     * name with every non-alphanumeric character collapsed to {@code _}; if
     * that yields nothing usable, the numeric {@code user.name} hash is used.
     */
    private static String userSegment() {
        String user = System.getProperty("user.name", "");
        String sanitized = user.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (sanitized.isBlank() || sanitized.equals("_")) {
            return "u" + Integer.toHexString(user.hashCode());
        }
        return sanitized;
    }

    /** The Unix domain socket path the daemon listens on. */
    public Path socket() {
        return socket;
    }

    /** The pidfile path, beside the socket. */
    public Path pidFile() {
        return pidFile;
    }

    /**
     * The startup lock-file path, beside the socket. The daemon holds an OS
     * file lock on this file for its whole lifetime so that two concurrent
     * starters cannot both build an engine, overwrite the pidfile, and unlink
     * each other's socket.
     */
    public Path lockFile() {
        return lockFile;
    }
}
