package io.github.randomcodespace.sonarpredict.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Shared, pre-configured Jackson mapper for all protocol (de)serialization. */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Parse integral JSON numbers as long so codec round-trips yield a
            // canonical JsonNode tree: without this, valueToTree of a long field
            // produces a LongNode while re-parsing the same JSON yields an IntNode,
            // and JsonNode equality is numeric-type-strict, breaking WireMessage.equals.
            .configure(DeserializationFeature.USE_LONG_FOR_INTS, true)
            .build();

    private Json() {
    }

    /** The single shared, thread-safe mapper. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
