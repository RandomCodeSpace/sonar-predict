package dev.sonarcli.daemon;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

/**
 * Detects the analyzer {@link SonarLanguage} for a file from its name extension.
 *
 * <p>The extension table is built from {@link SonarLanguage#getDefaultFileSuffixes()}
 * for the v1 language set (Java, Python, JS, TS, PHP, Kotlin, Go, Ruby, Scala,
 * HTML, XML, CSS). Some analyzers report suffixes without a leading dot
 * (e.g. PHP: {@code php,php3,...}); both forms are normalized here.
 */
public final class LanguageDetector {

    /** v1 language set, in priority order — first registration wins on a shared suffix. */
    private static final SonarLanguage[] SUPPORTED = {
            SonarLanguage.JAVA,
            SonarLanguage.PYTHON,
            SonarLanguage.JS,
            SonarLanguage.TS,
            SonarLanguage.PHP,
            SonarLanguage.KOTLIN,
            SonarLanguage.GO,
            SonarLanguage.RUBY,
            SonarLanguage.SCALA,
            SonarLanguage.HTML,
            SonarLanguage.XML,
            SonarLanguage.CSS,
    };

    /** Lower-cased extension (no leading dot) -> language. */
    private static final Map<String, SonarLanguage> BY_EXTENSION = buildExtensionMap();

    private LanguageDetector() {
    }

    private static Map<String, SonarLanguage> buildExtensionMap() {
        Map<String, SonarLanguage> map = new LinkedHashMap<>();
        for (SonarLanguage language : SUPPORTED) {
            for (String suffix : language.getDefaultFileSuffixes()) {
                String ext = normalizeExtension(suffix);
                if (!ext.isEmpty()) {
                    map.putIfAbsent(ext, language);
                }
            }
        }
        return Map.copyOf(map);
    }

    /** Strips a leading dot and lower-cases; {@code ".PY"} and {@code "py"} both yield {@code "py"}. */
    private static String normalizeExtension(String raw) {
        String ext = raw.startsWith(".") ? raw.substring(1) : raw;
        return ext.toLowerCase(Locale.ROOT);
    }

    /**
     * Detects the language of {@code fileName} from its extension.
     *
     * @param fileName a file name or path; only the text after the last {@code .} matters
     * @return the detected language, or {@link Optional#empty()} if the extension
     *         is unknown or the name has none
     */
    public static Optional<SonarLanguage> detect(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        int lastSep = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (dot < 0 || dot < lastSep || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(BY_EXTENSION.get(ext));
    }
}
