package dev.sonarcli.protocol;

import java.io.IOException;

/** Thrown when a message frame is malformed, truncated, or exceeds the size limit. */
public class ProtocolException extends IOException {

    public ProtocolException(String message) {
        super(message);
    }
}
