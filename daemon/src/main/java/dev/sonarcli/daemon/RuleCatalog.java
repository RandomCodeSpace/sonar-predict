package dev.sonarcli.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import dev.sonarcli.protocol.Json;
import dev.sonarcli.protocol.dto.RuleMetadata;

/**
 * In-memory catalog of analyzer rule metadata, keyed by rule key
 * ({@code <repo>:<sqKey>}, e.g. {@code java:S1118}).
 *
 * <p><b>Why this exists:</b> the analysis-engine {@code Issue} carries no
 * severity or type — only a rule key. Accurate severity/type/description must
 * come from the rule metadata bundled inside each analyzer plugin JAR.
 *
 * <p><b>Extraction path chosen:</b> read the rule resources directly from the
 * plugin JARs. Every SonarSource analyzer ships its rules as
 * {@code org/sonar/l10n/<l10n>/rules/<repo>/<sqKey>.json} (machine-readable
 * metadata: {@code title}, {@code type}, {@code defaultSeverity}) plus a
 * sibling {@code <sqKey>.html} (the rule description). The rule key the engine
 * reports is {@code <repo>:<sqKey>}. This is simpler and more robust than
 * pulling in {@code sonarlint-rule-extractor}, which needs a fully wired
 * plugin runtime and connected-mode plumbing just to enumerate static
 * metadata that already lives in the JARs as plain files.
 *
 * <p>Only the SonarSource analyzers' own rule repositories are indexed;
 * imported third-party linter repos (pmd, eslint, detekt, …) are skipped.
 */
public final class RuleCatalog {

    /**
     * SonarSource analyzer rule-repository directory names (the {@code <repo>}
     * segment, which is also the rule-key prefix the engine emits).
     */
    private static final Set<String> ANALYZER_REPOS = Set.of(
            "java", "python", "javascript", "typescript", "css",
            "php", "kotlin", "go", "ruby", "scala", "Web", "xml");

    /**
     * Maps a rule-repository directory to the {@code RuleMetadata.language}
     * value (the SonarLanguage key). Repos absent here use the repo name.
     */
    private static final Map<String, String> REPO_TO_LANGUAGE = Map.of(
            "python", "py",
            "javascript", "js",
            "typescript", "ts",
            "Web", "web");

    /** Matches {@code org/sonar/l10n/<l10n>/rules/<repo>/<sqKey>.json}. */
    private static final Pattern RULE_JSON = Pattern.compile(
            "^org/sonar/l10n/[^/]+/rules/([^/]+)/([^/]+)\\.json$");

    private static final String DEFAULT_SEVERITY = "MAJOR";
    private static final String DEFAULT_TYPE = "CODE_SMELL";

    private final Map<String, RuleMetadata> rulesByKey;

    private RuleCatalog(Map<String, RuleMetadata> rulesByKey) {
        this.rulesByKey = Map.copyOf(rulesByKey);
    }

