package io.github.randomcodespace.sonarpredict.daemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sonarsource.sonarlint.core.commons.Version;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;
import org.sonarsource.sonarlint.core.plugin.commons.LoadedPlugins;
import org.sonarsource.sonarlint.core.plugin.commons.PluginsLoadResult;
import org.sonarsource.sonarlint.core.plugin.commons.PluginsLoader;

/**
 * Loads analyzer plugin JARs into a {@link LoadedPlugins} for the analysis
 * engine.
 *
 * <p>{@link #loadAll} loads every vendored analyzer; {@link #loadJava} remains
 * for the Java-only path.
 */
public final class PluginRuntime {

    /** The v1 multi-language analyzer set enabled when loading all plugins. */
    static final Set<SonarLanguage> V1_LANGUAGES = Set.of(
            SonarLanguage.JAVA,
            SonarLanguage.PYTHON,
            SonarLanguage.JS,
            SonarLanguage.TS,
            SonarLanguage.CSS,
            SonarLanguage.PHP,
            SonarLanguage.KOTLIN,
            SonarLanguage.GO,
            SonarLanguage.RUBY,
            SonarLanguage.SCALA,
            SonarLanguage.HTML,
            SonarLanguage.XML);

    private PluginRuntime() {
    }

    /**
     * Loads every {@code *.jar} found in {@code pluginsDir}, enabling the full
     * v1 multi-language analyzer set ({@link #V1_LANGUAGES}).
     *
     * <p>Installs the global SonarLint log target first ({@link EngineLog}).
     *
     * @param pluginsDir directory containing the vendored analyzer plugin JARs
     * @return the loaded plugins, covering all vendored analyzers
     * @throws IllegalStateException if the directory holds no plugin JARs
     */
    public static LoadedPlugins loadAll(Path pluginsDir) {
        Set<Path> jars = findPluginJars(pluginsDir);
        if (jars.isEmpty()) {
            throw new IllegalStateException(
                    "no analyzer plugin JARs found in " + pluginsDir.toAbsolutePath());
        }
        return loadFrom(jars);
    }

