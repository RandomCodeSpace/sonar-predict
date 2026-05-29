package io.github.randomcodespace.sonarpredict.daemon;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

/**
 * The single, authoritative table of analyzer rule-repository facts.
 *
 * <p><b>Why this exists.</b> The same repo-dir &rarr; language relationships used to
 * be encoded in four separate places that had to be edited in lockstep:
 * <ul>
 *   <li>{@code RuleCatalog} &mdash; the indexed analyzer repo set and the
 *       repo &rarr; {@code RuleMetadata.language} (protocol key) override map;</li>
 *   <li>{@code SonarWayProfiles} &mdash; the repo &rarr; served {@link SonarLanguage}s,
 *       the {@code Web} split-out, and the TypeScript engine-repo override;</li>
 *   <li>{@code AnalysisService} &mdash; the profile-rule repo &rarr; protocol-key fallback;</li>
 *   <li>{@code LanguageDetector} &mdash; the ordered v1 language set.</li>
 * </ul>
 * Consolidating them here removes the drift hazard: each call site now reads the
 * exact view it needs from this one table. This is a pure structural dedup; every
 * accessor reproduces its former call site's lookup byte-for-byte.
 *
 * <p>The {@code <repo>} segment is the rule-repository directory name in a plugin
 * JAR resource path ({@code org/sonar/l10n/<l10n>/rules/<repo>/...}); it is also
 * the rule-key prefix the engine emits. An {@link Entry#protocolLanguageKey} is the
 * {@code RuleMetadata.language} / SonarLanguage-key the protocol surfaces. An
 * entry's {@link Entry#servedLanguages} are the {@link SonarLanguage}s whose files
 * the engine analyzes with that repository's rules, each mapped to the engine rule
 * repository its rules activate under (equal to the repo-dir except TypeScript,
 * whose rules read from the {@code javascript} resource but activate under
 * {@code typescript}).
 */
final class LanguageMap {

    /**
     * One analyzer rule repository.
     *
     * @param repo               the repo-dir / rule-key prefix (e.g. {@code "javascript"}, {@code "Web"})
     * @param protocolLanguageKey the protocol/{@code RuleMetadata.language} key (e.g. {@code "js"}, {@code "web"})
     * @param servedLanguages    served {@link SonarLanguage} &rarr; the engine rule repository its
     *                            rules activate under (the engine-repo, == {@code repo} unless overridden)
     */
    record Entry(String repo, String protocolLanguageKey, Map<SonarLanguage, String> servedLanguages) {
        Entry {
            servedLanguages = Map.copyOf(servedLanguages);
        }
    }

    /**
     * The consolidated repo table. Iteration order is irrelevant to every current
     * consumer (the four former maps were unordered {@code Map.of}/{@code Set.of});
     * it is listed here repo-by-repo for readability.
     */
    private static final List<Entry> ENTRIES = List.of(
            new Entry("java", "java", Map.of(SonarLanguage.JAVA, "java")),
            new Entry("python", "py", Map.of(SonarLanguage.PYTHON, "python")),
            // The javascript analyzer drives both .js and .ts files; TS rules read
            // from this resource but activate under the `typescript` engine repo.
            new Entry("javascript", "js",
                    Map.of(SonarLanguage.JS, "javascript", SonarLanguage.TS, "typescript")),
            // typescript is an indexed analyzer repo with its own protocol key, but
            // ships no Sonar_way_profile.json resource: it serves no profile language.
            new Entry("typescript", "ts", Map.of()),
            new Entry("css", "css", Map.of(SonarLanguage.CSS, "css")),
            new Entry("php", "php", Map.of(SonarLanguage.PHP, "php")),
            new Entry("kotlin", "kotlin", Map.of(SonarLanguage.KOTLIN, "kotlin")),
            new Entry("go", "go", Map.of(SonarLanguage.GO, "go")),
            new Entry("ruby", "ruby", Map.of(SonarLanguage.RUBY, "ruby")),
            new Entry("scala", "scala", Map.of(SonarLanguage.SCALA, "scala")),
            // The HTML analyzer's repo is `Web` (capitalized), protocol key `web`.
            new Entry("Web", "web", Map.of(SonarLanguage.HTML, "Web")),
            new Entry("xml", "xml", Map.of(SonarLanguage.XML, "xml")));

