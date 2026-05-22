package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.plugin.commons.LoadedPlugins;

class RuleParameterDefaultsTest {

    /** The vendored plugin directory, relative to the daemon module root. */
    private static final Path PLUGINS_DIR = Path.of("plugins");

    private static RuleParameterDefaults defaults;

    @BeforeAll
    static void extractFromVendoredPlugins() {
        LoadedPlugins plugins = PluginRuntime.loadAll(PLUGINS_DIR);
        defaults = RuleParameterDefaults.extract(plugins);
    }

    @Test
    @DisplayName("go:S100 carries the analyzer's Go function-name format, not the camelCase default")
    void extract_goS100_hasGoFunctionNameFormat() {
        Map<String, String> params = defaults.paramsFor("go:S100");
        assertEquals("^(_|[a-zA-Z0-9]+)$", params.get("format"),
                "go:S100 must expose the Go function-name regex registered in its "
                        + "RulesDefinition, so the engine does not fall back to the "
                        + "camelCase check-field default");
    }

    @Test
    @DisplayName("every loaded analyzer's RulesDefinition is harvested without failure")
    void extract_everyLoadedAnalyzer_harvestedWithoutFailure() {
        // The reflection-driven extraction must succeed for every analyzer, not
        // just the SLANG-based Go plugin: a recorded failure means an analyzer's
        // RulesDefinition could not be driven and its rules would silently lose
        // their parameter defaults.
        assertTrue(defaults.failures().isEmpty(),
                "no analyzer's RulesDefinition extraction may fail, got: "
                        + defaults.failures());

        // Sanity: extraction genuinely harvested the core analyzers — proving
        // "no failures" is not just "nothing ran". The JS-family repositories
        // (javascript/typescript/css) are omitted here because that plugin only
        // loads when a Node.js runtime is present.
        for (String repository : List.of(
                "java", "python", "go", "php", "kotlin", "ruby", "scala", "xml", "Web")) {
            assertTrue(defaults.harvestedRepositories().contains(repository),
                    "expected analyzer repository '" + repository
                            + "' to be harvested, got: " + defaults.harvestedRepositories());
        }
    }

    @Test
    @DisplayName("an unknown or null rule key yields an empty parameter map, never null")
    void paramsFor_unknownKey_yieldsEmptyMap() {
        assertTrue(defaults.paramsFor("go:DoesNotExist").isEmpty(),
                "an unknown rule key must yield an empty map");
        assertTrue(defaults.paramsFor(null).isEmpty(),
                "a null rule key must yield an empty map, not throw");
    }
}
