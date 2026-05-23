package dev.sonarcli.daemon;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

/**
 * Classifies a source file as production or test code per SonarQube's standard
 * {@code sonar.sources} vs {@code sonar.tests} distinction.
 *
 * <p>SonarSource rules carry a scope ({@code MAIN}, {@code TEST}, {@code ALL});
 * the analysis engine skips {@code MAIN}-only rules on files marked as test.
 * This is how SonarQube proper lets test code keep its conventional looser
 * style (e.g. {@code methodUnderTest_scenario_expected} method names, longer
 * methods, generic exception handling) without false positives like
 * {@code java:S100}. We do not override any rule here — we only correctly
 * classify files; the analyzer's own scope metadata does the rest.
 *
 * <p>Patterns follow each language's standard build-tool and analyzer
 * conventions:
 * <pre>
 *   Java/Kotlin: src/test/**, *Test.{java,kt}, *Tests.{java,kt}, *IT.java
 *   Scala:       src/test/**, *Test.scala
 *   Go:          *_test.go
 *   Python:      tests/**, test/**, test_*.py, *_test.py
 *   JS/TS:       tests/**, __tests__/**, *.{test,spec}.{js,jsx,ts,tsx}
 *   PHP:         tests/**, *Test.php
 *   Ruby:        spec/**, test/**, *_spec.rb, *_test.rb
 *   CSS/HTML/XML: no test convention; everything is MAIN
 * </pre>
 */
public final class TestPathDetector {

    /** Path segments that indicate test scope regardless of language. */
    private static final List<Pattern> COMMON_PATH_PATTERNS = List.of(
            Pattern.compile("(^|/)src/test/"),
            Pattern.compile("(^|/)tests?/"),
            Pattern.compile("(^|/)__tests__/"),
            Pattern.compile("(^|/)spec/"));

    /** Per-language filename patterns matched against the file's basename. */
    private static final Map<SonarLanguage, List<Pattern>> FILENAME_PATTERNS = Map.ofEntries(
            Map.entry(SonarLanguage.JAVA, List.of(
                    Pattern.compile(".*Test\\.java$"),
                    Pattern.compile(".*Tests\\.java$"),
                    Pattern.compile(".*IT\\.java$"))),
            Map.entry(SonarLanguage.KOTLIN, List.of(
                    Pattern.compile(".*Test\\.kt$"),
                    Pattern.compile(".*Tests\\.kt$"))),
            Map.entry(SonarLanguage.SCALA, List.of(
                    Pattern.compile(".*Test\\.scala$"))),
            Map.entry(SonarLanguage.GO, List.of(
                    Pattern.compile(".*_test\\.go$"))),
            Map.entry(SonarLanguage.PYTHON, List.of(
                    Pattern.compile("test_.*\\.py$"),
                    Pattern.compile(".*_test\\.py$"))),
            Map.entry(SonarLanguage.JS, List.of(
                    Pattern.compile(".*\\.(test|spec)\\.jsx?$"))),
            Map.entry(SonarLanguage.TS, List.of(
                    Pattern.compile(".*\\.(test|spec)\\.tsx?$"))),
            Map.entry(SonarLanguage.PHP, List.of(
                    Pattern.compile(".*Test\\.php$"))),
            Map.entry(SonarLanguage.RUBY, List.of(
                    Pattern.compile(".*_spec\\.rb$"),
                    Pattern.compile(".*_test\\.rb$"))));

    private TestPathDetector() {
        // utility class
    }

    /**
     * @param relativePath '/'-separated path relative to the analysis base
     *                     directory (the engine's standard form)
     * @param language     the file's detected language; may be {@code null}
     * @return {@code true} if the path matches a test convention for the
     *         given language, {@code false} otherwise
     */
    public static boolean isTest(String relativePath, SonarLanguage language) {
        if (relativePath == null || relativePath.isEmpty()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        return matchesCommonTestPath(normalized)
                || matchesLanguageTestFilename(normalized, language);
    }

    /** Path-segment check — cross-language, always runs first. */
    private static boolean matchesCommonTestPath(String normalizedPath) {
        for (Pattern p : COMMON_PATH_PATTERNS) {
            if (p.matcher(normalizedPath).find()) {
                return true;
            }
        }
        return false;
    }

    /** Filename check — per-language. No-op when {@code language} is null. */
    private static boolean matchesLanguageTestFilename(String normalizedPath, SonarLanguage language) {
        if (language == null) {
            return false;
        }
        List<Pattern> patterns = FILENAME_PATTERNS.get(language);
        if (patterns == null) {
            return false;
        }
        int slash = normalizedPath.lastIndexOf('/');
        String filename = slash < 0 ? normalizedPath : normalizedPath.substring(slash + 1);
        for (Pattern p : patterns) {
            if (p.matcher(filename).matches()) {
                return true;
            }
        }
        return false;
    }
}