    private static final Map<String, Entry> BY_REPO = indexByRepo();

    /**
     * The v1 language set in priority order &mdash; first registration wins on a
     * shared file suffix in {@code LanguageDetector}. This ordering is its own fact
     * (it is not derivable from the per-repo served sets), so it is listed
     * explicitly; it is the single authoritative copy nonetheless.
     */
    private static final List<SonarLanguage> DETECTION_ORDER = List.of(
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
            SonarLanguage.CSS);

    private static final Set<String> ANALYZER_REPOS = indexRepoNames();

    private LanguageMap() {
    }

    private static Map<String, Entry> indexByRepo() {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : ENTRIES) {
            map.put(e.repo(), e);
        }
        return Map.copyOf(map);
    }

    private static Set<String> indexRepoNames() {
        Set<String> repos = new LinkedHashSet<>();
        for (Entry e : ENTRIES) {
            repos.add(e.repo());
        }
        return Set.copyOf(repos);
    }

    /**
     * The SonarSource analyzer rule-repository directory names (the {@code <repo>}
     * segments that are indexed; also the rule-key prefixes the engine emits).
     *
     * <p>Replaces {@code RuleCatalog.ANALYZER_REPOS}.
     *
     * @return the indexed analyzer repo-dir names
     */
    static Set<String> analyzerRepos() {
        return ANALYZER_REPOS;
    }

    /**
     * The protocol / {@code RuleMetadata.language} key for {@code repo}, falling
     * back to {@code repo} itself when the repo is not in the table.
     *
     * <p>Reproduces both {@code RuleCatalog.REPO_TO_LANGUAGE.getOrDefault(repo, repo)}
     * and {@code AnalysisService.REPO_TO_LANGUAGE_KEY.getOrDefault(repo, repo)} &mdash;
     * the two were byte-identical (the four explicit overrides
     * python&rarr;py, javascript&rarr;js, typescript&rarr;ts, Web&rarr;web; every
     * other repo &rarr; itself).
     *
     * @param repo the rule-repository directory / rule-key prefix
     * @return the protocol language key
     */
    static String protocolLanguageKey(String repo) {
        Entry e = BY_REPO.get(repo);
        return e != null ? e.protocolLanguageKey() : repo;
    }

    /**
     * The {@link SonarLanguage}s whose files the engine analyzes with {@code repo}'s
     * rules, or an empty set if {@code repo} ships no Sonar way profile.
     *
     * <p>Reproduces {@code SonarWayProfiles.languagesFor(repo)} &mdash; the union of
     * the former {@code REPO_TO_LANGUAGES} and the {@code WEB_REPO} split-out, with
     * the {@code typescript} repo (and any unknown repo) resolving to {@code {}}.
     *
     * @param repo the rule-repository directory
     * @return the served languages, or an empty set
     */
    static Set<SonarLanguage> servedLanguages(String repo) {
        Entry e = BY_REPO.get(repo);
        return e != null ? e.servedLanguages().keySet() : Set.of();
    }

    /**
     * The engine rule-key repository prefix for {@code language}'s rules read from
     * {@code repo}'s resource, falling back to {@code repo} when the language has no
     * override.
     *
     * <p>Reproduces {@code ENGINE_REPO_OVERRIDE.getOrDefault(language, repo)} in
     * {@code SonarWayProfiles}: only {@link SonarLanguage#TS} overrides
     * ({@code javascript} resource &rarr; {@code typescript} engine repo); every
     * other language's engine repo equals the resource directory it came from.
     *
     * @param language the served analyzer language
     * @param repo     the resource directory the rules were read from
     * @return the engine rule-key repository prefix
     */
    static String engineRepo(SonarLanguage language, String repo) {
        Entry e = BY_REPO.get(repo);
        if (e != null) {
            String engineRepo = e.servedLanguages().get(language);
            if (engineRepo != null) {
                return engineRepo;
            }
        }
        return repo;
    }

    /**
     * The v1 language set in detection priority order &mdash; first registration
     * wins on a shared file suffix.
     *
     * <p>Replaces {@code LanguageDetector.SUPPORTED}.
     *
     * @return the ordered supported languages
     */
    static List<SonarLanguage> detectionOrder() {
        return DETECTION_ORDER;
    }
}
