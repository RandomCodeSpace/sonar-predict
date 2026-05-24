package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the daemon's analyzer-plugin directory is configurable. The
 * {@code sonar.plugins.dir} system property (or {@code SONAR_PLUGINS_DIR}
 * environment variable) overrides the dev default of a CWD-relative
 * {@code plugins/} directory — the seam the CLI's {@code setup} uses to point
 * the daemon at the provisioned {@code ~/.sonar/<version>/plugins} directory.
 */
class PluginsDirTest {

    @Test
    @DisplayName("defaults to the CWD-relative plugins/ directory")
    void defaultsToCwdPlugins() {
        Path resolved = PluginsDir.resolve(Map.of(), name -> null);
        assertEquals(Path.of("plugins"), resolved,
                "with no override the daemon must use the dev-default plugins/ dir");
    }

    @Test
    @DisplayName("the sonar.plugins.dir system property overrides the default")
    void systemPropertyOverrides() {
        Path resolved = PluginsDir.resolve(
                Map.of(), name -> "sonar.plugins.dir".equals(name) ? "/opt/runtime/plugins" : null);
        assertEquals(Path.of("/opt/runtime/plugins"), resolved,
                "the sonar.plugins.dir property must override the default");
    }

    @Test
    @DisplayName("the SONAR_PLUGINS_DIR environment variable overrides the default")
    void environmentVariableOverrides() {
        Path resolved = PluginsDir.resolve(
                Map.of("SONAR_PLUGINS_DIR", "/srv/plugins"), name -> null);
        assertEquals(Path.of("/srv/plugins"), resolved,
                "the SONAR_PLUGINS_DIR env var must override the default");
    }

    @Test
    @DisplayName("the system property wins over the environment variable")
    void propertyWinsOverEnvironment() {
        Path resolved = PluginsDir.resolve(
                Map.of("SONAR_PLUGINS_DIR", "/srv/plugins"),
                name -> "sonar.plugins.dir".equals(name) ? "/opt/plugins" : null);
        assertEquals(Path.of("/opt/plugins"), resolved,
                "the system property must take precedence over the env var");
    }
}
