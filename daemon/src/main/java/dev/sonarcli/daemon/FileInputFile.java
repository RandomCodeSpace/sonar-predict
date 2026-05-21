package dev.sonarcli.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

/**
 * {@link ClientInputFile} backed by a real file on disk.
 *
 * <p>Ported from the engine spike's {@code FileClientInputFile}, extended to
 * carry an explicit {@link SonarLanguage} and to derive a {@code /}-separated
 * relative path from a base directory.
 */
public final class FileInputFile implements ClientInputFile {

    private final Path absolutePath;
    private final String relativePath;
    private final SonarLanguage language;

    /**
     * @param file     the source file (need not be absolute)
     * @param baseDir  directory the file's relative path is computed against
     * @param language the analyzer language for this file
     */
    public FileInputFile(Path file, Path baseDir, SonarLanguage language) {
        this.absolutePath = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Path base = Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath().normalize();
        // Path.relativize uses the OS separator; the engine expects '/'.
        this.relativePath = base.relativize(absolutePath).toString().replace('\\', '/');
        this.language = Objects.requireNonNull(language, "language");
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
    public SonarLanguage language() {
        return language;
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
