package dev.sonarcli.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.sonarcli.cli.setup.Manifest;
import dev.sonarcli.cli.setup.RuntimeLayout;
import dev.sonarcli.protocol.SocketPaths;

/**
 * Locates the daemon's runnable fat jar and spawns it as a child JVM, returning
 * only once the daemon's Unix domain socket is accepting connections.
 *
 * <p><b>Jar resolution.</b> {@link #resolveDaemonJar()} first reads the
 * {@code sonar.daemon.jar} system property; that is the supported override and
 * the mechanism Plan 7's {@code setup} command will use to point at an
 * installed runtime. With no property set it falls back to a dev default — the
 * newest {@code daemon/target/sonar-predictor-daemon-*.jar} (the shaded jar,
 * never the {@code original-} prefixed unshaded one), located relative to the
 * working directory or its ancestors.
 *
 * <p><b>Determinism.</b> {@link #start()} polls {@link #isDaemonRunning()} —
 * an actual connect attempt against the socket — until it succeeds or a bounded
 * timeout elapses. It never sleeps for a fixed duration guessing the daemon is
 * up. A {@code start()} call when the daemon is already running is a no-op:
 * the first connect attempt succeeds and no process is spawned. The spawned
 * daemon self-deduplicates too (it detects a live pidfile), so a lost race
 * still yields a single daemon.
 *
 * <p><b>Environment.</b> The child JVM inherits this process's environment with
 * {@code XDG_RUNTIME_DIR} forced to the directory holding the socket, so the
 * daemon's {@link SocketPaths#resolve()} resolves the exact same socket and
 * pidfile this launcher expects.
 *
 * <p><b>Working directory.</b> The daemon resolves its vendored analyzer
 * plugins from a {@code plugins/} directory relative to its working directory.
 * {@link #resolveDaemonWorkDir()} reads the {@code sonar.daemon.workdir} system
 * property, falling back to the {@code daemon/} module root that holds the
 * resolved jar (its {@code daemon/plugins/} is the dev-default plugin set).
 *
 * <p><b>Java runtime.</b> The daemon launches with the {@code java} named by
 * {@code -Dsonar.java.exe} when set — the skill bundle's {@code bin/sonar}
 * launcher sets it to a Java 17+ runtime it auto-discovered. With the property
 * absent the daemon launches with the current/system {@code java} that started
 * the CLI. No JRE is provisioned or bundled.
 */
public final class DaemonLauncher {

    /** System property naming the daemon fat jar; overrides the dev default. */
    public static final String DAEMON_JAR_PROPERTY = "sonar.daemon.jar";

    /** System property naming the daemon working dir; overrides the dev default. */
    public static final String DAEMON_WORKDIR_PROPERTY = "sonar.daemon.workdir";

    /**
     * System property naming the {@code java} executable used to spawn the
     * daemon. The skill bundle's {@code bin/sonar} launcher sets it to the
     * Java 17+ runtime it auto-discovered, so the daemon launches on a
     * verified-compatible JVM rather than whichever {@code java} happens to be
     * on {@code PATH}. Absent, the launcher falls back to the current JVM's
     * {@code java.home} (the dev default).
     */
    public static final String JAVA_EXE_PROPERTY = "sonar.java.exe";

    /**
     * The {@code sonar.plugins.dir} property the daemon reads to locate its
     * analyzer plugins. The launcher sets it when a provisioned runtime is
     * found. Kept as a literal here because the {@code cli} module must not
     * depend on the {@code daemon} module — the two ends agree on the name.
     */
    public static final String DAEMON_PLUGINS_DIR_PROPERTY = "sonar.plugins.dir";

    /** Default bounded wait for the daemon socket to start accepting. */
    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(60);

    private static final String DAEMON_JAR_PREFIX = "sonar-predictor-daemon-";

    private final SocketPaths paths;
    private final Duration startupTimeout;

    /** Creates a launcher with the {@link #DEFAULT_STARTUP_TIMEOUT}. */
    public DaemonLauncher(SocketPaths paths) {
        this(paths, DEFAULT_STARTUP_TIMEOUT);
    }

