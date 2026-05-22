package dev.sonarcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.sonarcli.protocol.MessageCodec;
import dev.sonarcli.protocol.Method;
import dev.sonarcli.protocol.SocketPaths;
import dev.sonarcli.protocol.WireMessage;

/**
 * Spawns the real daemon JVM as a subprocess via {@link DaemonLauncher}.
 * Deterministic: {@code start()} returns only once the socket is accepting
 * connections — there is no sleep. Each test points the daemon at an isolated
 * temp {@code XDG_RUNTIME_DIR} and shuts it down in a {@code finally} block.
 */
class DaemonLauncherTest {

    @Test
    @DisplayName("resolves the daemon jar from the sonar.daemon.jar system property")
    void resolvesJarFromSystemProperty(@TempDir Path dir) throws Exception {
        Path fakeJar = Files.createFile(dir.resolve("custom-daemon.jar"));
        String previous = System.getProperty("sonar.daemon.jar");
        System.setProperty("sonar.daemon.jar", fakeJar.toString());
        try {
            assertEquals(fakeJar, DaemonLauncher.resolveDaemonJar(),
                    "the sonar.daemon.jar property must take precedence");
        } finally {
            if (previous == null) {
                System.clearProperty("sonar.daemon.jar");
            } else {
                System.setProperty("sonar.daemon.jar", previous);
            }
        }
    }

    @Test
    @DisplayName("the dev default points at the daemon module's shaded jar")
    void devDefaultResolvesShadedJar() {
        String previous = System.getProperty("sonar.daemon.jar");
        System.clearProperty("sonar.daemon.jar");
        try {
            Path jar = DaemonLauncher.resolveDaemonJar();
            assertNotNull(jar, "the dev default must resolve a jar path");
            String name = jar.getFileName().toString();
            assertTrue(name.startsWith("sonar-predictor-daemon-") && name.endsWith(".jar"),
                    "dev default must select the daemon shaded jar, got: " + name);
            assertFalse(name.startsWith("original-"),
                    "dev default must not select the unshaded original jar");
        } finally {
            if (previous != null) {
                System.setProperty("sonar.daemon.jar", previous);
            }
        }
    }

    @Test
    @DisplayName("the dev-default working directory contains the analyzer plugins")
    void devDefaultWorkDirContainsPlugins() {
        String previous = System.getProperty("sonar.daemon.workdir");
        System.clearProperty("sonar.daemon.workdir");
        try {
            Path workDir = DaemonLauncher.resolveDaemonWorkDir();
            assertNotNull(workDir, "the dev default must resolve a working directory");
            assertTrue(Files.isDirectory(workDir.resolve("plugins")),
                    "daemon working dir must contain a plugins/ directory, got: " + workDir);
        } finally {
            if (previous != null) {
                System.setProperty("sonar.daemon.workdir", previous);
            }
        }
    }

    @Test
    @DisplayName("start() spawns the daemon and returns once the socket is accepting")
    void startSpawnsDaemonAndWaitsForSocket(@TempDir Path dir) throws Exception {
        SocketPaths paths = SocketPaths.resolve(Map.of("XDG_RUNTIME_DIR", dir.toString()));
        DaemonLauncher launcher = new DaemonLauncher(paths, Duration.ofSeconds(60));
        try {
            assertFalse(launcher.isDaemonRunning(), "no daemon should be running yet");

            launcher.start();

            assertTrue(launcher.isDaemonRunning(),
                    "after start() the daemon socket must be accepting connections");
            assertTrue(Files.exists(paths.socket()),
                    "the daemon socket file must exist after start()");
        } finally {
            shutdown(paths);
        }
    }

    @Test
    @DisplayName("a second start() while the daemon is already running is a no-op")
    void secondStartIsNoOp(@TempDir Path dir) throws Exception {
        SocketPaths paths = SocketPaths.resolve(Map.of("XDG_RUNTIME_DIR", dir.toString()));
        DaemonLauncher launcher = new DaemonLauncher(paths, Duration.ofSeconds(60));
        try {
            launcher.start();
            assertTrue(launcher.isDaemonRunning());
            long pidAfterFirst = readPid(paths);

            launcher.start(); // must detect the live daemon and not spawn a second

            assertTrue(launcher.isDaemonRunning(),
                    "the daemon must still be running after a no-op second start()");
            assertEquals(pidAfterFirst, readPid(paths),
                    "a second start() must not replace the running daemon");
        } finally {
            shutdown(paths);
        }
    }

    @Test
    @DisplayName("a provisioned runtime launches on system Java with its plugins dir pinned")
    void provisionedRuntimeDrivesTheSpawnCommand(@TempDir Path dir) throws Exception {
        // Build a fully provisioned runtime layout under a temp base.
        dev.sonarcli.cli.setup.RuntimeLayout layout =
                new dev.sonarcli.cli.setup.RuntimeLayout(dir, "10.24.0.81415");
        Files.createDirectories(layout.pluginsDir());
        Files.createFile(layout.pluginsDir().resolve("sonar-java-plugin-8.15.0.39343.jar"));
        Files.createFile(layout.engineJar());
        assertTrue(layout.isProvisioned(), "the stub layout must be provisioned");

        Path daemonJar = Files.createFile(dir.resolve("daemon.jar"));
        var command = DaemonLauncher.buildSpawnCommand(daemonJar, layout);

        assertFalse(command.get(0).startsWith(dir.toString()),
                "a provisioned launch must use the system java, not a bundled JRE, got: "
                        + command.get(0));
        assertTrue(command.stream().anyMatch(arg ->
                        arg.equals("-D" + DaemonLauncher.DAEMON_PLUGINS_DIR_PROPERTY
                                + "=" + layout.pluginsDir().toAbsolutePath())),
                "a provisioned launch must pass -Dsonar.plugins.dir, got: " + command);
        assertTrue(command.contains(daemonJar.toString()),
                "the command must run the daemon jar");
    }

