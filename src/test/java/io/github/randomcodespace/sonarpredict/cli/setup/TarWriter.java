package io.github.randomcodespace.sonarpredict.cli.setup;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal USTAR tar writer — a test fixture for producing fake JRE archives
 * without a third-party archive library. Writes regular files and directories
 * with a controllable execute bit, then the two zero end-of-archive blocks.
 */
final class TarWriter implements Closeable {

    private static final int BLOCK = 512;

    private final OutputStream out;

    TarWriter(OutputStream out) {
        this.out = out;
    }

    /** Writes a directory entry (type-flag {@code '5'}). */
    void addDirectory(String name) throws IOException {
        out.write(header(name.endsWith("/") ? name : name + "/", 0, '5', 0_755));
    }

    /** Writes a regular-file entry; {@code executable} sets mode {@code 0755} vs {@code 0644}. */
    void addFile(String name, byte[] content, boolean executable) throws IOException {
        out.write(header(name, content.length, '0', executable ? 0_755 : 0_644));
        out.write(content);
        int pad = (int) ((BLOCK - (content.length % BLOCK)) % BLOCK);
        if (pad > 0) {
            out.write(new byte[pad]);
        }
    }

    /**
     * Writes a GNU long-name ({@code 'L'}) entry whose payload is
     * {@code longName}, then the real file entry — exercises {@code Tar}'s
     * long-name handling for paths longer than the 100-byte USTAR name field.
     */
    void addLongNameFile(String longName, byte[] content, boolean executable) throws IOException {
        byte[] nameBytes = longName.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[nameBytes.length + 1]; // GNU long names are NUL-terminated
        System.arraycopy(nameBytes, 0, payload, 0, nameBytes.length);
        writeBlocked(header("././@LongLink", payload.length, 'L', 0_644), payload);
        String shortName = longName.length() <= 100
                ? longName : longName.substring(longName.length() - 100);
        writeBlocked(header(shortName, content.length, '0', executable ? 0_755 : 0_644), content);
    }

    /**
     * Writes a file header declaring {@code declaredSize} bytes but emits only
     * {@code body} (fewer) and no end-of-archive blocks — exercises {@code Tar}'s
     * truncated-entry handling. Do not call {@link #close()} afterwards.
     */
    void addTruncatedFile(String name, int declaredSize, byte[] body) throws IOException {
        out.write(header(name, declaredSize, '0', 0_644));
        out.write(body);
    }

    /** Writes a header followed by its payload padded to the 512-byte block. */
    private void writeBlocked(byte[] header, byte[] payload) throws IOException {
        out.write(header);
        out.write(payload);
        int pad = (int) ((BLOCK - (payload.length % BLOCK)) % BLOCK);
        if (pad > 0) {
            out.write(new byte[pad]);
        }
    }

    private static byte[] header(String name, int size, char type, int mode) {
        byte[] h = new byte[BLOCK];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, h, 0, Math.min(nameBytes.length, 100));
        putOctal(h, 100, 8, mode);
        putOctal(h, 108, 8, 0);          // uid
        putOctal(h, 116, 8, 0);          // gid
        putOctal(h, 124, 12, size);
        putOctal(h, 136, 12, 0);         // mtime
        h[156] = (byte) type;
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        h[263] = '0';
        h[264] = '0';
        // checksum: spaces while computing, then the octal sum.
        Arrays.fill(h, 148, 156, (byte) ' ');
        int checksum = 0;
        for (byte b : h) {
            checksum += b & 0xff;
        }
        putOctal(h, 148, 7, checksum);
        h[155] = ' ';
        return h;
    }

    private static void putOctal(byte[] buf, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        int pad = length - 1 - octal.length();
        int i = offset;
        for (int p = 0; p < pad; p++) {
            buf[i++] = '0';
        }
        for (int c = 0; c < octal.length(); c++) {
            buf[i++] = (byte) octal.charAt(c);
        }
        buf[i] = 0;
    }

    @Override
    public void close() throws IOException {
        // Two zero blocks mark the end of the archive.
        out.write(new byte[BLOCK * 2]);
        out.flush();
    }
}
