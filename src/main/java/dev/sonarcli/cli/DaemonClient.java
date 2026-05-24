package dev.sonarcli.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import dev.sonarcli.protocol.Json;
import dev.sonarcli.protocol.MessageCodec;
import dev.sonarcli.protocol.Method;
import dev.sonarcli.protocol.SocketPaths;
import dev.sonarcli.protocol.WireMessage;
import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.PingResponse;
import dev.sonarcli.protocol.dto.RuleMetadata;

/**
 * RPC client for the analysis daemon over its Unix domain socket.
 *
 * <p><b>Auto-start.</b> Every call connects a fresh {@link SocketChannel} to
 * {@link SocketPaths#socket()}. If the connect fails — no daemon is listening —
 * the client asks {@link DaemonLauncher} to start one and retries exactly once.
 * The launcher's {@code start()} returns only when the socket is accepting, so
 * the retry never races the bind.
 *
 * <p><b>Framing &amp; correlation.</b> Requests and responses are framed
 * {@link WireMessage}s via {@link MessageCodec}. Each request carries a fresh
 * {@code id}; the response is checked to carry the same {@code id} and
 * {@code method}, so a mismatched frame is caught rather than mis-decoded.
 *
 * <p><b>Errors.</b> The daemon reports every failure as a response whose
 * payload is {@code {"error": "..."}} reusing the request method. The client
 * detects that {@code error} field and throws {@link DaemonException}; a bad
 * result is never returned silently. Socket and protocol failures also become
 * {@link DaemonException}.
 *
 * <p>One connection per call: the daemon serves a connection serially, and the
 * CLI issues calls one at a time, so no pooling is needed in v1.
 */
public final class DaemonClient implements DaemonRpc {

    /** JSON field the daemon uses to signal a failure payload. */
    private static final String ERROR_FIELD = "error";

    private final SocketPaths paths;
    private final DaemonLauncher launcher;

    /**
     * @param paths    the daemon socket location
     * @param launcher used to auto-start the daemon when it is not running
     */
    public DaemonClient(SocketPaths paths, DaemonLauncher launcher) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    /** Sends a {@code PING} and returns the daemon's liveness/identity payload. */
    @Override
    public PingResponse ping() {
        return call(Method.PING, null, PingResponse.class);
    }

    /** Sends an {@code ANALYZE} request and returns its findings. */
    @Override
    public AnalyzeResponse analyze(AnalyzeRequest request) {
        Objects.requireNonNull(request, "request");
        return call(Method.ANALYZE, Json.mapper().valueToTree(request), AnalyzeResponse.class);
    }

    /** Looks up static metadata for one rule key. */
    @Override
    public RuleMetadata ruleMetadata(String ruleKey) {
        Objects.requireNonNull(ruleKey, "ruleKey");
        return call(Method.RULE_METADATA, new TextNode(ruleKey), RuleMetadata.class);
    }

    /**
     * Fetches the daemon's whole rule catalog in one round trip — a
     * {@code RULE_METADATA} call with a {@code null} payload, which the daemon
     * answers with a {@code List<RuleMetadata>}.
     */
    @Override
    public java.util.List<RuleMetadata> ruleCatalog() {
        WireMessage response = exchange(
                new WireMessage(newId(), Method.RULE_METADATA, null));
        JsonNode body = response.payload();
        if (body != null && body.isObject() && body.has(ERROR_FIELD)) {
            throw new DaemonException(
                    "daemon error (RULE_METADATA): " + body.get(ERROR_FIELD).asText());
        }
        try {
            return Json.mapper().convertValue(
                    body,
                    Json.mapper().getTypeFactory()
                            .constructCollectionType(java.util.List.class, RuleMetadata.class));
        } catch (IllegalArgumentException e) {
            throw new DaemonException(
                    "could not decode the rule catalog from the daemon response", e);
        }
    }

    /** Sends a {@code SHUTDOWN} request asking the daemon to stop. */
    @Override
    public void shutdown() {
        // The daemon may close the socket as it stops; a clean ack or an EOF
        // both mean the request was honoured.
        try {
            exchange(new WireMessage(newId(), Method.SHUTDOWN, null));
        } catch (DaemonException ignored) {
            // The daemon tearing the connection down mid-response is expected.
        }
    }

    private <T> T call(Method method, JsonNode payload, Class<T> responseType) {
        WireMessage response = exchange(new WireMessage(newId(), method, payload));
        JsonNode body = response.payload();
        if (body != null && body.isObject() && body.has(ERROR_FIELD)) {
            throw new DaemonException(
                    "daemon error (" + method + "): " + body.get(ERROR_FIELD).asText());
        }
        try {
            return Json.mapper().treeToValue(body, responseType);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DaemonException(
                    "could not decode " + responseType.getSimpleName()
                            + " from the daemon response", e);
        }
    }

    /**
     * Sends one request and reads its response, auto-starting the daemon and
     * retrying once if no daemon is listening.
     */
    private WireMessage exchange(WireMessage request) {
        try {
            return roundTrip(request);
        } catch (IOException firstFailure) {
            // No daemon, or a stale socket — start one and retry exactly once.
            launcher.start();
            try {
                return roundTrip(request);
            } catch (IOException secondFailure) {
                throw new DaemonException(
                        "daemon RPC failed after auto-start: " + secondFailure.getMessage(),
                        secondFailure);
            }
        }
    }

    private WireMessage roundTrip(WireMessage request) throws IOException {
        try (SocketChannel channel =
                     SocketChannel.open(UnixDomainSocketAddress.of(paths.socket()));
             OutputStream out = Channels.newOutputStream(channel);
             InputStream in = Channels.newInputStream(channel)) {
            MessageCodec.writeMessage(out, request);
            WireMessage response = MessageCodec.readMessage(in);
            if (!request.id().equals(response.id())) {
                throw new DaemonException("response id mismatch: expected "
                        + request.id() + ", got " + response.id());
            }
            if (request.method() != response.method()) {
                throw new DaemonException("response method mismatch: expected "
                        + request.method() + ", got " + response.method());
            }
            return response;
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