    /**
     * Loads <em>exactly</em> the analyzer plugin jars named in {@code jars} —
     * an explicit allow-list rather than a blind {@code *.jar} glob.
     *
     * <p>This is what keeps an unaccounted-for jar dropped into the plugins
     * directory out of the engine: only the jars the caller hands in are ever
     * passed to the plugin loader. Every path must point at a regular file;
     * a missing jar fails fast rather than silently shrinking the analyzer set.
     *
     * @param jars the exact set of analyzer plugin jars to load
     * @return the loaded plugins
     * @throws IllegalStateException if {@code jars} is empty or any jar is absent
     */
    public static LoadedPlugins loadFrom(Set<Path> jars) {
        EngineLog.install();

        if (jars.isEmpty()) {
            throw new IllegalStateException("no analyzer plugin JARs supplied");
        }
        Set<Path> resolved = new LinkedHashSet<>();
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException(
                        "analyzer plugin JAR not found: " + jar.toAbsolutePath());
            }
            resolved.add(jar.toAbsolutePath().normalize());
        }

        // The JS/TS/CSS analyzer declares a NodeJs runtime requirement
        // (NodeJs-Min-Version in its manifest). The plugin loader skips it
        // unless a Node.js version is supplied, so detect the host Node.
        PluginsLoader.Configuration config = new PluginsLoader.Configuration(
                resolved,
                V1_LANGUAGES,
                false,
                detectNodeVersion());
        // Second argument is disabledPluginsForAnalysis — keys in this set are excluded
        // from getAnalysisPluginInstancesByKeys() and therefore never installed into any
        // Spring analysis container. Pass an empty set so the host plugin's extensions
        // (NoOpAnalysisWarnings) are visible to sensors such as HtmlSensor that autowire
        // AnalysisWarnings. The engine's own "additionalAllowedPlugins" list (textdeveloper,
        // textenterprise, etc.) is built internally by PluginsLoader and is separate.
        PluginsLoadResult result = new PluginsLoader().load(config, Set.of());
        return result.getLoadedPlugins();
    }

    /**
     * Derives the set of v1 analyzer languages whose plugin actually loaded,
     * from the keys of the successfully-loaded plugins.
     *
     * <p>A skipped analyzer — most notably the {@code javascript} plugin when
     * no Node.js runtime is available, which carries JS, TS, and CSS — is
     * absent from {@code loadedPluginKeys}, so its languages are excluded.
     * This is what makes {@code loadedLanguages()} report only the languages
     * the daemon can truly analyze rather than the full static set.
     *
     * @param loadedPluginKeys the plugin keys present in {@link LoadedPlugins}
     *                         ({@code LoadedPlugins.getAllPluginInstancesByKeys().keySet()})
     * @return the subset of {@link #V1_LANGUAGES} backed by a loaded plugin
     */
    public static Set<SonarLanguage> loadedLanguagesFor(Set<String> loadedPluginKeys) {
        Set<SonarLanguage> loaded = EnumSet.noneOf(SonarLanguage.class);
        for (SonarLanguage language : V1_LANGUAGES) {
            if (loadedPluginKeys.contains(language.getPlugin().getKey())) {
                loaded.add(language);
            }
        }
        return loaded;
    }

    /**
     * Matches the {@code vX.Y.Z} output of {@code node --version}.
     *
     * <p>Suppresses {@code java:S5852}: this regex has no nested quantifiers
     * and no overlapping alternations, and {@link #detectNodeVersion()} bounds
     * the input length to 64 chars before invoking the matcher. ReDoS surface
     * is nil; Sonar's rule is being conservative on any quantifier-bearing
     * pattern.
     */
    @SuppressWarnings("java:S5852")
    private static final Pattern NODE_VERSION =
            Pattern.compile("v?(\\d+\\.\\d+\\.\\d+)");

    /**
     * Detects the host Node.js version by running {@code node --version}.
     *
     * <p>Suppresses:
     * <ul>
     *   <li>{@code java:S4036} — PATH-resolving {@code node} is intended; this
     *       is a developer tool and Node is canonically managed by nvm / asdf
     *       / mise / fnm, none of which use a stable absolute path.</li>
     *   <li>{@code java:S5852} — {@link #NODE_VERSION} is
     *       {@code v?(\d+\.\d+\.\d+)}: no nested quantifiers, no overlapping
     *       alternations; the input is additionally bounded to 64 chars
     *       before being matched. ReDoS surface is nil.</li>
     * </ul>
     *
     * @return the parsed version, or {@link Optional#empty()} if Node is absent
     *         or its version could not be determined — in which case the
     *         JS/TS/CSS analyzer is simply skipped, not a fatal error
     */
    @SuppressWarnings({"java:S4036", "java:S5852"})
    static Optional<Version> detectNodeVersion() {
        try {
            Process process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (output == null || output.length() > 64) {
                // Bound input length before regex match — `node --version`
                // emits one short `vX.Y.Z` line; anything pathologically long
                // is a misconfigured shim or corrupt output, not a valid
                // version. Bounding also defangs the (already low) S5852
                // ReDoS concern on NODE_VERSION.
                return Optional.empty();
            }
            Matcher m = NODE_VERSION.matcher(output.trim());
            return m.find() ? Optional.of(Version.create(m.group(1))) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Set<Path> findPluginJars(Path pluginsDir) {
        try (Stream<Path> entries = Files.list(pluginsDir)) {
            return entries
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .map(p -> p.toAbsolutePath().normalize())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not list plugins directory: " + pluginsDir.toAbsolutePath(), e);
        }
    }

    /**
     * Loads the {@code sonar-java-plugin} JAR found in {@code pluginsDir}.
     *
     * <p>Installs the global SonarLint log target first ({@link EngineLog})
     * because {@link PluginsLoader#load} logs and throws without one.
     *
     * @param pluginsDir directory containing {@code sonar-java-plugin-*.jar}
     * @return the loaded plugins, including the {@code java} plugin
     * @throws IllegalStateException if no Java plugin JAR is present
     */
    public static LoadedPlugins loadJava(Path pluginsDir) {
        EngineLog.install();

        Path javaPluginJar = findJavaPluginJar(pluginsDir);

        PluginsLoader.Configuration config = new PluginsLoader.Configuration(
                Set.of(javaPluginJar),
                Set.of(SonarLanguage.JAVA),
                false,
                Optional.empty());
        PluginsLoadResult result = new PluginsLoader().load(config, Set.of());
        return result.getLoadedPlugins();
    }

    private static Path findJavaPluginJar(Path pluginsDir) {
        try (Stream<Path> entries = Files.list(pluginsDir)) {
            return entries
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("sonar-java-plugin-") && name.endsWith(".jar");
                    })
                    .findFirst()
                    .map(Path::toAbsolutePath)
                    .orElseThrow(() -> new IllegalStateException(
                            "no sonar-java-plugin-*.jar found in " + pluginsDir.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not list plugins directory: " + pluginsDir.toAbsolutePath(), e);
        }
    }
}
