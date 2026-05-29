package io.github.randomcodespace.sonarpredict.daemon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

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

    /**
     * Applies {@code action} to every {@code *.jar} in {@code pluginsDir}, in
     * directory-iteration order.
     *
     * <p>This is the shared jar-scan loop used by the in-memory plugin readers
     * ({@code RuleCatalog}, {@code SonarWayProfiles}): list the directory,
     * keep entries whose file name ends in {@code .jar}, and hand each to the
     * caller. The directory must hold at least one plugin jar — an empty scan
     * is a configuration error, not a silently-empty result.
     *
     * @param pluginsDir directory holding the vendored analyzer plugin JARs
     * @param action     invoked once per discovered {@code *.jar}
     * @throws IllegalStateException if the directory holds no {@code *.jar}
     * @throws UncheckedIOException  if the directory cannot be listed
     */
    static void forEachJar(Path pluginsDir, Consumer<Path> action) {
        List<Path> jars;
        try (Stream<Path> entries = Files.list(pluginsDir)) {
            jars = entries
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not list plugins directory: " + pluginsDir.toAbsolutePath(), e);
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException(
                    "no analyzer plugin JARs in " + pluginsDir.toAbsolutePath());
        }
        for (Path jar : jars) {
            action.accept(jar);
        }
    }
}
