package dev.sonarcli.daemon;

/**
 * The daemon's own version string, reported in a {@code PING} response so a CLI
 * can detect a stale daemon and decide whether to restart it.
 *
 * <p>Sourced from the build: the {@code Implementation-Version} of the daemon
 * classes' package, populated from the Maven artifact version when the daemon
 * runs from its packaged JAR. In a test/exploded-classes run no manifest is
 * present, so a {@code dev} fallback is used.
 */
public final class DaemonVersion {

    private static final String FALLBACK = "0.1.0-dev";

    private DaemonVersion() {
    }

    /** The current daemon version, never {@code null}. */
    public static String current() {
        Package pkg = DaemonVersion.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null && !version.isBlank() ? version : FALLBACK;
    }
}
