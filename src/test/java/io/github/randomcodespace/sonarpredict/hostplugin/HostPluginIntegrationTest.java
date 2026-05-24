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
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.sonarsource.sonarlint.core.plugin.commons.LoadedPlugins;

class HostPluginIntegrationTest {

    @TempDir
    Path tempPluginsDir;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger("ROOT")).addAppender(appender);
    }

    @Test
    void loadAll_includesHostPluginAndNoMissingBeanErrors() throws IOException {
        populateTempPluginsDir(tempPluginsDir);

        LoadedPlugins loaded = PluginRuntime.loadAll(tempPluginsDir);

        assertThat(loaded.getAllPluginInstancesByKeys()).containsKey("sonarpredict-host");
        assertThat(appender.list).noneMatch(e ->
                e.getFormattedMessage() != null
                && e.getFormattedMessage().contains("NoSuchBeanDefinitionException"));
    }

    /**
     * Assembles a temporary plugins directory by hard-linking all JARs from the
     * vendored {@code plugins/} directory (populated at process-test-resources)
     * and the host plugin JAR produced at process-test-classes.
     *
     * <p>Hard-links are used where supported (same filesystem); Files.copy falls
     * back transparently when the link target is on a different device.
     */
    private static void populateTempPluginsDir(Path tempDir) throws IOException {
        // Vendor analyzer JARs — present at process-test-resources phase.
        // Exclude the host JAR (sonar-predictor-*-host.jar) from this glob:
        // antrun copies it into plugins/ at process-test-classes, but findHostJar()
        // below adds it explicitly to avoid a duplicate / FileAlreadyExistsException.
        Path vendorPluginsDir = Path.of("plugins");
        try (Stream<Path> entries = Files.list(vendorPluginsDir)) {
            for (Path jar : (Iterable<Path>) entries
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().contains("-host.jar"))::iterator) {
                linkOrCopy(jar, tempDir.resolve(jar.getFileName()));
            }
        }

        // Host plugin JAR — produced at process-test-classes phase
        Path hostJar = findHostJar();
        linkOrCopy(hostJar, tempDir.resolve(hostJar.getFileName()));
    }

    private static void linkOrCopy(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException e) {
            Files.copy(source.toAbsolutePath(), target);
        }
    }

    private static Path findHostJar() throws IOException {
        Path targetDir = Path.of("target");
        try (Stream<Path> entries = Files.list(targetDir)) {
            return entries
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("sonar-predictor-") && n.endsWith("-host.jar");
                    })
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "host jar not found in target/; run `mvn package -DskipTests` first"));
        }
    }
}
