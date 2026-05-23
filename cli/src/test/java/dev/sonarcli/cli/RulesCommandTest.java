package dev.sonarcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.PingResponse;
import dev.sonarcli.protocol.dto.RuleMetadata;

import picocli.CommandLine;

/**
 * Exercises {@code sonar rules list} and {@code sonar rules show} against a
 * stub {@link DaemonRpc}, so no live daemon is needed.
 */
class RulesCommandTest {

    private static final RuleMetadata S1118 = new RuleMetadata(
            "java:S1118", "Utility classes should not have public constructors",
            "java", "MAJOR", "CODE_SMELL",
            "<p>Utility classes should not be instantiated.</p>",
            "Add a private constructor to hide the implicit public one.");
    private static final RuleMetadata S106 = new RuleMetadata(
            "java:S106", "Standard outputs should not be used directly to log anything",
            "java", "MAJOR", "CODE_SMELL",
            "<p>Use a logger instead of System.out.</p>", null);

    /** A stub daemon serving a fixed two-rule catalog. */
    private static final class StubRpc implements DaemonRpc {
        @Override
        public PingResponse ping() {
            return new PingResponse("0.1.0-test", 1L, List.of("java"));
        }

        @Override
        public AnalyzeResponse analyze(AnalyzeRequest request) {
            return new AnalyzeResponse(List.of(), List.of());
        }

        @Override
        public RuleMetadata ruleMetadata(String ruleKey) {
            if (S1118.ruleKey().equals(ruleKey)) {
                return S1118;
            }
            if (S106.ruleKey().equals(ruleKey)) {
                return S106;
            }
            throw new DaemonException("daemon error (RULE_METADATA): unknown rule key: " + ruleKey);
        }

        @Override
        public List<RuleMetadata> ruleCatalog() {
            return List.of(S106, S1118);
        }

        @Override
        public void shutdown() {
            // Intentionally empty: the rules subcommand tests never trigger
            // daemon shutdown; this method exists only to satisfy the interface.
        }
    }

    /** A no-op daemon control. */
    private static final class StubControl implements DaemonControl {
        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void start() {
            // Intentionally empty: the rules subcommand never starts the
            // daemon; the stub reports it as already running.
        }

        @Override
        public boolean stop() {
            return true;
        }
    }

    private record Run(int exitCode, String out, String err) {
    }

    private static Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        SonarCommand root = new SonarCommand(new StubRpc(), new StubControl());
        CommandLine cmd = SonarCommand.configure(new CommandLine(root))
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        int code = cmd.execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    @Test
    @DisplayName("rules list prints every known rule key and name")
    void rulesListPrintsKeysAndNames() {
        Run run = run("rules", "list");

        assertEquals(0, run.exitCode(), "rules list must exit 0");
        assertTrue(run.out().contains("java:S1118"), "the S1118 key must be listed");
        assertTrue(run.out().contains("java:S106"), "the S106 key must be listed");
        assertTrue(run.out().contains("Utility classes should not have public constructors"),
                "the rule name must be listed alongside the key");
    }

    @Test
    @DisplayName("rules show prints full metadata for a known rule")
    void rulesShowPrintsFullMetadata() {
        Run run = run("rules", "show", "java:S1118");

        assertEquals(0, run.exitCode(), "rules show must exit 0 for a known rule");
        String out = run.out();
        assertTrue(out.contains("java:S1118"), "the rule key must appear");
        assertTrue(out.contains("Utility classes should not have public constructors"),
                "the rule name must appear");
        assertTrue(out.contains("java"), "the language must appear");
        assertTrue(out.contains("MAJOR"), "the severity must appear");
        assertTrue(out.contains("CODE_SMELL"), "the type must appear");
        assertTrue(out.contains("Utility classes should not be instantiated"),
                "the description must appear");
        assertTrue(out.contains("Add a private constructor to hide the implicit public one."),
                "the how-to-fix guidance must appear");
    }

    @Test
    @DisplayName("rules show on an unknown rule key exits 2 with a clear message")
    void rulesShowUnknownKeyExitsTwo() {
        Run run = run("rules", "show", "java:S9999");

        assertEquals(2, run.exitCode(), "an unknown rule key must exit 2");
        assertFalse(run.err().isBlank(), "the error must be explained on stderr");
        assertTrue(run.err().contains("java:S9999"),
                "the error must name the unknown rule key");
    }
}
