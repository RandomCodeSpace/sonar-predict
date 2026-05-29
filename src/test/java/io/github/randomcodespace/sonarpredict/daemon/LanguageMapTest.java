package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

/**
 * Equivalence oracle for the structural-dedup refactor (#12).
 *
 * <p>Before consolidation the same repo-dir -> language facts were encoded in four
 * places ({@code RuleCatalog.ANALYZER_REPOS}/{@code REPO_TO_LANGUAGE},
 * {@code SonarWayProfiles.REPO_TO_LANGUAGES}/{@code WEB_REPO}/{@code ENGINE_REPO_OVERRIDE},
 * {@code AnalysisService.REPO_TO_LANGUAGE_KEY}, {@code LanguageDetector.SUPPORTED}).
 * This test pins every one of those mappings as a literal expectation derived from
 * the pre-refactor sources, and asserts {@link LanguageMap} reproduces each one
 * byte-for-byte. It guards the dedup even where behaviour tests do not reach.
 */
class LanguageMapTest {

    // --- RuleCatalog.ANALYZER_REPOS (the indexed analyzer rule-repo dir names) -----------

    @Test
    @DisplayName("analyzerRepos reproduces RuleCatalog.ANALYZER_REPOS exactly")
    void analyzerRepos_matchesRuleCatalogSet() {
        assertEquals(
                Set.of("java", "python", "javascript", "typescript", "css",
                        "php", "kotlin", "go", "ruby", "scala", "Web", "xml"),
                LanguageMap.analyzerRepos());
    }

    // --- RuleCatalog.REPO_TO_LANGUAGE (protocol language key, getOrDefault(repo, repo)) ---
    // Pinned as the exact getOrDefault semantics: explicit override for four repos,
    // otherwise the repo name itself.

    @Test
    @DisplayName("protocolLanguageKey reproduces RuleCatalog.REPO_TO_LANGUAGE getOrDefault")
    void protocolLanguageKey_matchesRuleCatalogMap() {
        // explicit overrides
        assertEquals("py", LanguageMap.protocolLanguageKey("python"));
        assertEquals("js", LanguageMap.protocolLanguageKey("javascript"));
        assertEquals("ts", LanguageMap.protocolLanguageKey("typescript"));
        assertEquals("web", LanguageMap.protocolLanguageKey("Web"));
        // getOrDefault(repo, repo) for every other analyzer repo
        assertEquals("java", LanguageMap.protocolLanguageKey("java"));
        assertEquals("css", LanguageMap.protocolLanguageKey("css"));
        assertEquals("php", LanguageMap.protocolLanguageKey("php"));
        assertEquals("kotlin", LanguageMap.protocolLanguageKey("kotlin"));
        assertEquals("go", LanguageMap.protocolLanguageKey("go"));
        assertEquals("ruby", LanguageMap.protocolLanguageKey("ruby"));
        assertEquals("scala", LanguageMap.protocolLanguageKey("scala"));
        assertEquals("xml", LanguageMap.protocolLanguageKey("xml"));
        // an unknown repo falls through to the repo name itself
        assertEquals("pmd", LanguageMap.protocolLanguageKey("pmd"));
        assertEquals("eslint", LanguageMap.protocolLanguageKey("eslint"));
    }

    // --- AnalysisService.REPO_TO_LANGUAGE_KEY (the profile-rule fallback) -----------------
    // Pre-refactor this was a SEPARATE map with the SAME four entries, values derived
    // from SonarLanguage.getSonarLanguageKey(). Pin that it is byte-identical to the
    // RuleCatalog protocol key AND equal to the enum-derived key.

    @Test
    @DisplayName("protocolLanguageKey reproduces AnalysisService.REPO_TO_LANGUAGE_KEY exactly")
    void protocolLanguageKey_matchesAnalysisServiceFallback() {
        assertEquals(SonarLanguage.PYTHON.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("python"));
        assertEquals(SonarLanguage.JS.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("javascript"));
        assertEquals(SonarLanguage.TS.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("typescript"));
        assertEquals(SonarLanguage.HTML.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("Web"));
        // getOrDefault(repo, repo) for an arbitrary repo (the profile-rule path can
        // hand any repository prefix; it must fall through to the prefix itself).
        assertEquals("somerepo", LanguageMap.protocolLanguageKey("somerepo"));
    }

    // --- SonarWayProfiles.REPO_TO_LANGUAGES + WEB_REPO (languagesFor) ---------------------

