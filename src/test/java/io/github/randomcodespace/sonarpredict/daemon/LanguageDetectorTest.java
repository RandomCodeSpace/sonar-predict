package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

import java.util.stream.Stream;

class LanguageDetectorTest {

    static final class Cases implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext ctx) {
            return Stream.of(
                    Arguments.of("Main.java", SonarLanguage.JAVA),
                    Arguments.of("app/Service.jav", SonarLanguage.JAVA),
                    Arguments.of("script.py", SonarLanguage.PYTHON),
                    Arguments.of("index.js", SonarLanguage.JS),
                    Arguments.of("widget.jsx", SonarLanguage.JS),
                    Arguments.of("module.ts", SonarLanguage.TS),
                    Arguments.of("Component.tsx", SonarLanguage.TS),
                    Arguments.of("legacy.php", SonarLanguage.PHP),
                    Arguments.of("Main.kt", SonarLanguage.KOTLIN),
                    Arguments.of("server.go", SonarLanguage.GO),
                    Arguments.of("app.rb", SonarLanguage.RUBY),
                    Arguments.of("Job.scala", SonarLanguage.SCALA),
                    Arguments.of("page.html", SonarLanguage.HTML),
                    Arguments.of("pom.xml", SonarLanguage.XML),
                    Arguments.of("theme.css", SonarLanguage.CSS));
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @ArgumentsSource(Cases.class)
    @DisplayName("known extensions detect the right SonarLanguage")
    void detect_knownExtension(String fileName, SonarLanguage expected) {
        Optional<SonarLanguage> result = LanguageDetector.detect(fileName);
        assertTrue(result.isPresent(), "expected a language for " + fileName);
        assertEquals(expected, result.get());
    }

    @Test
    @DisplayName("an unknown extension yields Optional.empty()")
    void detect_unknownExtension_isEmpty() {
        assertTrue(LanguageDetector.detect("notes.txt").isEmpty());
        assertTrue(LanguageDetector.detect("archive.zip").isEmpty());
        assertTrue(LanguageDetector.detect("README").isEmpty());
    }

    @Test
    @DisplayName("detection works on a full path and is case-insensitive")
    void detect_pathAndCaseInsensitive() {
        assertEquals(SonarLanguage.JAVA,
                LanguageDetector.detect("src/main/java/Foo.JAVA").orElseThrow());
        assertEquals(SonarLanguage.PYTHON,
                LanguageDetector.detect("/abs/path/to/x.PY").orElseThrow());
    }
}
