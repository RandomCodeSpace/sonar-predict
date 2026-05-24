package io.github.randomcodespace.sonarpredict.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.randomcodespace.sonarpredict.protocol.dto.PingResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

    @Test
    void writesAndReadsBackOneMessage() throws Exception {
        JsonNode payload = Json.mapper().valueToTree(
                new PingResponse("0.1.0", 5L, List.of("java")));
        WireMessage original = new WireMessage("ping-1", Method.PING, payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessageCodec.writeMessage(out, original);

        WireMessage restored = MessageCodec.readMessage(
                new ByteArrayInputStream(out.toByteArray()));
        assertEquals(original, restored);
    }

    @Test
    void writesAndReadsBackMultipleMessagesInSequence() throws Exception {
        WireMessage first = new WireMessage("a", Method.PING, Json.mapper().valueToTree("x"));
        WireMessage second = new WireMessage("b", Method.SHUTDOWN, Json.mapper().valueToTree("y"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessageCodec.writeMessage(out, first);
        MessageCodec.writeMessage(out, second);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertEquals(first, MessageCodec.readMessage(in));
        assertEquals(second, MessageCodec.readMessage(in));
    }
}
