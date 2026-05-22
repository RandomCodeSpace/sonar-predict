package dev.sonarcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.PingResponse;
import dev.sonarcli.protocol.dto.RuleMetadata;

import picocli.CommandLine;

/**
 * Verifies that {@code sonar --help} is a complete, self-sufficient command
 * reference: it must name every subcommand, document the {@code 0}/{@code 1}/
 * {@code 2} exit codes, and carry at least one usage example. Per-subcommand
 * help ({@code check --help}) must list every option that command accepts.
 */
class HelpTest {

    /** A no-op stub daemon — help rendering never contacts it. */
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
            return new RuleMetadata(ruleKey, "n", "java", "MAJOR", "BUG", "<p>d</p>", null);
        }

        @Override
        public List<RuleMetadata> ruleCatalog() {
            return List.of();
        }

        @Override
        public void shutdown() {
        }
    }

    /** A no-op stub daemon control. */
    private static final class StubControl implements DaemonControl {
        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public void start() {
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
    @DisplayName("--help names every subcommand")
    void rootHelpNamesEverySubcommand() {
        Run run = run("--help");

        assertEquals(0, run.exitCode(), "--help must exit 0");
        String help = run.out();
        for (String sub : List.of("check", "analyze", "rules",
                "install-hook", "daemon", "setup", "version")) {
            assertTrue(help.contains(sub),
                    "--help must list the '" + sub + "' subcommand, got:\n" + help);
        }
    }

    @Test
    @DisplayName("--help documents the 0/1/2 exit codes")
    void rootHelpDocumentsExitCodes() {
        String help = run("--help").out();

        assertTrue(help.toLowerCase().contains("exit code"),
                "--help must carry an exit-code section, got:\n" + help);
        assertTrue(help.contains("0") && help.contains("1") && help.contains("2"),
                "--help must list exit codes 0, 1 and 2, got:\n" + help);
        assertTrue(help.toLowerCase().contains("tool error"),
                "--help must explain exit code 2 as a tool error, got:\n" + help);
    }

    @Test
    @DisplayName("--help shows a usage example with --format before the subcommand")
    void rootHelpCarriesUsageExample() {
        String help = run("--help").out();

        assertTrue(help.contains("sonar --format json check --diff"),
                "--help must show the primary usage example with the global --format "
                        + "option before the subcommand, got:\n" + help);
        assertFalse(help.contains("sonar check --diff --format json"),
                "--help must not place the global --format option after the subcommand "
                        + "— the CLI rejects that placement, got:\n" + help);
    }

    @Test
    @DisplayName("check --help shows --format before the subcommand, not after")
    void checkHelpUsesCorrectGlobalOptionPlacement() {
        String help = run("check", "--help").out();

        assertTrue(help.contains("sonar --format json check"),
                "check --help must place the global --format option before the "
                        + "subcommand, got:\n" + help);
        assertFalse(help.contains("check --diff --format json"),
                "check --help must not place --format after the subcommand, got:\n" + help);
    }

    @Test
    @DisplayName("check --help lists --diff, --format, --config and --coverage")
    void checkHelpListsItsOptions() {
        Run run = run("check", "--help");

        assertEquals(0, run.exitCode(), "check --help must exit 0");
        String help = run.out();
        for (String option : List.of("--diff", "--format", "--config", "--coverage")) {
            assertTrue(help.contains(option),
                    "check --help must list the '" + option + "' option, got:\n" + help);
        }
    }

    @Test
    @DisplayName("sonar help renders the same complete reference as --help")
    void helpSubcommandRendersReference() {
        Run run = run("help");

        assertEquals(0, run.exitCode(), "sonar help must exit 0");
        String help = run.out();
        assertTrue(help.contains("check") && help.contains("analyze"),
                "sonar help must list subcommands, got:\n" + help);
    }
}
