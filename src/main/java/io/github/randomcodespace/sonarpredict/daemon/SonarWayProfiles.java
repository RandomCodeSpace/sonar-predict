package io.github.randomcodespace.sonarpredict.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

import io.github.randomcodespace.sonarpredict.protocol.Json;

/**
 * The bundled "Sonar way" default quality profiles, read straight from the
 * vendored analyzer plugin JARs.
 *
 * <p><b>Why this exists:</b> with no external {@code --config} profile, the
 * daemon must still analyze every detected language. Each SonarSource analyzer
 * ships its default profile as a JSON resource at
 * {@code org/sonar/l10n/<l10n>/rules/<repo>/Sonar_way_profile.json} — a JSON
 * object with a {@code ruleKeys} array of bare SonarQube keys. The engine rule
 * key is {@code <repo>:<sqKey>}; the {@code <repo>} directory segment is also
 * the rule-key prefix the engine emits.
 *
 * <p>This replaces the curated {@code JavaRuleSet}/{@code PythonRuleSet}/
 * {@code JavaScriptRuleSet} subsets: the bundled profile is the analyzer's own
 * recommended rule set and covers all twelve v1 languages, not just three.
 *
 * <p><b>TypeScript.</b> The JavaScript analyzer drives both {@code .js} and
 * {@code .ts} files, but its TypeScript sensor activates rules from a separate
 * {@code typescript} rule repository ({@code typescript:Sxxx}) — registered
 * programmatically by the plugin, with no {@code Sonar_way_profile.json}
 * resource of its own. The analyzer keeps the {@code javascript} and
 * {@code typescript} repositories in lockstep on rule numbers, so this class
 * re-emits the JavaScript Sonar way rule numbers under the {@code typescript:}
 * prefix for {@link SonarLanguage#TS}. Emitting them as {@code javascript:}
 * would leave the TypeScript sensor with an empty active-rule set — every
 * {@code .ts} file analyzed clean, with no issue and no warning.
 */
public final class SonarWayProfiles {

    /** Matches {@code org/sonar/l10n/<l10n>/rules/<repo>/Sonar_way_profile.json}. */
    private static final Pattern PROFILE_JSON = Pattern.compile(
            "^org/sonar/l10n/[^/]+/rules/([^/]+)/Sonar_way_profile\\.json$");

    /**
     * Rule-repository directory → the {@link SonarLanguage}s whose files the
     * engine analyzes with that repository's rules. {@code javascript} serves
     * both JS and TS; every other repo serves a single language.
     */
    private static final Map<String, Set<SonarLanguage>> REPO_TO_LANGUAGES = Map.of(
            "java", Set.of(SonarLanguage.JAVA),
            "python", Set.of(SonarLanguage.PYTHON),
            "javascript", Set.of(SonarLanguage.JS, SonarLanguage.TS),
            "css", Set.of(SonarLanguage.CSS),
            "php", Set.of(SonarLanguage.PHP),
            "kotlin", Set.of(SonarLanguage.KOTLIN),
            "go", Set.of(SonarLanguage.GO),
            "ruby", Set.of(SonarLanguage.RUBY),
            "scala", Set.of(SonarLanguage.SCALA),
            "xml", Set.of(SonarLanguage.XML));

    /** {@code Web} is the HTML analyzer's repo; kept separate so the map above stays ≤10 entries. */
    private static final Map<String, Set<SonarLanguage>> WEB_REPO =
            Map.of("Web", Set.of(SonarLanguage.HTML));

    /**
     * Engine rule-key repository prefix for a language, when it differs from
     * the profile-resource directory the rules were read from.
     *
     * <p>Only {@link SonarLanguage#TS} needs this: its rules live in the
     * {@code javascript} profile resource, but the JavaScript analyzer's
     * TypeScript sensor activates them from the {@code typescript} rule
     * repository. Every other language's engine repository equals its
     * resource directory, so it is absent here.
     */
    private static final Map<SonarLanguage, String> ENGINE_REPO_OVERRIDE =
            Map.of(SonarLanguage.TS, "typescript");

    private final Map<SonarLanguage, List<String>> ruleKeysByLanguage;

    private SonarWayProfiles(Map<SonarLanguage, List<String>> ruleKeysByLanguage) {
        this.ruleKeysByLanguage = ruleKeysByLanguage;
    }

