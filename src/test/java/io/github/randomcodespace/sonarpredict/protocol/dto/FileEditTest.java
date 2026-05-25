package io.github.randomcodespace.sonarpredict.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileEditTest {

    @Test
    void serializesAndDeserializes() throws Exception {
        FileEdit original = new FileEdit(
                "src/main/java/Util.java",
                List.of(new TextEdit(1, 0, 1, 0, "package com.example;\n")));
        String json = Json.mapper().writeValueAsString(original);
        FileEdit restored = Json.mapper().readValue(json, FileEdit.class);
        assertEquals(original, restored);
    }

    @Test
    void nullEditsBecomeEmptyList() {
        FileEdit fileEdit = new FileEdit("x.java", null);
        assertTrue(fileEdit.edits().isEmpty());
    }

    @Test
    void editsAreDefensivelyCopied() {
        List<TextEdit> mutable = new ArrayList<>();
        mutable.add(new TextEdit(1, 0, 1, 5, "hello"));
        FileEdit fileEdit = new FileEdit("x.java", mutable);
        mutable.add(new TextEdit(2, 0, 2, 5, "world"));
        assertEquals(1, fileEdit.edits().size());
    }

    @Test
    void editsListIsUnmodifiable() {
        FileEdit fileEdit = new FileEdit("x.java", List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> fileEdit.edits().add(new TextEdit(1, 0, 1, 0, "x")));
    }

    @Test
    void roundtripPreservesIdentity() throws Exception {
        FileEdit original = new FileEdit(
                "src/Bad.java",
                List.of(
                        new TextEdit(3, 11, 3, 30, "Optional.empty()"),
                        new TextEdit(5, 0, 5, 4, "// fixed: ")));
        String json = Json.mapper().writeValueAsString(original);
        FileEdit restored = Json.mapper().readValue(json, FileEdit.class);
        assertEquals(original, restored);
        assertNotSame(original.edits(), restored.edits());
    }
}
