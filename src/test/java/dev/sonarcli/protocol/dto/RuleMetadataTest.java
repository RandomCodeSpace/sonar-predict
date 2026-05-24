package dev.sonarcli.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.sonarcli.protocol.Json;
import org.junit.jupiter.api.Test;

class RuleMetadataTest {

    @Test
    void serializesAndDeserializes() throws Exception {
        RuleMetadata original = new RuleMetadata(
                "java:S1118", "Utility classes should not have public constructors",
                "java", "MAJOR", "CODE_SMELL",
                "<p>Utility classes ... should not be instantiated.</p>",
                "Add a private constructor to hide the implicit public one.");
        String json = Json.mapper().writeValueAsString(original);
        RuleMetadata restored = Json.mapper().readValue(json, RuleMetadata.class);
        assertEquals(original, restored);
    }

    @Test
    void allowsNullHowToFix() throws Exception {
        RuleMetadata original = new RuleMetadata(
                "java:S100", "Method names should comply with a naming convention",
                "java", "MINOR", "CODE_SMELL", "<p>Shared naming conventions ...</p>", null);
        String json = Json.mapper().writeValueAsString(original);
        RuleMetadata restored = Json.mapper().readValue(json, RuleMetadata.class);
        assertNull(restored.howToFix());
        assertEquals(original, restored);
    }
}
