package dev.sonarcli.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-prefixed framing for protocol messages.
 *
 * <p>Wire format: a 4-byte big-endian length, followed by that many bytes of
 * UTF-8 JSON encoding a {@link WireMessage}.
 */
public final class MessageCodec {

    /** Hard upper bound on a single frame; guards against corrupt length prefixes. */
    public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private MessageCodec() {
    }

    /** Serializes {@code message} to JSON and writes it as one length-prefixed frame. */
    public static void writeMessage(OutputStream out, WireMessage message) throws IOException {
        writeFrame(out, Json.mapper().writeValueAsBytes(message));
    }

    /** Reads one length-prefixed frame and deserializes it into a {@link WireMessage}. */
    public static WireMessage readMessage(InputStream in) throws IOException {
        return Json.mapper().readValue(readFrame(in), WireMessage.class);
    }

    static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        if (payload.length > MAX_FRAME_BYTES) {
            throw new ProtocolException(
                    "frame too large: " + payload.length + " > " + MAX_FRAME_BYTES);
        }
        byte[] header = {
                (byte) (payload.length >>> 24),
                (byte) (payload.length >>> 16),
                (byte) (payload.length >>> 8),
                (byte) payload.length
        };
        out.write(header);
        out.write(payload);
        out.flush();
    }

    static byte[] readFrame(InputStream in) throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length == 0) {
            throw new EOFException("stream closed before a frame header");
        }
        if (header.length < 4) {
            throw new ProtocolException("truncated frame header: " + header.length + " bytes");
        }
        int length = ((header[0] & 0xFF) << 24)
                | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8)
                | (header[3] & 0xFF);
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new ProtocolException("illegal frame length: " + length);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) {
            throw new ProtocolException(
                    "truncated frame body: expected " + length + ", got " + payload.length);
        }
        return payload;
    }
}
