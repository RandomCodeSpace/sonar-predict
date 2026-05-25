package io.github.randomcodespace.sonarpredict.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.randomcodespace.sonarpredict.protocol.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuickFixTest {

    @Test
    void serializesAndDeserializesSingleFileQuickFix() throws Exception {
        QuickFix original = new QuickFix(
                "Add a private constructor",
                List.of(new FileEdit(
                        "src/main/java/Constants.java",
                        List.of(new TextEdit(3, 0, 3, 0,
                                "    private Constants() { /* utility class */ }\n")))));
        String json = Json.mapper().writeValueAsString(original);
        QuickFix restored = Json.mapper().readValue(json, QuickFix.class);
        assertEquals(original, restored);
    }

    @Test
    void serializesMultiFileQuickFix() throws Exception {
        QuickFix original = new QuickFix(
                "Rename across two files",
                List.of(
                        new FileEdit("A.java", List.of(new TextEdit(1, 6, 1, 13, "Renamed"))),
                        new FileEdit("B.java", List.of(new TextEdit(7, 12, 7, 19, "Renamed")))));
        String json = Json.mapper().writeValueAsString(original);
        assertEquals(original, Json.mapper().readValue(json, QuickFix.class));
    }

    @Test
    void nullFileEditsBecomeEmptyList() {
        QuickFix qf = new QuickFix("noop", null);
        assertTrue(qf.fileEdits().isEmpty());
    }

    @Test
    void fileEditsAreDefensivelyCopied() {
        List<FileEdit> mutable = new ArrayList<>();
        mutable.add(new FileEdit("a.java", List.of()));
        QuickFix qf = new QuickFix("x", mutable);
        mutable.add(new FileEdit("b.java", List.of()));
        assertEquals(1, qf.fileEdits().size());
    }

    @Test
    void fileEditsListIsUnmodifiable() {
        QuickFix qf = new QuickFix("x", List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> qf.fileEdits().add(new FileEdit("a.java", List.of())));
    }
}
