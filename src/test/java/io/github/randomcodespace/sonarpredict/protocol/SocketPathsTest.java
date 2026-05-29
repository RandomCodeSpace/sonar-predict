package io.github.randomcodespace.sonarpredict.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SocketPathsTest {

    @Test
    @DisplayName("socket and pidfile live under $XDG_RUNTIME_DIR when it is set")
    void resolvesUnderXdgRuntimeDir() {
        Path runtime = Path.of("/run/user/1000");
        SocketPaths paths = SocketPaths.resolve(Map.of("XDG_RUNTIME_DIR", runtime.toString()));

        assertEquals(runtime, paths.socket().getParent(),
                "socket must sit directly under $XDG_RUNTIME_DIR");
        assertEquals(runtime, paths.pidFile().getParent(),
                "pidfile must sit beside the socket under $XDG_RUNTIME_DIR");
    }

    @Test
    @DisplayName("falls back to a per-user subdirectory of the temp dir when $XDG_RUNTIME_DIR is unset")
    void fallsBackToPerUserTempSubdirWhenXdgUnset() {
        SocketPaths paths = SocketPaths.resolve(Map.of());
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Path socketDir = paths.socket().getParent().normalize();

        // The fallback must NOT place the socket directly in the world-
        // traversable temp dir — it must use a per-user subdirectory.
        assertEquals(tmp.normalize(), socketDir.getParent(),
                "the fallback socket directory must be a child of java.io.tmpdir");
        assertTrue(socketDir.getFileName().toString().startsWith("sonar-predictor-"),
                "the fallback directory must be a sonar-predictor-<user> subdirectory, got: "
                        + socketDir.getFileName());
        assertTrue(socketDir.getFileName().toString()
                        .contains(System.getProperty("user.name")),
                "the fallback directory name must be user-specific, got: "
                        + socketDir.getFileName());
        assertEquals(socketDir, paths.pidFile().getParent().normalize(),
                "the pidfile must sit in the same per-user fallback directory");
    }

    @Test
    @DisplayName("the temp fallback directory is created with POSIX 0700 permissions")
    void fallbackDirectoryIsOwnerOnly() throws Exception {
        SocketPaths paths = SocketPaths.resolve(Map.of());
        Path socketDir = paths.socket().getParent();

        // Resolving must have created the directory so the daemon can bind.
        assertTrue(java.nio.file.Files.isDirectory(socketDir),
                "the fallback directory must be created on resolve, got: " + socketDir);

        var fs = socketDir.getFileSystem();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                fs.supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are only assertable on a POSIX filesystem");
        var perms = java.nio.file.Files.getPosixFilePermissions(socketDir);
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
                perms,
                "the fallback directory must be owner-only (0700), got: " + perms);
    }

    @Test
    @DisplayName("an empty $XDG_RUNTIME_DIR is treated as unset and falls back to the per-user temp subdir")
    void emptyXdgTreatedAsUnset() {
        SocketPaths paths = SocketPaths.resolve(Map.of("XDG_RUNTIME_DIR", "  "));
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));

        assertEquals(tmp.normalize(), paths.socket().getParent().normalize().getParent(),
                "an empty XDG value must fall back to the per-user temp subdirectory");
    }

    @Test
    @DisplayName("paths are stable: the same environment yields the same paths")
    void pathsAreStable() {
        Map<String, String> env = Map.of("XDG_RUNTIME_DIR", "/run/user/1000");
        SocketPaths a = SocketPaths.resolve(env);
        SocketPaths b = SocketPaths.resolve(env);

        assertEquals(a.socket(), b.socket(), "socket path must be deterministic");
        assertEquals(a.pidFile(), b.pidFile(), "pidfile path must be deterministic");
    }

    @Test
    @DisplayName("names are fixed — a single shared daemon per machine")
    void namesAreFixed() {
        SocketPaths paths = SocketPaths.resolve(Map.of("XDG_RUNTIME_DIR", "/run/user/1000"));

        assertTrue(paths.socket().getFileName().toString().endsWith(".sock"),
                "socket name must end in .sock, got: " + paths.socket().getFileName());
        assertTrue(paths.pidFile().getFileName().toString().endsWith(".pid"),
                "pidfile name must end in .pid, got: " + paths.pidFile().getFileName());
        // socket and pidfile share a stem so the pair is obviously related.
        String socketStem = stem(paths.socket().getFileName().toString());
        String pidStem = stem(paths.pidFile().getFileName().toString());
        assertEquals(socketStem, pidStem,
                "socket and pidfile must share a stem (related pair)");
    }

    @Test
    @DisplayName("the no-arg resolve() reads the real process environment")
    void noArgResolveUsesProcessEnv() {
        SocketPaths paths = SocketPaths.resolve();

        assertTrue(paths.socket().getFileName().toString().endsWith(".sock"));
        assertTrue(paths.pidFile().getFileName().toString().endsWith(".pid"));
    }

    @Test
    @DisplayName("a supplied version keys the socket/pidfile/lockfile names")
    void versionKeysTheNames() {
        Path runtime = Path.of("/run/user/1000");
        SocketPaths paths = SocketPaths.resolve(
                Map.of("XDG_RUNTIME_DIR", runtime.toString()), "11.3.0.85510");

        assertEquals("sonar-daemon-11.3.0.85510.sock",
                paths.socket().getFileName().toString(),
                "the socket name must carry the version token");
        assertEquals("sonar-daemon-11.3.0.85510.pid",
                paths.pidFile().getFileName().toString());
        assertEquals("sonar-daemon-11.3.0.85510.lock",
                paths.lockFile().getFileName().toString());
        assertEquals("11.3.0.85510", paths.version(),
                "resolved paths must expose their version token for the launcher to relay");
    }

    @Test
    @DisplayName("a blank version yields the bare unversioned names (back-compat)")
    void blankVersionYieldsBareNames() {
        Map<String, String> env = Map.of("XDG_RUNTIME_DIR", "/run/user/1000");
        SocketPaths blank = SocketPaths.resolve(env, "  ");

        assertEquals("sonar-daemon.sock", blank.socket().getFileName().toString(),
                "a blank version must fall back to the bare socket name");
        assertEquals("", blank.version(), "a blank version must expose an empty token");
        assertEquals(SocketPaths.resolve(env).socket(), blank.socket(),
                "blank-version and no-version resolution must agree");
    }

    @Test
    @DisplayName("a version with path separators is sanitized to a single safe segment")
    void versionIsSanitizedToSingleSegment() {
        Path runtime = Path.of("/run/user/1000");
        SocketPaths paths = SocketPaths.resolve(
                Map.of("XDG_RUNTIME_DIR", runtime.toString()), "1.0/evil sub");

        assertEquals(runtime, paths.socket().getParent(),
                "a malicious version must not escape the runtime directory");
        String name = paths.socket().getFileName().toString();
        assertTrue(name.startsWith("sonar-daemon-") && name.endsWith(".sock"),
                "name must remain a single sonar-daemon-*.sock segment, got: " + name);
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
