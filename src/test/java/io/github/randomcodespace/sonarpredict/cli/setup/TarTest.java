package io.github.randomcodespace.sonarpredict.cli.setup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adversarial tests for {@link Tar}'s security-relevant extraction paths:
 * tar-slip rejection, top-dir stripping, GNU long-name handling, truncated-entry
 * rejection, and owner-only execute clamping. Plain extraction is already
 * exercised end-to-end via {@code SetupCommandTest}; these lock in the guards a
 * later "simplification" could silently reopen.
 */
class TarTest {

    @FunctionalInterface
    private interface TarBody {
        void write(TarWriter tar) throws IOException;
    }

    /** Builds a complete (end-blocked) {@code .tar.gz} from a writer recipe. */
    private static byte[] targz(TarBody body) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw);
                TarWriter tar = new TarWriter(gz)) {
            body.write(tar);
        }
        return raw.toByteArray();
    }

    private static void extract(byte[] targz, Path dest, boolean stripTopDir) throws IOException {
        Tar.extractTarGz(new ByteArrayInputStream(targz), dest, stripTopDir);
    }

    @Test
    @DisplayName("a tar-slip entry that escapes the destination is rejected")
    void tarSlipEntryRejected(@TempDir Path dir) throws Exception {
        Path dest = dir.resolve("dest");
        byte[] archive = targz(tar ->
                tar.addFile("../escaped.txt", "pwned".getBytes(StandardCharsets.UTF_8), false));

        IOException ex = assertThrows(IOException.class, () -> extract(archive, dest, false),
                "an entry resolving outside the destination must be rejected");
        assertTrue(ex.getMessage().contains("escapes destination"),
                "the rejection must name the escape, got: " + ex.getMessage());
        assertFalse(Files.exists(dir.resolve("escaped.txt")),
                "the escaping file must never be written outside the destination");
    }

    @Test
    @DisplayName("stripTopDir drops the single leading path component")
    void stripTopDirDropsLeadingComponent(@TempDir Path dest) throws Exception {
        byte[] archive = targz(tar -> {
            tar.addDirectory("jdk-21/");
            tar.addDirectory("jdk-21/bin/");
            tar.addFile("jdk-21/bin/java", "ELF".getBytes(StandardCharsets.UTF_8), true);
        });

        extract(archive, dest, true);

        assertTrue(Files.isRegularFile(dest.resolve("bin/java")),
                "stripping the top dir must land bin/java directly under the destination");
        assertFalse(Files.exists(dest.resolve("jdk-21")),
                "the stripped top-level component must not appear under the destination");
    }

    @Test
    @DisplayName("a GNU long-name entry (>100 bytes) is extracted under its full name")
    void longNameEntryHandled(@TempDir Path dest) throws Exception {
        String longName = "deep/" + "x".repeat(150) + ".txt";
        byte[] archive = targz(tar ->
                tar.addLongNameFile(longName, "ok".getBytes(StandardCharsets.UTF_8), false));

        extract(archive, dest, false);

        assertTrue(Files.isRegularFile(dest.resolve(longName)),
                "a >100-byte GNU long name must be honored, expected: " + longName);
    }

    @Test
    @DisplayName("a truncated entry (declared size exceeds the stream) is rejected")
    @SuppressWarnings("resource") // TarWriter is intentionally not closed: closing
    // writes the end-of-archive blocks, which would pad the stream and defeat the
    // truncation we are simulating. The underlying gz IS closed (try-with-resources).
    void truncatedEntryRejected(@TempDir Path dest) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            new TarWriter(gz).addTruncatedFile(
                    "short.bin", 4096, "only-ten!!".getBytes(StandardCharsets.UTF_8));
        }
        byte[] archive = raw.toByteArray();

        assertThrows(IOException.class, () -> extract(archive, dest, false),
                "a truncated entry must fail loudly, not write a partial file silently");
    }

    @Test
    @DisplayName("an executable entry is clamped to owner-only execute")
    void executableBitClampedToOwner(@TempDir Path dest) throws Exception {
        byte[] archive = targz(tar -> {
            tar.addFile("run.sh", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8), true);
            tar.addFile("data.txt", "plain".getBytes(StandardCharsets.UTF_8), false);
        });

        extract(archive, dest, false);

        Path exec = dest.resolve("run.sh");
        Path plain = dest.resolve("data.txt");
        assertTrue(Files.isRegularFile(exec) && Files.isRegularFile(plain),
                "both entries must be extracted");

        Assumptions.assumeTrue(
                dest.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "execute-bit clamping is only assertable on a POSIX filesystem");

        Set<PosixFilePermission> execPerms = Files.getPosixFilePermissions(exec);
        assertTrue(execPerms.contains(PosixFilePermission.OWNER_EXECUTE),
                "an executable entry must stay owner-executable, got: " + execPerms);
        assertFalse(execPerms.contains(PosixFilePermission.GROUP_EXECUTE)
                        || execPerms.contains(PosixFilePermission.OTHERS_EXECUTE),
                "execute must be clamped to the owner (no group/other), got: " + execPerms);

        Set<PosixFilePermission> plainPerms = Files.getPosixFilePermissions(plain);
        assertFalse(plainPerms.contains(PosixFilePermission.OWNER_EXECUTE)
                        || plainPerms.contains(PosixFilePermission.GROUP_EXECUTE)
                        || plainPerms.contains(PosixFilePermission.OTHERS_EXECUTE),
                "a non-executable entry must carry no execute bits, got: " + plainPerms);
    }
}
