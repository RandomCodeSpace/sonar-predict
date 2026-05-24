package io.github.randomcodespace.sonarpredict.daemon;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import io.github.randomcodespace.sonarpredict.protocol.Method;
import io.github.randomcodespace.sonarpredict.protocol.WireMessage;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeRequest;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.PingResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.RuleMetadata;

/**
 * Routes one request {@link WireMessage} to a response {@link WireMessage}.
 *
 * <p>The response always carries the request's {@code id} (so the CLI can
 * correlate it) and the request's {@code method}. Every failure mode — a
 * malformed payload, an unknown rule key, an analyzer crash — is converted to
 * a well-formed error response ({@code {"error": "..."}} payload); no exception
 * escapes {@link #dispatch}. This keeps the socket read→dispatch→write loop in
 * {@code DaemonServer} simple: it never has to recover from a thrown error
 * mid-frame.
 *
 * <p>Stateless except for the warm {@link AnalysisService}/{@link RuleCatalog}
 * it delegates to and the daemon start time used to report uptime.
 */
public final class RequestDispatcher {

    private final AnalysisService analysisService;
    private final RuleCatalog ruleCatalog;
    private final Runnable shutdownSignal;
    private final long startMillis;

    /**
     * @param analysisService the warm analysis core
     * @param ruleCatalog     the rule-metadata catalog (also available via the
     *                        service; passed explicitly so a caller may share one)
     * @param shutdownSignal  invoked once when a {@code SHUTDOWN} request arrives
     */
    public RequestDispatcher(
            AnalysisService analysisService,
            RuleCatalog ruleCatalog,
            Runnable shutdownSignal) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService");
        this.ruleCatalog = Objects.requireNonNull(ruleCatalog, "ruleCatalog");
        this.shutdownSignal = Objects.requireNonNull(shutdownSignal, "shutdownSignal");
        this.startMillis = System.currentTimeMillis();
    }

    /**
     * Dispatches {@code request} and returns its response. Never throws: a
     * failure becomes an error response with the same {@code id}/{@code method}.
     *
     * @param request the incoming framed message
     * @return the response message to write back
     */
    public WireMessage dispatch(WireMessage request) {
        try {
            return switch (request.method()) {
                case PING -> response(request, ping());
                case ANALYZE -> response(request, analyze(request));
                case RULE_METADATA -> response(request, ruleMetadata(request));
                case SHUTDOWN -> response(request, shutdown());
            };
        } catch (RuntimeException e) {
            return error(request, e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage());
        }
    }

    private PingResponse ping() {
        return new PingResponse(
                DaemonVersion.current(),
                System.currentTimeMillis() - startMillis,
                analysisService.loadedLanguages());
    }

    private AnalyzeResponse analyze(WireMessage request) {
        AnalyzeRequest req = read(request.payload(), AnalyzeRequest.class);
        return analysisService.analyze(req);
    }

    /**
     * Serves {@code RULE_METADATA}. A non-blank string payload looks up one
     * rule (and fails if the key is unknown); a {@code null} or blank payload
     * returns the entire catalog as a {@code List<RuleMetadata>}, which is how
     * the CLI builds its {@code RuleMetadataIndex} in one round trip.
     */
    private Object ruleMetadata(WireMessage request) {
        JsonNode payload = request.payload();
        if (payload == null || payload.isNull()
                || (payload.isTextual() && payload.asText().isBlank())) {
            return ruleCatalog.all();
        }
        if (!payload.isTextual()) {
            throw new IllegalArgumentException(
                    "RULE_METADATA payload must be a rule-key string, or null for the full catalog");
        }
        String ruleKey = payload.asText();
        RuleMetadata md = ruleCatalog.lookup(ruleKey);
        if (md == null) {
            throw new IllegalArgumentException("unknown rule key: " + ruleKey);
        }
        return md;
    }

    private ShutdownAck shutdown() {
        shutdownSignal.run();
        return new ShutdownAck(true);
    }

    /** Ack payload for a {@code SHUTDOWN} request. */
    public record ShutdownAck(boolean acknowledged) {
    }

    /** Error payload returned for any failed request. */
    public record ErrorResponse(String error) {
    }

    private static <T> T read(JsonNode payload, Class<T> type) {
        if (payload == null) {
            throw new IllegalArgumentException("missing payload for " + type.getSimpleName());
        }
        try {
            return Json.mapper().treeToValue(payload, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "malformed payload for " + type.getSimpleName() + ": " + e.getMessage());
        }
    }

    private static WireMessage response(WireMessage request, Object payload) {
        return new WireMessage(
                request.id(), request.method(), Json.mapper().valueToTree(payload));
    }

    private static WireMessage error(WireMessage request, String message) {
        return new WireMessage(
                request.id(),
                request.method(),
                Json.mapper().valueToTree(new ErrorResponse(message)));
    }
}