    /**
     * @param paths          the socket/pidfile locations the daemon must use
     * @param startupTimeout bounded wait for the socket to begin accepting
     */
    public DaemonLauncher(SocketPaths paths, Duration startupTimeout) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
    }

    /**
     * Resolves the daemon fat jar: the {@code sonar.daemon.jar} property if set,
     * otherwise the dev-default shaded jar under {@code daemon/target/}.
     *
     * @return the resolved jar path
     * @throws IllegalStateException if no jar can be located
     */
    public static Path resolveDaemonJar() {
        String override = System.getProperty(DAEMON_JAR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path jar = findDevDefaultJar();
        if (jar == null) {
            throw new IllegalStateException(
                    "could not locate the daemon jar; set -D" + DAEMON_JAR_PROPERTY
                            + "=<path> or build the daemon module first");
        }
        return jar;
    }

    /**
     * Resolves the working directory the daemon JVM runs in — the directory the
     * daemon resolves its {@code plugins/} subdirectory against. The
     * {@code sonar.daemon.workdir} property wins; otherwise the {@code daemon/}
     * module root holding the resolved jar is used.
     *
     * @return the daemon working directory
     * @throws IllegalStateException if no directory containing {@code plugins/}
     *                               can be located
     */
    public static Path resolveDaemonWorkDir() {
        String override = System.getProperty(DAEMON_WORKDIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        // Dev default: the jar lives at <module>/target/<jar>; the module root
        // (<module>) holds the plugins/ directory the daemon needs.
        Path jar = resolveDaemonJar();
        Path target = jar.toAbsolutePath().getParent();
        Path moduleRoot = target != null ? target.getParent() : null;
        if (moduleRoot != null && Files.isDirectory(moduleRoot.resolve("plugins"))) {
            return moduleRoot;
        }
        throw new IllegalStateException(
                "could not locate the daemon working directory (no plugins/ found near "
                        + jar + "); set -D" + DAEMON_WORKDIR_PROPERTY + "=<path>");
    }

    /** Whether the daemon socket currently accepts a connection. */
    public boolean isDaemonRunning() {
        try (SocketChannel channel =
                SocketChannel.open(UnixDomainSocketAddress.of(paths.socket()))) {
            return true;
        } catch (IOException notRunning) {
            return false;
        }
    }

    /**
     * Ensures the daemon is running: returns immediately if it already is,
     * otherwise spawns the daemon JVM and blocks until its socket is accepting.
     *
     * @throws IllegalStateException if the socket is not accepting within the
     *                               startup timeout
     */
    public void start() {
        if (isDaemonRunning()) {
            return;
        }
        Process process = spawn();
        awaitSocket(process);
    }

    private Process spawn() {
        Path jar = resolveDaemonJar();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("daemon jar does not exist: " + jar);
        }
        List<String> command = buildSpawnCommand(jar, resolveProvisionedLayout());
        ProcessBuilder builder = new ProcessBuilder(command);
        // The daemon resolves its plugins/ directory relative to its working
        // directory; run it from the module root that holds plugins/.
        builder.directory(resolveDaemonWorkDir().toFile());
        // The daemon resolves SocketPaths from its environment; force it onto
        // the same runtime directory this launcher expects.
        builder.environment().put("XDG_RUNTIME_DIR", paths.socket().getParent().toString());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            return builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not spawn the daemon JVM", e);
        }
    }

    /**
     * Builds the JVM command line that launches the daemon.
     *
     * <p><b>Java runtime.</b> When {@code -Dsonar.java.exe} is set — the skill
     * bundle's {@code bin/sonar} launcher sets it to the Java 17+ runtime it
     * auto-discovered — the daemon spawns with that executable. Absent, the
     * daemon launches with the current JVM's {@code java} (the dev default).
     *
     * <p><b>Plugins directory resolution.</b> The daemon is told where its
     * analyzer plugins live via {@code -Dsonar.plugins.dir}. The directory is
     * taken, in order, from: an explicit {@code -Dsonar.plugins.dir} on the
     * CLI (the skill bundle's launcher sets this to {@code <bundle>/plugins});
     * else a verified provisioned {@code ~/.sonar/<version>/} runtime created
     * by {@code sonar setup}. With neither, the dev default applies: the
     * daemon resolves a {@code plugins/} directory relative to its working
     * directory.
     *
     * @param jar         the daemon fat jar to run
     * @param provisioned a fully provisioned runtime layout, or {@code null}
     * @return the command line for {@link ProcessBuilder}
     */
    static List<String> buildSpawnCommand(Path jar, RuntimeLayout provisioned) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        String pluginsDir = resolvePluginsDir(provisioned);
        if (pluginsDir != null) {
            command.add("-D" + DAEMON_PLUGINS_DIR_PROPERTY + "=" + pluginsDir);
        }
        command.add("-jar");
        command.add(jar.toString());
        return command;
    }

    /**
     * Resolves the analyzer-plugin directory to hand the daemon, or
     * {@code null} when the daemon should use its working-directory default.
     *
     * <p>An explicit {@code -Dsonar.plugins.dir} on the CLI wins — that is how
     * the skill bundle's {@code bin/sonar} launcher points at {@code
     * <bundle>/plugins}. Otherwise a verified provisioned runtime's plugins
     * directory is used.
     *
     * @param provisioned a verified provisioned layout, or {@code null}
     * @return the plugins directory path, or {@code null} for the dev default
     */
    private static String resolvePluginsDir(RuntimeLayout provisioned) {
        String explicit = System.getProperty(DAEMON_PLUGINS_DIR_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (provisioned != null) {
            return provisioned.pluginsDir().toAbsolutePath().toString();
        }
        return null;
    }

    /**
     * Resolves the provisioned runtime layout if one is fully in place
     * <em>and verified</em> against the bundled manifest's checksums.
     *
     * <p>A layout is only returned when every engine + plugin jar matches the
     * manifest's pinned SHA-256 and the full plugin set is present
     * ({@link RuntimeLayout#isVerified}). A tampered, partial, or stale runtime
     * is rejected — the launcher falls back to the dev default rather than
     * launching unverified artifacts.
     *
     * @return the verified {@link RuntimeLayout}, or {@code null} when no
     *         trustworthy provisioned runtime exists (the dev-default applies)
     */
    static RuntimeLayout resolveProvisionedLayout() {
        try {
            Manifest manifest = Manifest.bundled();
            RuntimeLayout layout = RuntimeLayout.forVersion(manifest.version());
            return layout.isVerified(manifest) ? layout : null;
        } catch (RuntimeException notAvailable) {
            // No bundled manifest or unreadable runtime — fall back to dev.
            return null;
        }
    }

    private void awaitSocket(Process process) {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isDaemonRunning()) {
                return;
            }
            if (!process.isAlive() && !isDaemonRunning()) {
                throw new IllegalStateException(
                        "daemon process exited (code " + process.exitValue()
                                + ") before its socket began accepting connections");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the daemon", e);
            }
        }
        throw new IllegalStateException(
                "daemon socket did not start accepting within " + startupTimeout);
    }

    private static String javaExecutable() {
        String configured = System.getProperty(JAVA_EXE_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path java = javaHome.resolve("bin").resolve("java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    private static Path findDevDefaultJar() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path target = dir.resolve("daemon").resolve("target");
            if (Files.isDirectory(target)) {
                Path jar = newestDaemonJar(target);
                if (jar != null) {
                    return jar;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static Path newestDaemonJar(Path targetDir) {
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(targetDir, "*.jar")) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(DAEMON_JAR_PREFIX) && !name.startsWith("original-")) {
                    candidates.add(entry);
                }
            }
        } catch (IOException e) {
            return null;
        }
        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        for (Path candidate : candidates) {
            try {
                long modified = Files.getLastModifiedTime(candidate).toMillis();
                if (modified > newestTime) {
                    newestTime = modified;
                    newest = candidate;
                }
            } catch (IOException ignored) {
                // Skip a jar we cannot stat.
            }
        }
        return newest;
    }
}
