package io.github.randomcodespace.sonarpredict.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The envelope for every framed message on the CLI↔daemon socket.
 *
 * <p>The {@code payload} is an opaque JSON tree; the receiver converts it to a
 * concrete DTO based on {@code method} (e.g. {@code AnalyzeRequest} for
 * {@code Method.ANALYZE}). The {@code id} correlates a response with its request.
 */
public record WireMessage(
        String id,
        Method method,
        JsonNode payload
) {
}
