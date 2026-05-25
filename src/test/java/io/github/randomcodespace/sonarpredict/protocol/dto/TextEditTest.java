package io.github.randomcodespace.sonarpredict.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import org.junit.jupiter.api.Test;

class TextEditTest {

    @Test
    void serializesAndDeserializes() throws Exception {
        TextEdit original = new TextEdit(10, 4, 10, 20, "Optional.empty()");
        String json = Json.mapper().writeValueAsString(original);
        TextEdit restored = Json.mapper().readValue(json, TextEdit.class);
        assertEquals(original, restored);
    }

    @Test
    void roundtripsMultiLineEdit() throws Exception {
        TextEdit original = new TextEdit(10, 0, 14, 6, """
                String s = """ + "\"\"\"" + """
                  multi
                  line
                """ + "\"\"\"" + ";\n");
        String json = Json.mapper().writeValueAsString(original);
        assertEquals(original, Json.mapper().readValue(json, TextEdit.class));
    }

    @Test
    void emptyReplacementMeansDelete() throws Exception {
        TextEdit deletion = new TextEdit(5, 0, 6, 0, "");
        String json = Json.mapper().writeValueAsString(deletion);
        TextEdit restored = Json.mapper().readValue(json, TextEdit.class);
        assertEquals("", restored.replacement());
    }
}
