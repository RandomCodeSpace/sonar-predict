package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.plugin.commons.LoadedPlugins;

class PluginRuntimeTest {

    /** The vendored plugin directory, relative to the daemon module root. */
    private static final Path PLUGINS_DIR = Paths.get("plugins");

    @Test
    @DisplayName("loadJava loads the vendored sonar-java plugin without throwing")
    void loadJava_loadsVendoredPlugin() {
        LoadedPlugins plugins = assertDoesNotThrow(() -> PluginRuntime.loadJava(PLUGINS_DIR));

        assertNotNull(plugins);
        assertTrue(plugins.getAllPluginInstancesByKeys().containsKey("java"),
                "expected the 'java' plugin to be loaded, got: "
                        + plugins.getAllPluginInstancesByKeys().keySet());
    }

    @Test
    @DisplayName("loadAll loads every vendored analyzer plugin without throwing")
    void loadAll_loadsAllVendoredPlugins() {
        LoadedPlugins plugins = assertDoesNotThrow(() -> PluginRuntime.loadAll(PLUGINS_DIR));

        assertNotNull(plugins);
        Set<String> keys = plugins.getAllPluginInstancesByKeys().keySet();
        // Plugin keys are the SonarLanguage plugin keys (see SonarLanguage.getPluginKey).
        for (String expected : Set.of("java", "python", "javascript", "php",
                "kotlin", "go", "ruby", "sonarscala", "web", "xml")) {
            assertTrue(keys.contains(expected),
                    "expected plugin '" + expected + "' to be loaded, got: " + keys);
        }
    }

    @Test
    @DisplayName("loadFrom loads exactly the named jars and ignores an extra jar in the directory")
    void loadFrom_ignoresExtraJarInDirectory(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        // A plugins directory holding the real java plugin PLUS a rogue jar
        // that is not in the explicit allow-list.
        Path realJava;
        try (var entries = java.nio.file.Files.list(PLUGINS_DIR)) {
            realJava = entries
                    .filter(p -> p.getFileName().toString().startsWith("sonar-java-plugin-"))
                    .findFirst()
                    .orElseThrow();
        }
        Path copiedJava = dir.resolve(realJava.getFileName().toString());
        java.nio.file.Files.copy(realJava, copiedJava);
        Path rogue = dir.resolve("rogue-plugin-1.0.0.jar");
        java.nio.file.Files.write(rogue, "not a real plugin".getBytes());

        // loadFrom is given ONLY the real java jar — the rogue jar in the same
        // directory must never reach the plugin loader.
        LoadedPlugins plugins = assertDoesNotThrow(
                () -> PluginRuntime.loadFrom(Set.of(copiedJava)));
        assertTrue(plugins.getAllPluginInstancesByKeys().containsKey("java"),
                "loadFrom must load the named java plugin");
    }

    @Test
    @DisplayName("loadFrom rejects a jar path that does not exist")
    void loadFrom_rejectsMissingJar(@org.junit.jupiter.api.io.TempDir Path dir) {
        Path missing = dir.resolve("nonexistent-plugin.jar");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> PluginRuntime.loadFrom(Set.of(missing)),
                "loadFrom must reject a jar that is not present on disk");
    }

    @Test
    @DisplayName("loadedLanguagesFor reflects only languages whose analyzer plugin actually loaded")
    void loadedLanguagesFor_reflectsActuallyLoadedPlugins() {
        // Simulate the JS/TS/CSS analyzer being SKIPPED (no Node on the host):
        // its 'javascript' plugin key is absent from the loaded-plugin keys.
        Set<String> loadedWithoutJs = Set.of(
                "java", "python", "php", "kotlin", "go", "ruby", "sonarscala", "web", "xml");
        Set<org.sonarsource.sonarlint.core.commons.api.SonarLanguage> languages =
                PluginRuntime.loadedLanguagesFor(loadedWithoutJs);

        assertFalse(languages.contains(
                        org.sonarsource.sonarlint.core.commons.api.SonarLanguage.JS),
                "JS must be excluded when the javascript plugin did not load: " + languages);
        assertFalse(languages.contains(
                        org.sonarsource.sonarlint.core.commons.api.SonarLanguage.TS),
                "TS must be excluded when the javascript plugin did not load: " + languages);
        assertTrue(languages.contains(
                        org.sonarsource.sonarlint.core.commons.api.SonarLanguage.JAVA),
                "JAVA must be present when the java plugin loaded: " + languages);
    }

    @Test
    @DisplayName("loadedLanguagesFor includes JS/TS when the javascript plugin loaded")
    void loadedLanguagesFor_includesJsWhenPluginLoaded() {
        Set<String> allLoaded = Set.of(
                "java", "python", "javascript", "php", "kotlin", "go",
                "ruby", "sonarscala", "web", "xml");
        Set<org.sonarsource.sonarlint.core.commons.api.SonarLanguage> languages =
                PluginRuntime.loadedLanguagesFor(allLoaded);

        assertTrue(languages.contains(
                        org.sonarsource.sonarlint.core.commons.api.SonarLanguage.JS),
                "JS must be present when the javascript plugin loaded: " + languages);
    }
}
