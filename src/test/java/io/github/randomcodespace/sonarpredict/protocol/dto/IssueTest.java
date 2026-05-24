package io.github.randomcodespace.sonarpredict.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import org.junit.jupiter.api.Test;

class IssueTest {

    @Test
    void serializesAndDeserializes() throws Exception {
        Issue original = new Issue(
                "java:S1118", "src/Main.java", 10, 4, 10, 20,
                "MAJOR", "CODE_SMELL", "Add a private constructor.");
        String json = Json.mapper().writeValueAsString(original);
        assertTrue(json.contains("\"ruleKey\":\"java:S1118\""), json);
        Issue restored = Json.mapper().readValue(json, Issue.class);
        assertEquals(original, restored);
    }
}
