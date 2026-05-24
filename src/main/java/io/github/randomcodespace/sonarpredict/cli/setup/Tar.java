package io.github.randomcodespace.sonarpredict.cli.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Minimal reader for {@code .tar.gz} archives — enough to extract a Temurin
 * JRE without pulling in a third-party archive library.
 *
 * <p>Supports the USTAR fields the JRE archives use: regular files, directories,
 * the file mode (so {@code bin/java} stays executable), and the GNU long-name
 * extension ({@code 'L'} type-flag) some archives use for deep paths. Symbolic
 * links inside the archive are skipped — the Temurin JRE layout does not rely
 * on them for {@code bin/java}.
 *
 * <p><b>Path safety.</b> Each entry path is resolved against the destination
 * and rejected if it escapes it ({@code zip-slip} / {@code tar-slip}).
 */
final class Tar {

    private static final int BLOCK = 512;
    private static final int NAME_LEN = 100;
    private static final int MODE_OFFSET = 100;
    private static final int SIZE_OFFSET = 124;
    private static final int TYPE_OFFSET = 156;

    private Tar() {
    }

    /**
     * Extracts a gzip-compressed tar stream into {@code destDir}, optionally
     * stripping the archive's single top-level directory component so that, for
     * a {@code jdk-17.../bin/java} archive, {@code bin/java} lands directly
     * under {@code destDir}.
     *
     * @param in           the {@code .tar.gz} stream
     * @param destDir      the directory to extract into
     * @param stripTopDir  whether to drop the leading path component
     * @throws IOException if the archive is malformed or extraction fails
     */
    static void extractTarGz(InputStream in, Path destDir, boolean stripTopDir)
            throws IOException {
        Path dest = destDir.toAbsolutePath().normalize();
        Files.createDirectories(dest);
        try (GZIPInputStream gz = new GZIPInputStream(in)) {
            byte[] header = new byte[BLOCK];
            String longName = null;
            while (readFully(gz, header) && !isAllZero(header)) {
                String name = longName != null ? longName : cString(header, 0, NAME_LEN);
                longName = null;
                long size = parseOctal(header, SIZE_OFFSET, 12);
                char type = (char) (header[TYPE_OFFSET] & 0xff);
                int mode = (int) parseOctal(header, MODE_OFFSET, 8);

                if (type == 'L') {
                    // GNU long name: the next blocks hold the real entry name.
                    longName = readEntryString(gz, size);
                    continue;
                }
                extractEntry(gz, dest, stripTopDir, name, size, type, mode);
            }
        }
    }

    /**
     * Dispatches one tar entry: skips it when stripping leaves an empty path,
     * creates a directory entry, writes a regular file, or skips any other
     * entry type (symlinks, etc.).
     */
    private static void extractEntry(InputStream gz, Path dest, boolean stripTopDir,
                                     String name, long size, char type, int mode)
            throws IOException {
        String relative = stripTopDir ? stripFirstComponent(name) : name;
        if (relative == null || relative.isEmpty()) {
            skip(gz, padded(size));
            return;
        }
        Path target = dest.resolve(relative).normalize();
        if (!target.startsWith(dest)) {
            throw new IOException("tar entry escapes destination: " + name);
        }

        if (type == '5' || name.endsWith("/")) {
            Files.createDirectories(target);
            skip(gz, padded(size));
        } else if (type == '0' || type == '\0') {
            Files.createDirectories(target.getParent());
            writeEntry(gz, target, size);
            applyMode(target, mode);
        } else {
            // Symlinks and other types: skip the payload, ignore.
            skip(gz, padded(size));
        }
    }

    private static void writeEntry(InputStream in, Path target, long size)
            throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            copyExact(in, out, size);
        }
        skip(in, padded(size) - size);
    }

    private static String readEntryString(InputStream in, long size) throws IOException {
        byte[] buf = new byte[(int) size];
        if (!readFully(in, buf)) {
            throw new IOException("truncated tar long-name entry");
        }
        skip(in, padded(size) - size);
        int end = 0;
        while (end < buf.length && buf[end] != 0) {
            end++;
        }
        return new String(buf, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Suppresses {@code java:S2612}: the {@code OTHERS_EXECUTE} bit is mirrored
     * from the trusted Temurin JRE tar entry's recorded mode (Temurin ships
     * shared binaries as world-executable so any user can run the JRE); we
     * are not granting permissions beyond what the verified archive declares.
     */
    @SuppressWarnings("java:S2612")
    private static void applyMode(Path file, int mode) {
        if ((mode & 0_111) == 0) {
            return; // no execute bits set
        }
        try {
            Set<PosixFilePermission> perms =
                    EnumSet.copyOf(Files.getPosixFilePermissions(file));
            if ((mode & 0_100) != 0) {
                perms.add(PosixFilePermission.OWNER_EXECUTE);
            }
            if ((mode & 0_010) != 0) {
                perms.add(PosixFilePermission.GROUP_EXECUTE);
            }
            if ((mode & 0_001) != 0) {
                // NOSONAR(java:S2612) the execute bit is mirrored from the
                // tar entry's recorded mode (Temurin JRE binaries ship as
                // world-executable); we are not granting permissions beyond
                // what the trusted archive declares.
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Owner-only execute is sufficient for our extracted JRE binaries;
            // group/other execute are not required for the daemon launcher path.
            if (!file.toFile().setExecutable(true, true)) {
                throw new UncheckedIOException(new IOException(
                        "could not mark " + file + " executable "
                                + "(POSIX permissions unsupported and "
                                + "File.setExecutable failed)"));
            }
        }
    }

    private static String stripFirstComponent(String name) {
        int slash = name.indexOf('/');
        return slash < 0 ? "" : name.substring(slash + 1);
    }

    private static long padded(long size) {
        long rem = size % BLOCK;
        return rem == 0 ? size : size + (BLOCK - rem);
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            int c = header[i] & 0xff;
            if (c == 0 || c == ' ') {
                continue;
            }
            if (c < '0' || c > '7') {
                continue;
            }
            value = (value << 3) + (c - '0');
        }
        return value;
    }

    private static String cString(byte[] buf, int offset, int max) {
        int end = offset;
        while (end < offset + max && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static boolean isAllZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) {
                return read > 0 && read == buf.length;
            }
            read += n;
        }
        return true;
    }

    private static void copyExact(InputStream in, OutputStream out, long size)
            throws IOException {
        byte[] buf = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                throw new IOException("truncated tar entry");
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    private static void skip(InputStream in, long count) throws IOException {
        long remaining = count;
        byte[] sink = new byte[8192];
        while (remaining > 0) {
            int n = in.read(sink, 0, (int) Math.min(sink.length, remaining));
            if (n < 0) {
                return;
            }
            remaining -= n;
        }
    }
}
