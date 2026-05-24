package dev.sonarcli.protocol.dto;

import java.util.List;

/** Daemon liveness/identity payload returned for {@code Method.PING}. */
public record PingResponse(
        String daemonVersion,
        long uptimeMillis,
        List<String> loadedLanguages
) {
}