    /**
     * Builds a catalog by scanning every {@code *.jar} in {@code pluginsDir}.
     *
     * @param pluginsDir directory holding the vendored analyzer plugin JARs
     * @return the populated catalog
     * @throws IllegalStateException if the directory holds no plugin JARs
     */
    public static RuleCatalog fromPluginsDir(Path pluginsDir) {
        Map<String, RuleMetadata> rules = new HashMap<>();
        int jarCount = 0;
        try (Stream<Path> entries = Files.list(pluginsDir)) {
            for (Path jar : entries
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .toList()) {
                jarCount++;
                indexJar(jar, rules);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not list plugins directory: " + pluginsDir.toAbsolutePath(), e);
        }
        if (jarCount == 0) {
            throw new IllegalStateException(
                    "no analyzer plugin JARs in " + pluginsDir.toAbsolutePath());
        }
        addTypeScriptAliases(rules);
        return new RuleCatalog(rules);
    }

    private static void indexJar(Path jarPath, Map<String, RuleMetadata> rules) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var it = jar.entries();
            while (it.hasMoreElements()) {
                JarEntry entry = it.nextElement();
                Matcher m = RULE_JSON.matcher(entry.getName());
                if (!m.matches()) {
                    continue;
                }
                String repo = m.group(1);
                String sqKey = m.group(2);
                if (!ANALYZER_REPOS.contains(repo)) {
                    continue;
                }
                RuleMetadata md = readRule(jar, entry, repo, sqKey);
                if (md != null) {
                    // First analyzer wins on duplicate keys (stable, JAR-order independent
                    // enough for our purposes — analyzer repos do not overlap in practice).
                    rules.putIfAbsent(md.ruleKey(), md);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read plugin JAR: " + jarPath, e);
        }
    }

    /**
     * Mirrors every {@code javascript:} rule as a {@code typescript:} rule.
     *
     * <p>The {@code sonar-javascript-plugin} ships rule metadata only under the
     * {@code javascript} repository, but its TypeScript sensor emits
     * {@code typescript:}-keyed issues with the same {@code S}-numbers. Without
     * this mirror, {@code lookup}/{@code all} hold no metadata for
     * {@code typescript:} keys, so TS issues get default severity/type and no
     * description. A genuine {@code typescript:} rule from a JAR (should one
     * ever ship) wins via {@code putIfAbsent}.
     */
    private static void addTypeScriptAliases(Map<String, RuleMetadata> rules) {
        for (RuleMetadata js : java.util.List.copyOf(rules.values())) {
            if (!js.ruleKey().startsWith("javascript:")) {
                continue;
            }
            String tsKey = "typescript:" + js.ruleKey().substring("javascript:".length());
            rules.putIfAbsent(tsKey, new RuleMetadata(
                    tsKey, js.name(), "ts", js.severity(), js.type(),
                    js.descriptionHtml(), js.howToFix()));
        }
    }

    private static RuleMetadata readRule(
            JarFile jar, JarEntry jsonEntry, String repo, String sqKey) throws IOException {
        JsonNode json;
        try (InputStream in = jar.getInputStream(jsonEntry)) {
            json = Json.mapper().readTree(in);
        }
        if (json == null || !json.isObject()) {
            return null;
        }
        String ruleKey = repo + ":" + sqKey;
        String name = text(json, "title", sqKey);
        String language = REPO_TO_LANGUAGE.getOrDefault(repo, repo);
        String severity = normalizeSeverity(text(json, "defaultSeverity", null));
        String type = normalizeType(text(json, "type", null));
        String descriptionHtml = readHtml(jar, jsonEntry.getName());
        return new RuleMetadata(ruleKey, name, language, severity, type, descriptionHtml, null);
    }

    /** Reads the sibling {@code <sqKey>.html} description, or {@code ""} if absent. */
    private static String readHtml(JarFile jar, String jsonEntryName) throws IOException {
        String htmlName = jsonEntryName.substring(0, jsonEntryName.length() - ".json".length())
                + ".html";
        JarEntry htmlEntry = jar.getJarEntry(htmlName);
        if (htmlEntry == null) {
            return "";
        }
        try (InputStream in = jar.getInputStream(htmlEntry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : fallback;
    }

    /** Rule JSON severity is title-cased ({@code Major}); the protocol wants uppercase. */
    private static String normalizeSeverity(String raw) {
        if (raw == null) {
            return DEFAULT_SEVERITY;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO" -> upper;
            default -> DEFAULT_SEVERITY;
        };
    }

    private static String normalizeType(String raw) {
        if (raw == null) {
            return DEFAULT_TYPE;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "BUG", "CODE_SMELL", "VULNERABILITY", "SECURITY_HOTSPOT" -> upper;
            default -> DEFAULT_TYPE;
        };
    }

    /**
     * Looks up rule metadata by rule key ({@code <repo>:<sqKey>}).
     *
     * @param ruleKey the engine-reported rule key, may be {@code null}
     * @return the metadata, or {@code null} if the key is unknown or {@code null}
     */
    public RuleMetadata lookup(String ruleKey) {
        return ruleKey == null ? null : rulesByKey.get(ruleKey);
    }

    /**
     * Every rule in the catalog, sorted by rule key for a stable listing.
     *
     * @return all rule metadata, ordered by {@code ruleKey}
     */
    public java.util.List<RuleMetadata> all() {
        return rulesByKey.values().stream()
                .sorted(java.util.Comparator.comparing(RuleMetadata::ruleKey))
                .toList();
    }

    /** {@code true} if the catalog holds no rules. */
    public boolean isEmpty() {
        return rulesByKey.isEmpty();
    }

    /** Number of rules in the catalog. */
    public int size() {
        return rulesByKey.size();
    }
}
