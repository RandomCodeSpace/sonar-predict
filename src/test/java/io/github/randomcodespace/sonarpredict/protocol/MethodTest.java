package io.github.randomcodespace.sonarpredict.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MethodTest {

    @Test
    void serializesAsItsName() throws Exception {
        String json = Json.mapper().writeValueAsString(Method.ANALYZE);
        assertEquals("\"ANALYZE\"", json);
    }

    @Test
    void deserializesFromItsName() throws Exception {
        Method restored = Json.mapper().readValue("\"RULE_METADATA\"", Method.class);
        assertEquals(Method.RULE_METADATA, restored);
    }
}
