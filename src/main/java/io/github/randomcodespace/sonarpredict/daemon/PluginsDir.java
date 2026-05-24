package io.github.randomcodespace.sonarpredict.daemon;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves the directory the daemon loads its vendored analyzer plugin jars
 * from.
 *
 * <p><b>Precedence.</b> The {@code sonar.plugins.dir} system property wins;
 * then the {@code SONAR_PLUGINS_DIR} environment variable; otherwise the
 * dev default — a {@code plugins/} directory resolved against the daemon's
 * working directory.
 *
 * <p>The override is the seam the CLI's {@code setup} uses: once a runtime is
 * provisioned the CLI launches the daemon with {@code sonar.plugins.dir}
 * pointed at {@code ~/.sonar/<version>/plugins}.
 */
final class PluginsDir {

    /** System property naming the analyzer-plugin directory. */
    static final String PROPERTY = "sonar.plugins.dir";

    /** Environment variable naming the analyzer-plugin directory. */
    static final String ENV_VAR = "SONAR_PLUGINS_DIR";

    /** The dev default — a {@code plugins/} directory relative to the CWD. */
    static final Path DEFAULT = Path.of("plugins");

    private PluginsDir() {
    }

    /** Resolves the plugins directory from the live process environment. */
    static Path resolve() {
        return resolve(System.getenv(), System::getProperty);
    }

    /**
     * Resolves the plugins directory from explicit sources — the testable form.
     *
     * @param env      the environment-variable map
     * @param property the system-property lookup
     * @return the resolved plugins directory
     */
    static Path resolve(Map<String, String> env, Function<String, String> property) {
        String fromProperty = property.apply(PROPERTY);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Path.of(fromProperty);
        }
        String fromEnv = env.get(ENV_VAR);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        return DEFAULT;
    }
}
