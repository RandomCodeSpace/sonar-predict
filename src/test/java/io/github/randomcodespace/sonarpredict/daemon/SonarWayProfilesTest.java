package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("TypeScript reuses the JS Sonar way rule numbers under the typescript repository")
    void load_typescript_reusesJsRulesUnderTypescriptRepo() {
        SonarWayProfiles profiles = load();
        List<String> tsKeys = profiles.ruleKeysFor(SonarLanguage.TS);
        assertFalse(tsKeys.isEmpty(),
                "TypeScript must inherit the JavaScript Sonar way rule numbers");
        // The JS plugin's TypeScript sensor activates rules from the `typescript`
        // rule repository (typescript:Sxxx). The JS analyzer ships no separate
        // typescript Sonar way profile, so the JS profile's rule numbers are
        // re-emitted under the typescript repo prefix. A javascript: prefix here
        // means .ts files run against an empty rule set — a silent clean zero.
        for (String key : tsKeys) {
            assertTrue(key.startsWith("typescript:"),
                    "TS active rules must use the typescript repo, got: " + key);
        }
    }

    @Test
    @DisplayName("the same rule numbers back the JS and TS Sonar way sets, differing only in repo prefix")
    void load_typescript_sameRuleNumbersAsJavascript() {
        SonarWayProfiles profiles = load();
        List<String> jsBare = stripRepo(profiles.ruleKeysFor(SonarLanguage.JS));
        List<String> tsBare = stripRepo(profiles.ruleKeysFor(SonarLanguage.TS));
        assertEquals(jsBare, tsBare,
                "TS Sonar way must carry exactly the JS rule numbers, only the repo prefix differs");
        assertTrue(profiles.ruleKeysFor(SonarLanguage.TS).contains("typescript:S1186"),
                "TS Sonar way must activate typescript:S1186");
    }

    private static List<String> stripRepo(List<String> keys) {
        return keys.stream().map(k -> k.substring(k.indexOf(':') + 1)).toList();
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
