package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import io.github.randomcodespace.sonarpredict.protocol.Method;
import io.github.randomcodespace.sonarpredict.protocol.WireMessage;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeRequest;
import io.github.randomcodespace.sonarpredict.protocol.dto.AnalyzeResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.PingResponse;
import io.github.randomcodespace.sonarpredict.protocol.dto.RuleMetadata;

class RequestDispatcherTest {

    private static final Path FIXTURES = Paths.get("src/test/resources/daemon/fixtures");

    private static AnalysisService service;

    @BeforeAll
    static void warmEngine() {
        service = new AnalysisService();
    }

    @AfterAll
    static void stopEngine() {
        service.close();
    }

    private RequestDispatcher dispatcher(AtomicBoolean shutdownFlag) {
        return new RequestDispatcher(
                service, service.ruleCatalog(), () -> shutdownFlag.set(true));
    }

    private WireMessage analyzeRequest(String id, String... files) {
        AnalyzeRequest req = new AnalyzeRequest(
                FIXTURES.toAbsolutePath().toString(), List.of(files),
                List.of(), null, List.of());
        return new WireMessage(id, Method.ANALYZE, Json.mapper().valueToTree(req));
    }

    @Test
    @DisplayName("PING returns a PingResponse payload carrying the request id")
    void ping_returnsPingResponse() {
        WireMessage response = dispatcher(new AtomicBoolean())
                .dispatch(new WireMessage("ping-1", Method.PING, null));

        assertEquals("ping-1", response.id(), "response must echo the request id");
        assertEquals(Method.PING, response.method());
        PingResponse ping = Json.mapper().convertValue(response.payload(), PingResponse.class);
        assertNotNull(ping.daemonVersion(), "ping must report a daemon version");
        assertTrue(ping.uptimeMillis() >= 0, "ping must report uptime");
        assertFalse(ping.loadedLanguages().isEmpty(), "ping must report loaded languages");
    }

    @Test
    @DisplayName("ANALYZE runs the analysis and returns an AnalyzeResponse with java:S1118")
    void analyze_returnsAnalyzeResponse() {
        WireMessage response = dispatcher(new AtomicBoolean())
                .dispatch(analyzeRequest("an-1", "java/UtilityClass.java"));

        assertEquals("an-1", response.id());
        assertEquals(Method.ANALYZE, response.method());
        AnalyzeResponse analyze =
                Json.mapper().convertValue(response.payload(), AnalyzeResponse.class);
        assertTrue(analyze.issues().stream().anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                "expected java:S1118, got: " + analyze.issues());
    }

    @Test
    @DisplayName("RULE_METADATA returns metadata for a known rule key")
    void ruleMetadata_returnsMetadata() {
        WireMessage request = new WireMessage(
                "rm-1", Method.RULE_METADATA, new TextNode("java:S1118"));
        WireMessage response = dispatcher(new AtomicBoolean()).dispatch(request);

        assertEquals("rm-1", response.id());
        assertEquals(Method.RULE_METADATA, response.method());
        RuleMetadata md = Json.mapper().convertValue(response.payload(), RuleMetadata.class);
        assertEquals("java:S1118", md.ruleKey());
        assertEquals("java", md.language());
    }

    @Test
    @DisplayName("SHUTDOWN returns an ack and signals shutdown via the callback")
    void shutdown_acksAndSignals() {
        AtomicBoolean shutdown = new AtomicBoolean(false);
        WireMessage response = dispatcher(shutdown)
                .dispatch(new WireMessage("sd-1", Method.SHUTDOWN, null));

        assertEquals("sd-1", response.id());
        assertEquals(Method.SHUTDOWN, response.method());
        assertTrue(shutdown.get(), "SHUTDOWN must signal the shutdown callback");
        assertFalse(response.payload().isMissingNode(), "shutdown ack payload must be present");
    }

    @Test
    @DisplayName("a malformed ANALYZE payload yields a well-formed error response, no exception")
    void malformedPayload_yieldsErrorResponse() {
        // ANALYZE with a String payload where an AnalyzeRequest object is required.
        WireMessage bad = new WireMessage(
                "bad-1", Method.ANALYZE, new TextNode("not an analyze request"));
        WireMessage response = dispatcher(new AtomicBoolean()).dispatch(bad);

        assertEquals("bad-1", response.id(), "error response must still echo the id");
        assertNotNull(response.payload(), "error response must carry a payload");
        assertTrue(response.payload().hasNonNull("error"),
                "error response payload must carry an 'error' field, got: " + response.payload());
    }

    @Test
    @DisplayName("RULE_METADATA for an unknown rule key yields an error response")
    void unknownRuleKey_yieldsErrorResponse() {
        WireMessage request = new WireMessage(
                "rm-x", Method.RULE_METADATA, new TextNode("java:NOPE9999"));
        WireMessage response = dispatcher(new AtomicBoolean()).dispatch(request);

        assertEquals("rm-x", response.id());
        assertTrue(response.payload().hasNonNull("error"),
                "unknown rule key must yield an error payload");
    }
}
