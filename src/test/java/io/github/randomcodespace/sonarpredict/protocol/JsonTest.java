package io.github.randomcodespace.sonarpredict.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JsonTest {

    record Sample(String name, int count) {}

    @Test
    void roundTripsARecord() throws Exception {
        Sample original = new Sample("widget", 3);
        String json = Json.mapper().writeValueAsString(original);
        Sample restored = Json.mapper().readValue(json, Sample.class);
        assertEquals(original, restored);
    }

    @Test
    void ignoresUnknownProperties() {
        String json = "{\"name\":\"widget\",\"count\":3,\"extra\":true}";
        assertDoesNotThrow(() -> Json.mapper().readValue(json, Sample.class));
    }
}