    /**
     * Loads every analyzer's bundled Sonar way profile from the plugin JARs in
     * {@code pluginsDir}.
     *
     * @param pluginsDir directory holding the vendored analyzer plugin JARs
     * @return the loaded default profiles, keyed by {@link SonarLanguage}
     * @throws IllegalStateException if the directory holds no plugin JARs
     */
    public static SonarWayProfiles load(Path pluginsDir) {
        Map<SonarLanguage, List<String>> byLanguage = new EnumMap<>(SonarLanguage.class);
        int jarCount = 0;
        try (Stream<Path> entries = Files.list(pluginsDir)) {
            for (Path jar : entries
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .toList()) {
                jarCount++;
                indexJar(jar, byLanguage);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not list plugins directory: " + pluginsDir.toAbsolutePath(), e);
        }
        if (jarCount == 0) {
            throw new IllegalStateException(
                    "no analyzer plugin JARs in " + pluginsDir.toAbsolutePath());
        }
        return new SonarWayProfiles(byLanguage);
    }

    /**
     * Suppresses {@code java:S5042} ("expanding an archive file") on this
     * method: we never resolve a {@link JarEntry#getName()} to a filesystem
     * path. Entry names are matched against a strict {@link #PROFILE_JSON}
     * regex and used only to fetch the entry's bytes via
     * {@link JarFile#getInputStream(java.util.zip.ZipEntry)} for in-memory
     * parsing — no zip-slip surface exists here.
     */
    @SuppressWarnings("java:S5042")
    private static void indexJar(Path jarPath, Map<SonarLanguage, List<String>> byLanguage) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var it = jar.entries();
            while (it.hasMoreElements()) {
                JarEntry entry = it.nextElement();
                Matcher m = PROFILE_JSON.matcher(entry.getName());
                if (!m.matches()) {
                    continue;
                }
                String repo = m.group(1);
                Set<SonarLanguage> languages = languagesFor(repo);
                if (languages.isEmpty()) {
                    continue;
                }
                List<String> bareKeys = readRuleKeys(jar, entry);
                for (SonarLanguage language : languages) {
                    // The engine rule-key prefix is the resource-directory repo,
                    // unless the language overrides it (TypeScript: rules read
                    // from the `javascript` resource, activated under `typescript`).
                    String engineRepo = ENGINE_REPO_OVERRIDE.getOrDefault(language, repo);
                    List<String> ruleKeys = prefix(bareKeys, engineRepo);
                    byLanguage.merge(language, ruleKeys, SonarWayProfiles::mergeDistinct);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read plugin JAR: " + jarPath, e);
        }
    }

    private static Set<SonarLanguage> languagesFor(String repo) {
        Set<SonarLanguage> languages = REPO_TO_LANGUAGES.get(repo);
        if (languages != null) {
            return languages;
        }
        return WEB_REPO.getOrDefault(repo, Set.of());
    }

    private static List<String> mergeDistinct(List<String> existing, List<String> added) {
        List<String> merged = new ArrayList<>(existing);
        for (String key : added) {
            if (!merged.contains(key)) {
                merged.add(key);
            }
        }
        return merged;
    }

    /** Reads the {@code ruleKeys} array — the bare SonarQube keys, unprefixed. */
    private static List<String> readRuleKeys(JarFile jar, JarEntry entry)
            throws IOException {
        JsonNode json;
        try (InputStream in = jar.getInputStream(entry)) {
            json = Json.mapper().readTree(in);
        }
        List<String> keys = new ArrayList<>();
        if (json == null || !json.isObject()) {
            return keys;
        }
        JsonNode array = json.get("ruleKeys");
        if (array == null || !array.isArray()) {
            return keys;
        }
        for (JsonNode keyNode : array) {
            if (keyNode.isTextual() && !keyNode.asText().isBlank()) {
                keys.add(keyNode.asText());
            }
        }
        return keys;
    }

    /** Prefixes each bare SonarQube key with {@code <repo>:} to form the engine rule key. */
    private static List<String> prefix(List<String> bareKeys, String repo) {
        List<String> keys = new ArrayList<>(bareKeys.size());
        for (String bare : bareKeys) {
            keys.add(repo + ":" + bare);
        }
        return keys;
    }

    /**
     * The Sonar way default-active rule keys for {@code language}.
     *
     * @param language the analyzer language
     * @return the {@code <repo>:<sqKey>}-prefixed rule keys, or an empty list if
     *         this language's analyzer ships no Sonar way profile
     */
    public List<String> ruleKeysFor(SonarLanguage language) {
        return ruleKeysByLanguage.getOrDefault(language, List.of());
    }

    /**
     * An empty view of these profiles — every language resolves to no rules.
     * Test seam for exercising the "detected language with no rule set"
     * warning path without depending on a missing plugin resource.
     *
     * @return a {@code SonarWayProfiles} that returns an empty list for every language
     */
    SonarWayProfiles restrictedToNone() {
        return new SonarWayProfiles(Map.of());
    }
}
