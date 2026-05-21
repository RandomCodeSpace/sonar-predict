package dev.sonarcli.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sonarcli.protocol.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyzeResponseTest {

    @Test
    void serializesAndDeserializes() throws Exception {
        Issue issue = new Issue(
                "java:S1118", "src/Main.java", 10, 4, 10, 20,
                "MAJOR", "CODE_SMELL", "Add a private constructor.");
        AnalysisWarning warning = new AnalysisWarning(null, "no analyzable files in src/gen");
        AnalyzeResponse original = new AnalyzeResponse(List.of(issue), List.of(warning));
        String json = Json.mapper().writeValueAsString(original);
        AnalyzeResponse restored = Json.mapper().readValue(json, AnalyzeResponse.class);
        assertEquals(original, restored);
    }

    @Test
    void supportsEmptyResults() throws Exception {
        AnalyzeResponse original = new AnalyzeResponse(List.of(), List.of());
        String json = Json.mapper().writeValueAsString(original);
        AnalyzeResponse restored = Json.mapper().readValue(json, AnalyzeResponse.class);
        assertEquals(original, restored);
    }
}
