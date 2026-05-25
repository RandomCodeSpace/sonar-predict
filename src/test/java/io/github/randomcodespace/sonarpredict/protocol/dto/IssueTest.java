package io.github.randomcodespace.sonarpredict.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import java.util.List;
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

    @Test
    void nineArgConstructorDefaultsQuickFixesToEmpty() {
        Issue issue = new Issue(
                "java:S1118", "x", 1, 0, 1, 5, "MAJOR", "CODE_SMELL", "m");
        assertTrue(issue.quickFixes().isEmpty());
    }

    @Test
    void roundtripsWithQuickFixes() throws Exception {
        List<QuickFix> qfs = List.of(
                new QuickFix(
                        "Add a private constructor",
                        List.of(new FileEdit(
                                "src/Main.java",
                                List.of(new TextEdit(3, 0, 3, 0,
                                        "    private Main() {}\n"))))));
        Issue original = new Issue(
                "java:S1118", "src/Main.java", 1, 0, 1, 4,
                "MAJOR", "CODE_SMELL", "Add a private constructor.", qfs);
        String json = Json.mapper().writeValueAsString(original);
        assertTrue(json.contains("quickFixes"), json);
        Issue restored = Json.mapper().readValue(json, Issue.class);
        assertEquals(original, restored);
        assertEquals(1, restored.quickFixes().size());
        assertEquals("Add a private constructor",
                restored.quickFixes().get(0).message());
    }

    @Test
    void nullQuickFixesNormalizedToEmpty() {
        Issue issue = new Issue(
                "k", "f", 1, 0, 1, 0, "MAJOR", "CODE_SMELL", "m", null);
        assertTrue(issue.quickFixes().isEmpty());
    }
}
