package io.github.randomcodespace.sonarpredict.hostplugin;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.randomcodespace.sonarpredict.daemon.PluginRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sonarsource.sonarlint.core.plugin.commons.LoadedPlugins;

class HostPluginIntegrationTest {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger("ROOT")).addAppender(appender);
    }

    @Test
    void loadAll_includesHostPluginAndNoMissingBeanErrors() throws IOException {
        Path pluginsDir = findDistPluginsDir();

        LoadedPlugins loaded = PluginRuntime.loadAll(pluginsDir);

        assertThat(loaded.getAllPluginInstancesByKeys()).containsKey("sonarpredict-host");
        assertThat(appender.list).noneMatch(e ->
                e.getFormattedMessage() != null
                && e.getFormattedMessage().contains("NoSuchBeanDefinitionException"));
    }

    private static Path findDistPluginsDir() throws IOException {
        Path targetDir = Path.of("target");
        try (Stream<Path> dirs = Files.list(targetDir)) {
            Path distRoot = dirs
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("sonar-predictor-dist-");
                    })
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "dist exploded dir not found in target/; run `mvn package -DskipTests` first"));
            return distRoot.resolve("sonar-predictor").resolve("plugins");
        }
    }
}
