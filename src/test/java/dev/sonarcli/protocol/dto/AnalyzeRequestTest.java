package dev.sonarcli.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.sonarcli.protocol.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyzeRequestTest {

    @Test
    void serializesAndDeserializesWithAllFields() throws Exception {
        AnalyzeRequest original = new AnalyzeRequest(
                "/repo", List.of("src/Main.java"), List.of("java"),
                "profile.xml", List.of("target/jacoco.xml"));
        String json = Json.mapper().writeValueAsString(original);
        AnalyzeRequest restored = Json.mapper().readValue(json, AnalyzeRequest.class);
        assertEquals(original, restored);
    }

    @Test
    void allowsNullProfileRef() throws Exception {
        AnalyzeRequest original = new AnalyzeRequest(
                "/repo", List.of("src/Main.java"), List.of(), null, List.of());
        String json = Json.mapper().writeValueAsString(original);
        AnalyzeRequest restored = Json.mapper().readValue(json, AnalyzeRequest.class);
        assertNull(restored.profileRef());
        assertEquals(original, restored);
    }
}
