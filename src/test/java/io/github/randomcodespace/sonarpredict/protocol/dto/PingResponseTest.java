package io.github.randomcodespace.sonarpredict.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class PingResponseTest {

    @Test
    void serializesAndDeserializes() throws Exception {
        PingResponse original = new PingResponse("0.1.0", 1234L, List.of("java", "python"));
        String json = Json.mapper().writeValueAsString(original);
        assertTrue(json.contains("\"daemonVersion\":\"0.1.0\""), json);
        PingResponse restored = Json.mapper().readValue(json, PingResponse.class);
        assertEquals(original, restored);
    }
}
