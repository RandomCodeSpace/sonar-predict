package dev.sonarcli.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.sonarcli.protocol.Json;
import org.junit.jupiter.api.Test;

class AnalysisWarningTest {

    @Test
    void serializesAndDeserializes() throws Exception {
        AnalysisWarning original = new AnalysisWarning("src/Bad.java", "could not parse file");
        String json = Json.mapper().writeValueAsString(original);
        AnalysisWarning restored = Json.mapper().readValue(json, AnalysisWarning.class);
        assertEquals(original, restored);
    }

    @Test
    void allowsNullFilePathForProjectLevelWarnings() throws Exception {
        AnalysisWarning original = new AnalysisWarning(null, "no analyzable files found");
        String json = Json.mapper().writeValueAsString(original);
        AnalysisWarning restored = Json.mapper().readValue(json, AnalysisWarning.class);
        assertNull(restored.filePath());
        assertEquals(original, restored);
    }
}