    @Test
    @DisplayName("servedLanguages reproduces SonarWayProfiles.languagesFor exactly")
    void servedLanguages_matchesSonarWayProfiles() {
        assertEquals(Set.of(SonarLanguage.JAVA), LanguageMap.servedLanguages("java"));
        assertEquals(Set.of(SonarLanguage.PYTHON), LanguageMap.servedLanguages("python"));
        assertEquals(Set.of(SonarLanguage.JS, SonarLanguage.TS),
                LanguageMap.servedLanguages("javascript"));
        assertEquals(Set.of(SonarLanguage.CSS), LanguageMap.servedLanguages("css"));
        assertEquals(Set.of(SonarLanguage.PHP), LanguageMap.servedLanguages("php"));
        assertEquals(Set.of(SonarLanguage.KOTLIN), LanguageMap.servedLanguages("kotlin"));
        assertEquals(Set.of(SonarLanguage.GO), LanguageMap.servedLanguages("go"));
        assertEquals(Set.of(SonarLanguage.RUBY), LanguageMap.servedLanguages("ruby"));
        assertEquals(Set.of(SonarLanguage.SCALA), LanguageMap.servedLanguages("scala"));
        assertEquals(Set.of(SonarLanguage.XML), LanguageMap.servedLanguages("xml"));
        // WEB_REPO split-out
        assertEquals(Set.of(SonarLanguage.HTML), LanguageMap.servedLanguages("Web"));
    }

    @Test
    @DisplayName("typescript repo serves no profile languages (TS is served via javascript)")
    void servedLanguages_typescriptRepoIsEmpty() {
        // typescript is an ANALYZER_REPO and has a protocol key, but it ships no
        // Sonar_way_profile.json resource of its own: languagesFor("typescript") == {}.
        assertEquals(Set.of(), LanguageMap.servedLanguages("typescript"));
    }

    @Test
    @DisplayName("an unknown repo serves no profile languages")
    void servedLanguages_unknownRepoIsEmpty() {
        assertEquals(Set.of(), LanguageMap.servedLanguages("pmd"));
        assertEquals(Set.of(), LanguageMap.servedLanguages("nonexistent"));
    }

    // --- SonarWayProfiles.ENGINE_REPO_OVERRIDE (engineRepo getOrDefault(language, repo)) --

    @Test
    @DisplayName("engineRepo reproduces ENGINE_REPO_OVERRIDE.getOrDefault(language, repo)")
    void engineRepo_matchesSonarWayProfilesOverride() {
        // The only override: TS rules read from the javascript resource activate
        // under the typescript engine repo.
        assertEquals("typescript", LanguageMap.engineRepo(SonarLanguage.TS, "javascript"));
        // JS (served from the same javascript resource) keeps the resource repo.
        assertEquals("javascript", LanguageMap.engineRepo(SonarLanguage.JS, "javascript"));
        // Every other language: engine repo == the resource directory it came from.
        assertEquals("java", LanguageMap.engineRepo(SonarLanguage.JAVA, "java"));
        assertEquals("python", LanguageMap.engineRepo(SonarLanguage.PYTHON, "python"));
        assertEquals("css", LanguageMap.engineRepo(SonarLanguage.CSS, "css"));
        assertEquals("Web", LanguageMap.engineRepo(SonarLanguage.HTML, "Web"));
        assertEquals("xml", LanguageMap.engineRepo(SonarLanguage.XML, "xml"));
        // getOrDefault falls back to the supplied repo for a non-overridden language
        assertEquals("anyrepo", LanguageMap.engineRepo(SonarLanguage.GO, "anyrepo"));
    }

    // --- LanguageDetector.SUPPORTED (ordered priority list) ------------------------------

    @Test
    @DisplayName("detectionOrder reproduces LanguageDetector.SUPPORTED exactly (order matters)")
    void detectionOrder_matchesLanguageDetectorArray() {
        assertEquals(
                List.of(
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
                        SonarLanguage.CSS),
                LanguageMap.detectionOrder());
    }

    // --- Cross-source consistency: the dedup itself ---------------------------------------

    @Test
    @DisplayName("every served language's protocol key equals its repo's protocol key")
    void servedLanguageProtocolKeysAreConsistent() {
        // For each analyzer repo that serves languages, every served language's own
        // getSonarLanguageKey() must agree with the repo's protocol key for the
        // single-language repos (the only repos AnalysisService/RuleCatalog key on).
        // javascript serves two languages (js, ts) so is asserted separately above.
        assertEquals(SonarLanguage.JAVA.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("java"));
        assertEquals(SonarLanguage.PYTHON.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("python"));
        assertEquals(SonarLanguage.CSS.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("css"));
        assertEquals(SonarLanguage.PHP.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("php"));
        assertEquals(SonarLanguage.KOTLIN.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("kotlin"));
        assertEquals(SonarLanguage.GO.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("go"));
        assertEquals(SonarLanguage.RUBY.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("ruby"));
        assertEquals(SonarLanguage.SCALA.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("scala"));
        assertEquals(SonarLanguage.XML.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("xml"));
        assertEquals(SonarLanguage.HTML.getSonarLanguageKey(),
                LanguageMap.protocolLanguageKey("Web"));
    }

    @Test
    @DisplayName("analyzerRepos is exactly the set of repo-dirs in the table")
    void analyzerReposCoversTableKeys() {
        // The indexed-repo set and the table's repo-dir keys must be the same set:
        // this is the invariant the dedup enforces (one table, no drift).
        assertTrue(LanguageMap.analyzerRepos().contains("typescript"),
                "typescript is an analyzer repo even though it serves no profile");
        assertEquals(12, LanguageMap.analyzerRepos().size());
    }
}
