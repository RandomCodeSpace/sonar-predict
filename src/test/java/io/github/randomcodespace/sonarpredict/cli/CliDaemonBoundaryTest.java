package io.github.randomcodespace.sonarpredict.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture guard: the {@code cli} package must not depend on the
 * {@code daemon} package — the two ends of the process split communicate only
 * through the {@code protocol} package and the spawned-process boundary.
 *
 * <p>The single-module collapse removed the compile-time module barrier that
 * used to enforce this, so this test restores the invariant cheaply, with no new
 * dependency: a {@code cli} source that imports a {@code daemon} class fails the
 * build. (The reverse direction — {@code daemon} reading {@code cli.setup}'s
 * bundled manifest for plugin verification — is intentional and not constrained
 * here.)
 */
class CliDaemonBoundaryTest {

    private static final Path CLI_SOURCES = Path.of(
            "src/main/java/io/github/randomcodespace/sonarpredict/cli");
    private static final String FORBIDDEN_IMPORT =
            "import io.github.randomcodespace.sonarpredict.daemon.";

    @Test
    @DisplayName("no cli source imports the daemon package (boundary enforced)")
    void cliDoesNotDependOnDaemon() throws IOException {
        assertTrue(Files.isDirectory(CLI_SOURCES),
                "cli sources must be present at " + CLI_SOURCES.toAbsolutePath());

        List<Path> javaFiles;
        try (Stream<Path> sources = Files.walk(CLI_SOURCES)) {
            javaFiles = sources
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }

        List<String> violations = new ArrayList<>();
        for (Path src : javaFiles) {
            for (String line : Files.readAllLines(src)) {
                if (line.contains(FORBIDDEN_IMPORT)) {
                    violations.add(CLI_SOURCES.relativize(src) + " -> " + line.trim());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "cli must not depend on daemon (route through protocol instead). Violations:\n"
                        + String.join("\n", violations));
    }
}
