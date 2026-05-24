package dev.sonarcli.cli.setup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Fetches a single artifact to a target path and verifies its SHA-256.
 *
 * <p>{@code http}/{@code https} URLs are fetched with {@link HttpClient};
 * {@code file:} URLs are read directly — the latter is the seam the
 * {@code --offline} provisioning path and the deterministic tests use.
 *
 * <p><b>Atomicity.</b> The bytes are streamed to a sibling {@code .part}
 * temporary file while the SHA-256 is computed; only a verified download is
 * moved onto the target path. Any failure — HTTP error, I/O fault, or checksum
 * mismatch — deletes the temporary file, so a failed fetch never leaves a
 * partial or unverified artifact behind.
 */
public final class Downloader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final HttpClient client;

    /** Creates a downloader with a default {@link HttpClient}. */
    public Downloader() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Downloads {@code source} to {@code target}, verifying the downloaded
     * bytes against {@code expectedSha256} (lowercase hex). Creates the target's
     * parent directories if needed.
     *
     * @param source         the artifact URL ({@code http}, {@code https}, or
     *                       {@code file})
     * @param target         the path the verified artifact is written to
     * @param expectedSha256 the expected lowercase-hex SHA-256 of the artifact
     * @throws DownloadException if the fetch fails or the checksum mismatches
     */
    public void fetch(URI source, Path target, String expectedSha256) {
        Path parent = target.toAbsolutePath().getParent();
        Path part = parent.resolve(target.getFileName() + ".part");
        try {
            Files.createDirectories(parent);
            String actual = streamToFile(source, part);
            String expected = expectedSha256.toLowerCase(Locale.ROOT);
            if (!actual.equals(expected)) {
                throw new DownloadException(
                        "SHA-256 checksum mismatch for " + source
                                + ": expected " + expected + " but got " + actual);
            }
            moveOntoTarget(part, target);
        } catch (DownloadException e) {
            deleteQuietly(part);
            throw e;
        } catch (IOException e) {
            deleteQuietly(part);
            throw new DownloadException("could not download " + source + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(part);
            throw new DownloadException("interrupted while downloading " + source, e);
        }
    }

    /** Streams {@code source} into {@code part}, returning the hex SHA-256. */
    private String streamToFile(URI source, Path part)
            throws IOException, InterruptedException {
        if ("file".equalsIgnoreCase(source.getScheme())) {
            try (InputStream in = Files.newInputStream(Path.of(source))) {
                return copyHashing(in, part);
            }
        }
        HttpRequest request = HttpRequest.newBuilder(source)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new DownloadException(
                        "HTTP " + response.statusCode() + " fetching " + source);
            }
            return copyHashing(in, part);
        }
    }

    /** Copies {@code in} to {@code part}, returning the hex SHA-256 of the bytes. */
    private static String copyHashing(InputStream in, Path part) throws IOException {
        MessageDigest digest = sha256Digest();
        try (DigestInputStream digesting = new DigestInputStream(in, digest)) {
            Files.copy(digesting, part, StandardCopyOption.REPLACE_EXISTING);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    /** Moves the verified temp file onto the target, atomically where possible. */
    private static void moveOntoTarget(Path part, Path target) throws IOException {
        try {
            Files.move(part, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; nothing useful to do on failure.
        }
    }
}
