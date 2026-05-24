package dev.sonarcli.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import dev.sonarcli.protocol.dto.AnalyzeRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class WireMessageTest {

    @Test
    void carriesAMethodAndAnOpaquePayloadThroughJson() throws Exception {
        AnalyzeRequest request = new AnalyzeRequest(
                "/repo", List.of("src/Main.java"), List.of(), null, List.of());
        JsonNode payload = Json.mapper().valueToTree(request);
        WireMessage message = new WireMessage("req-1", Method.ANALYZE, payload);

        String json = Json.mapper().writeValueAsString(message);
        WireMessage restored = Json.mapper().readValue(json, WireMessage.class);

        assertEquals("req-1", restored.id());
        assertEquals(Method.ANALYZE, restored.method());

        AnalyzeRequest restoredPayload =
                Json.mapper().treeToValue(restored.payload(), AnalyzeRequest.class);
        assertEquals(request, restoredPayload);
    }
}
