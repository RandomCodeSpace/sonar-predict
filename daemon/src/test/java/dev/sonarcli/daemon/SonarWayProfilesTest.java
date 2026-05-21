package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

class SonarWayProfilesTest {

    private static SonarWayProfiles load() {
        return SonarWayProfiles.load(PluginsDir.resolve());
    }

    @Test
    @DisplayName("Java, Python, JS and Go all have non-empty Sonar way rule lists")
    void load_coreLanguages_nonEmpty() {
        SonarWayProfiles profiles = load();
        for (SonarLanguage lang : List.of(
                SonarLanguage.JAVA, SonarLanguage.PYTHON,
                SonarLanguage.JS, SonarLanguage.GO)) {
            assertFalse(profiles.ruleKeysFor(lang).isEmpty(),
                    lang + " must have a non-empty Sonar way profile");
        }
    }

    @Test
    @DisplayName("every rule key is prefixed with its repository key")
    void load_ruleKeys_areRepoPrefixed() {
        SonarWayProfiles profiles = load();
        for (String key : profiles.ruleKeysFor(SonarLanguage.JAVA)) {
            assertTrue(key.startsWith("java:"),
                    "java rule key must be repo-prefixed, got: " + key);
        }
        for (String key : profiles.ruleKeysFor(SonarLanguage.GO)) {
            assertTrue(key.startsWith("go:"),
                    "go rule key must be repo-prefixed, got: " + key);
        }
    }

    @Test
    @DisplayName("the Java Sonar way profile includes the well-known rule java:S1118")
    void load_java_includesS1118() {
        SonarWayProfiles profiles = load();
        assertTrue(profiles.ruleKeysFor(SonarLanguage.JAVA).contains("java:S1118"),
                "java Sonar way must activate java:S1118");
    }

    @Test
    @DisplayName("TypeScript reuses the javascript repository's Sonar way rules")
    void load_typescript_reusesJavascriptRules() {
        SonarWayProfiles profiles = load();
        List<String> tsKeys = profiles.ruleKeysFor(SonarLanguage.TS);
        assertFalse(tsKeys.isEmpty(),
                "TypeScript must inherit the javascript Sonar way profile");
        for (String key : tsKeys) {
            assertTrue(key.startsWith("javascript:"),
                    "TS active rules use the javascript repo, got: " + key);
        }
    }

    @Test
    @DisplayName("a language whose plugin ships no profile yields an empty list, not an error")
    void load_unknownLanguage_emptyList() {
        SonarWayProfiles profiles = load();
        List<String> keys = profiles.ruleKeysFor(SonarLanguage.COBOL);
        assertNotNull(keys, "an unsupported language must yield a list, not null");
        assertTrue(keys.isEmpty(), "an unsupported language must yield an empty list");
    }
}
