package io.github.randomcodespace.sonarpredict.cli.setup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link Downloader} against a loopback {@link HttpServer} serving
 * fixed bytes — no public network is touched. Covers a successful checksum-
 * verified fetch, a checksum-mismatch failure, and that a failed fetch leaves
 * no partial file behind.
 */
class DownloaderTest {

    private HttpServer server;
    private byte[] payload;

    @BeforeEach
    void startServer() throws IOException {
        payload = "fake-artifact-bytes\n".repeat(64).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/artifact.jar", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(payload);
            }
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @DisplayName("fetch downloads a file and verifies its SHA-256")
    void fetchVerifiesChecksum(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("downloaded.jar");

        new Downloader().fetch(url("/artifact.jar"), target, sha256(payload));

        assertTrue(Files.isRegularFile(target), "the target file must exist");
        assertArrayEquals(payload, Files.readAllBytes(target),
                "the downloaded bytes must match what the server served");
    }

    @Test
    @DisplayName("a checksum mismatch throws and leaves no partial file")
    void checksumMismatchLeavesNoFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("downloaded.jar");
        String wrong = "0".repeat(64);

        DownloadException ex = assertThrows(DownloadException.class,
                () -> new Downloader().fetch(url("/artifact.jar"), target, wrong));

        assertTrue(ex.getMessage().toLowerCase().contains("checksum")
                        || ex.getMessage().toLowerCase().contains("sha-256"),
                "the message must name the checksum failure, got: " + ex.getMessage());
        assertFalse(Files.exists(target),
                "a checksum mismatch must leave no partial file behind");
    }

    @Test
    @DisplayName("an HTTP error throws and leaves no partial file")
    void httpErrorLeavesNoFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("downloaded.jar");

        assertThrows(DownloadException.class,
                () -> new Downloader().fetch(url("/missing"), target, "0".repeat(64)));

        assertFalse(Files.exists(target),
                "a failed download must leave no partial file behind");
    }

    @Test
    @DisplayName("fetch supports file: URLs for offline sources")
    void fetchFromFileUrl(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("source.jar");
        Files.write(source, payload);
        Path target = dir.resolve("copied.jar");

        new Downloader().fetch(source.toUri(), target, sha256(payload));

        assertArrayEquals(payload, Files.readAllBytes(target));
    }
}
