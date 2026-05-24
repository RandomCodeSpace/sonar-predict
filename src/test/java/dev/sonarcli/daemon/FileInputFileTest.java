package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

class FileInputFileTest {

    @Test
    @DisplayName("relativePath, uri, contents, isTest and language are correct")
    void fields_areCorrect(@TempDir Path baseDir) throws Exception {
        Path pkgDir = Files.createDirectories(baseDir.resolve("com/example"));
        Path file = pkgDir.resolve("Foo.java");
        String body = "package com.example;\nclass Foo {}\n";
        Files.writeString(file, body, StandardCharsets.UTF_8);

        FileInputFile input = new FileInputFile(file, baseDir, SonarLanguage.JAVA);

        assertEquals("com/example/Foo.java", input.relativePath());
        assertEquals(file.toAbsolutePath().toUri(), input.uri());
        assertEquals(body, input.contents());
        assertFalse(input.isTest());
        assertEquals(SonarLanguage.JAVA, input.language());
        assertEquals(StandardCharsets.UTF_8, input.getCharset());
    }
}
