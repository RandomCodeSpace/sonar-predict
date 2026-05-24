package io.github.randomcodespace.sonarpredict.protocol;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import org.junit.jupiter.api.Test;

class MessageCodecEdgeCasesTest {

    @Test
    void emptyStreamThrowsEofException() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        assertThrows(EOFException.class, () -> MessageCodec.readFrame(in));
    }

    @Test
    void truncatedHeaderThrowsProtocolException() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[] {0x00, 0x00});
        assertThrows(ProtocolException.class, () -> MessageCodec.readFrame(in));
    }

    @Test
    void lengthPrefixAboveTheLimitThrowsProtocolException() {
        // Header declares 0x7FFFFFFF bytes (~2 GB), far above MAX_FRAME_BYTES.
        byte[] hostileHeader = {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        ByteArrayInputStream in = new ByteArrayInputStream(hostileHeader);
        assertThrows(ProtocolException.class, () -> MessageCodec.readFrame(in));
    }

    @Test
    void truncatedBodyThrowsProtocolException() {
        // Header declares 10 bytes, but only 3 body bytes follow.
        byte[] frame = {0x00, 0x00, 0x00, 0x0A, 0x01, 0x02, 0x03};
        ByteArrayInputStream in = new ByteArrayInputStream(frame);
        assertThrows(ProtocolException.class, () -> MessageCodec.readFrame(in));
    }
}
