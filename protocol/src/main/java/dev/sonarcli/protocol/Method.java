package dev.sonarcli.protocol;

/** RPC methods exchanged between the CLI and the daemon. */
public enum Method {
    ANALYZE,
    RULE_METADATA,
    PING,
    SHUTDOWN
}
