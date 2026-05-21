package dev.sonarcli.spike;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;

/**
 * Minimal {@link ClientInputFile} backed by a real file on disk.
 *
 * The interface (verified via javap against sonarlint-analysis-engine
 * 10.24.0.81415) requires: getPath, isTest, getCharset, getClientObject,
 * inputStream, contents, relativePath, uri. language() and isDirty() are
 * defaults we leave alone.
 */
final class FileClientInputFile implements ClientInputFile {

    private final Path absolutePath;
    private final String relativePath;

    FileClientInputFile(Path absolutePath, String relativePath) {
        this.absolutePath = absolutePath.toAbsolutePath();
        this.relativePath = relativePath;
    }

    @Override
    public String getPath() {
        return absolutePath.toString();
    }

    @Override
    public boolean isTest() {
        return false;
    }

    @Override
    public Charset getCharset() {
        return StandardCharsets.UTF_8;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <G> G getClientObject() {
        return (G) absolutePath;
    }

    @Override
    public InputStream inputStream() throws IOException {
        return Files.newInputStream(absolutePath);
    }

    @Override
    public String contents() throws IOException {
        return Files.readString(absolutePath, StandardCharsets.UTF_8);
    }

    @Override
    public String relativePath() {
        return relativePath;
    }

    @Override
    public URI uri() {
        return absolutePath.toUri();
    }
}
