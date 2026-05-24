package dev.sonarcli.cli;

import java.util.List;

import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.PingResponse;
import dev.sonarcli.protocol.dto.RuleMetadata;

/**
 * The RPC surface the CLI commands depend on, so a command can be exercised
 * against a stub without a live daemon. {@link DaemonClient} is the real,
 * socket-backed implementation.
 */
public interface DaemonRpc {

    /** Returns the daemon's liveness/identity payload. */
    PingResponse ping();

    /** Analyzes the requested files and returns the findings. */
    AnalyzeResponse analyze(AnalyzeRequest request);

    /** Returns static metadata for one rule key. */
    RuleMetadata ruleMetadata(String ruleKey);

    /** Returns static metadata for every rule the daemon knows. */
    List<RuleMetadata> ruleCatalog();

    /** Asks the daemon to stop. */
    void shutdown();
}