    @Test
    @DisplayName("with no provisioned runtime the dev default uses the system JVM")
    void devDefaultDrivesTheSpawnCommand(@TempDir Path dir) throws Exception {
        Path daemonJar = Files.createFile(dir.resolve("daemon.jar"));
        var command = DaemonLauncher.buildSpawnCommand(daemonJar, null);

        assertFalse(command.stream().anyMatch(
                        arg -> arg.startsWith("-D" + DaemonLauncher.DAEMON_PLUGINS_DIR_PROPERTY)),
                "the dev default must not pin sonar.plugins.dir, got: " + command);
        assertTrue(command.contains("-jar") && command.contains(daemonJar.toString()),
                "the command must run the daemon jar");
    }

    @Test
    @DisplayName("sonar.java.exe drives the java executable in the spawn command")
    void javaExePropertyDrivesTheSpawnCommand(@TempDir Path dir) throws Exception {
        Path bundledJava = Files.createFile(dir.resolve("bundled-java"));
        Path daemonJar = Files.createFile(dir.resolve("daemon.jar"));
        String previous = System.getProperty(DaemonLauncher.JAVA_EXE_PROPERTY);
        System.setProperty(DaemonLauncher.JAVA_EXE_PROPERTY, bundledJava.toString());
        try {
            var command = DaemonLauncher.buildSpawnCommand(daemonJar, null);

            assertEquals(bundledJava.toString(), command.get(0),
                    "the spawn command must use the java named by sonar.java.exe, got: "
                            + command);
        } finally {
            if (previous == null) {
                System.clearProperty(DaemonLauncher.JAVA_EXE_PROPERTY);
            } else {
                System.setProperty(DaemonLauncher.JAVA_EXE_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("sonar.plugins.dir is forwarded to the daemon for the bundled layout")
    void pluginsDirPropertyIsForwardedToTheSpawnCommand(@TempDir Path dir) throws Exception {
        Path pluginsDir = Files.createDirectories(dir.resolve("plugins"));
        Path daemonJar = Files.createFile(dir.resolve("daemon.jar"));
        String previous = System.getProperty(DaemonLauncher.DAEMON_PLUGINS_DIR_PROPERTY);
        System.setProperty(DaemonLauncher.DAEMON_PLUGINS_DIR_PROPERTY, pluginsDir.toString());
        try {
            var command = DaemonLauncher.buildSpawnCommand(daemonJar, null);

            assertTrue(command.contains(
                            "-D" + DaemonLauncher.DAEMON_PLUGINS_DIR_PROPERTY + "="
                                    + pluginsDir),
                    "a bundled launch must forward -Dsonar.plugins.dir, got: " + command);
        } finally {
            if (previous == null) {
                System.clearProperty(DaemonLauncher.DAEMON_PLUGINS_DIR_PROPERTY);
            } else {
                System.setProperty(DaemonLauncher.DAEMON_PLUGINS_DIR_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("sonar.daemon.jar is honored by resolveDaemonJar for the bundled layout")
    void daemonJarPropertyHonoredForBundledLayout(@TempDir Path dir) throws Exception {
        Path bundledJar = Files.createFile(dir.resolve("sonar-predictor-daemon.jar"));
        String previous = System.getProperty(DaemonLauncher.DAEMON_JAR_PROPERTY);
        System.setProperty(DaemonLauncher.DAEMON_JAR_PROPERTY, bundledJar.toString());
        try {
            assertEquals(bundledJar, DaemonLauncher.resolveDaemonJar(),
                    "the bundled sonar.daemon.jar path must be honored");
        } finally {
            if (previous == null) {
                System.clearProperty(DaemonLauncher.DAEMON_JAR_PROPERTY);
            } else {
                System.setProperty(DaemonLauncher.DAEMON_JAR_PROPERTY, previous);
            }
        }
    }

    private static long readPid(SocketPaths paths) throws Exception {
        return Long.parseLong(Files.readString(paths.pidFile()).trim());
    }

    /** Sends SHUTDOWN to the daemon and waits for the socket file to disappear. */
    private static void shutdown(SocketPaths paths) throws Exception {
        if (Files.exists(paths.socket())) {
            try (SocketChannel channel =
                    SocketChannel.open(UnixDomainSocketAddress.of(paths.socket()))) {
                MessageCodec.writeMessage(
                        java.nio.channels.Channels.newOutputStream(channel),
                        new WireMessage("shutdown", Method.SHUTDOWN, null));
                MessageCodec.readMessage(java.nio.channels.Channels.newInputStream(channel));
            } catch (Exception ignored) {
                // Daemon already gone — nothing to clean up.
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (Files.exists(paths.socket()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
