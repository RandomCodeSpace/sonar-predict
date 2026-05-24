package io.github.randomcodespace.sonarpredict.cli.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the pinned runtime manifest bundled on the CLI classpath. The
 * manifest is the single source of truth for {@code setup}: an engine
 * coordinate and the ten analyzer plugins.
 */
class ManifestTest {

    @Test
    @DisplayName("the bundled manifest loads from the classpath")
    void loadsFromClasspath() {
        Manifest manifest = Manifest.bundled();
        assertNotNull(manifest, "the bundled manifest must load");
        assertFalse(manifest.version().isBlank(), "manifest version must be set");
    }

    @Test
    @DisplayName("the manifest carries the engine and all ten plugins")
    void carriesEveryArtifact() {
        Manifest manifest = Manifest.bundled();

        assertEquals("sonarlint-analysis-engine", manifest.engine().artifactId());
        assertEquals("10.24.0.81415", manifest.engine().version());

        assertEquals(10, manifest.plugins().size(),
                "the manifest must pin all ten analyzer plugins");
    }

    @Test
    @DisplayName("every artifact has a non-blank coordinate, version, and sha256")
    void everyArtifactHasAChecksum() {
        Manifest manifest = Manifest.bundled();

        assertArtifactComplete(manifest.engine());
        for (Manifest.Artifact plugin : manifest.plugins()) {
            assertArtifactComplete(plugin);
        }
    }

    @Test
    @DisplayName("the java plugin coordinate matches the pinned Maven path")
    void javaPluginCoordinate() {
        Manifest manifest = Manifest.bundled();
        Manifest.Artifact java = manifest.plugins().stream()
                .filter(p -> p.artifactId().equals("sonar-java-plugin"))
                .findFirst()
                .orElseThrow();
        assertEquals("org.sonarsource.java", java.groupId());
        assertEquals("8.15.0.39343", java.version());
    }

    private static void assertArtifactComplete(Manifest.Artifact artifact) {
        assertFalse(artifact.groupId().isBlank(),
                "groupId must be set for " + artifact.artifactId());
        assertFalse(artifact.artifactId().isBlank(), "artifactId must be set");
        assertFalse(artifact.version().isBlank(),
                "version must be set for " + artifact.artifactId());
        assertNotNull(artifact.sha256(), "sha256 must be present for " + artifact.artifactId());
        assertFalse(artifact.sha256().isBlank(),
                "sha256 must be non-blank for " + artifact.artifactId());
        assertTrue(artifact.sha256().length() == 64,
                "sha256 must be 64 hex chars for " + artifact.artifactId());
    }
}
