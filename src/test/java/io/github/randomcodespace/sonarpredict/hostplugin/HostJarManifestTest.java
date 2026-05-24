package io.github.randomcodespace.sonarpredict.hostplugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HostJarManifestTest {

    @Test
    void hostJarHasRequiredPluginManifestEntries() throws IOException {
        Path hostJar = findHostJar();

        try (JarFile jar = new JarFile(hostJar.toFile())) {
            Manifest mf = jar.getManifest();
            var attrs = mf.getMainAttributes();

            assertThat(attrs.getValue("Plugin-Class"))
                    .isEqualTo("io.github.randomcodespace.sonarpredict.hostplugin.SonarPredictHostPlugin");
            assertThat(attrs.getValue("Plugin-Key")).isEqualTo("sonarpredict-host");
            assertThat(attrs.getValue("Plugin-Name")).isEqualTo("Sonar Predictor Host");
            assertThat(attrs.getValue("Plugin-Version")).isNotBlank();
            assertThat(attrs.getValue("Sonar-Version")).isEqualTo("9.9");
            assertThat(attrs.getValue("SonarLint-Supported")).isEqualTo("true");
        }
    }

    @Test
    void hostJarContainsOnlyHostPluginClasses() throws IOException {
        Path hostJar = findHostJar();

        try (JarFile jar = new JarFile(hostJar.toFile())) {
            List<String> classEntries = jar.stream()
                    .map(JarEntry::getName)
                    .filter(n -> n.endsWith(".class"))
                    .sorted()
                    .toList();

            assertThat(classEntries).containsExactly(
                    "io/github/randomcodespace/sonarpredict/hostplugin/NoOpAnalysisWarnings.class",
                    "io/github/randomcodespace/sonarpredict/hostplugin/SonarPredictHostPlugin.class");
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
