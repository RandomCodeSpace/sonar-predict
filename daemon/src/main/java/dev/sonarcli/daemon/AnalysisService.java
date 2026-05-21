package dev.sonarcli.daemon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.sonarsource.sonarlint.core.analysis.AnalysisScheduler;
import org.sonarsource.sonarlint.core.analysis.api.ActiveRule;
import org.sonarsource.sonarlint.core.analysis.api.AnalysisConfiguration;
import org.sonarsource.sonarlint.core.analysis.api.AnalysisResults;
import org.sonarsource.sonarlint.core.analysis.api.AnalysisSchedulerConfiguration;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.analysis.api.Issue;
import org.sonarsource.sonarlint.core.analysis.api.TriggerType;
import org.sonarsource.sonarlint.core.analysis.command.AnalyzeCommand;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;
import org.sonarsource.sonarlint.core.commons.progress.SonarLintCancelMonitor;
import org.sonarsource.sonarlint.core.commons.progress.TaskManager;
import org.sonarsource.sonarlint.core.plugin.commons.LoadedPlugins;

import dev.sonarcli.protocol.dto.AnalysisWarning;
import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;

/**
 * Long-lived analysis core: takes a protocol {@link AnalyzeRequest}, detects
 * each file's language, runs the embedded SonarSource analysis engine over all
 * supported files in one pass, and returns an {@link AnalyzeResponse} of mapped
 * issues and warnings.
 *
 * <p><b>Warm engine.</b> Plugins ({@link PluginRuntime#loadAll}), the
 * {@link RuleCatalog}, and the engine {@link AnalysisScheduler} are all built
 * <em>once</em> in the constructor and reused for every {@link #analyze} call.
 * The {@code AnalysisScheduler} is explicitly designed for this: it owns a
 * worker thread and a command queue, and {@link AnalysisScheduler#post} simply
 * enqueues one {@link AnalyzeCommand} at a time — there is no per-analysis
 * teardown. Constructing a fresh scheduler (and reloading every analyzer JAR)
 * per request is what made the pre-daemon code cold; this class keeps it warm
 * so the second and subsequent analyses skip all plugin/classloader startup.
 * {@link #close} stops the scheduler; the instance is then unusable.
 *
 * <p>Multi-language: every vendored analyzer that can load is loaded, languages
 * are detected per file ({@link LanguageDetector}), and issue severity/type
 * come from the {@link RuleCatalog}. An analyzer that is skipped at load time
 * — most notably the JS/TS/CSS analyzer when no Node.js runtime is available —
 * is excluded from {@link #loadedLanguages()}, and a file targeting such a
 * language produces a visible {@link AnalysisWarning} rather than a silent
 * zero. With no {@code --config} profile, active rules are each analyzer's
 * bundled "Sonar way" default profile ({@link SonarWayProfiles}), covering
 * every loaded language. A detected language whose analyzer ships no Sonar way
 * profile also produces a warning. Files whose language is unknown or
 * unsupported are reported as warnings too.
 *
 * <p><b>Thread-safe (serialized).</b> {@link #analyze} may be called from any
 * number of threads — the daemon serves each socket connection on its own
 * thread. Every {@code analyze()} call is serialized on a single
 * {@link ReentrantLock}, so the underlying engine scheduler sees one analysis
 * at a time and concurrent requests cannot corrupt one another. {@link #close}
 * acquires the same lock, so a shutdown arriving during an in-flight analysis
 * waits for that analysis to finish before the engine scheduler is stopped —
 * the engine is never torn down underneath a running analysis.
 */
public final class AnalysisService implements AutoCloseable {

    private final LoadedPlugins loadedPlugins;
    private final Set<SonarLanguage> loadedLanguages;
    private final RuleCatalog ruleCatalog;
    private final SonarWayProfiles sonarWayProfiles;
    private final AnalysisScheduler scheduler;
    private final Path workDir;

    /**
     * Serializes {@link #analyze} calls and gates {@link #close} so shutdown
     * waits for any in-flight analysis. A fair lock keeps a shutdown from
     * starving behind a stream of analyses.
     */
    private final java.util.concurrent.locks.ReentrantLock analysisLock =
            new java.util.concurrent.locks.ReentrantLock(true);

    private volatile boolean closed;

    /**
     * Test-only hook run inside the {@link #analyze} locked region, right
     * after the lock is acquired. Lets a test pin an analysis in-flight to
     * exercise the {@link #close}-waits-for-analysis path deterministically.
     * {@code null} (a no-op) in production.
     */
    private volatile Runnable analysisEnteredHook;

    /**
     * Builds a warm service over the resolved analyzer-plugin directory: the
     * {@code sonar.plugins.dir} property or {@code SONAR_PLUGINS_DIR} env var
     * if set (the CLI {@code setup} seam), otherwise the dev-default
     * CWD-relative {@code plugins/} directory.
     */
    public AnalysisService() {
        this(PluginsDir.resolve());
    }

    /**
     * Builds a warm service: loads every analyzer plugin, the rule catalog, and
     * starts the engine scheduler. This is the expensive step; do it once.
     *
     * @param pluginsDir directory holding the vendored analyzer plugin JARs
     */
    public AnalysisService(Path pluginsDir) {
        EngineLog.install();
        this.loadedPlugins = PluginRuntime.loadAll(pluginsDir);
        // The real loaded-language set: an analyzer skipped at load time (e.g.
        // the JS/TS/CSS plugin with no Node.js runtime) is absent here, so
        // loadedLanguages() and the PING response never over-report capability.
        this.loadedLanguages = PluginRuntime.loadedLanguagesFor(
                loadedPlugins.getAllPluginInstancesByKeys().keySet());
        this.ruleCatalog = RuleCatalog.fromPluginsDir(pluginsDir);
        this.sonarWayProfiles = SonarWayProfiles.load(pluginsDir);
        this.workDir = createWorkDir();

        AnalysisSchedulerConfiguration schedulerConfig = AnalysisSchedulerConfiguration.builder()
                .setWorkDir(workDir)
                .setClientPid(ProcessHandle.current().pid())
                .build();
        this.scheduler =
                new AnalysisScheduler(schedulerConfig, loadedPlugins, new EngineLog());
    }

    /**
     * Analyzes the files named in {@code request} and returns the findings.
     * Reuses the loaded plugins, catalog, and engine scheduler — no startup cost.
     *
     * @param request the analysis request; {@code files} are relative to {@code baseDir}
     * @return a response carrying mapped issues and any non-fatal warnings
     * @throws IllegalStateException if the service has been {@link #close closed}
     */
    public AnalyzeResponse analyze(AnalyzeRequest request) {
        // Serialize every analysis: the engine scheduler must see one at a
        // time, and close() must be able to wait behind an in-flight call.
        analysisLock.lock();
        try {
            Runnable hook = analysisEnteredHook;
            if (hook != null) {
                hook.run();
            }
            // The closed check is inside the lock so a close() that won the
            // lock first is fully observed before this analysis proceeds.
            if (closed) {
                throw new IllegalStateException("AnalysisService is closed");
            }
            return analyzeLocked(request);
        } finally {
            analysisLock.unlock();
        }
    }

    /** The analysis body; always runs holding {@link #analysisLock}. */
    private AnalyzeResponse analyzeLocked(AnalyzeRequest request) {
        Path baseDir = Path.of(request.baseDir()).toAbsolutePath().normalize();

        List<FileInputFile> inputFiles = new ArrayList<>();
        List<AnalysisWarning> warnings = new ArrayList<>();
        Set<SonarLanguage> presentLanguages = new java.util.HashSet<>();

        for (String relative : request.files()) {
            Optional<SonarLanguage> language = LanguageDetector.detect(relative);
            if (language.isEmpty()) {
                warnings.add(new AnalysisWarning(
                        relative, "unsupported or unrecognized file type; skipped"));
                continue;
            }
            if (!loadedLanguages.contains(language.get())) {
                // The file's language is recognized, but its analyzer plugin
                // was skipped at load time (e.g. JS/TS/CSS with no Node.js).
                // Surface a visible warning instead of a silent zero.
                warnings.add(new AnalysisWarning(
                        relative,
                        language.get().getSonarLanguageKey()
                                + ": analyzer not loaded (its plugin was skipped); "
                                + "file not analyzed"));
                continue;
            }
            Path file = baseDir.resolve(relative).toAbsolutePath().normalize();
            if (!isWithinBaseDir(relative, file, baseDir)) {
                // A direct socket client could send an absolute path, a '..'
                // escape, or any name resolving outside baseDir to read files
                // the daemon was never asked to analyze. Reject and warn —
                // the rest of the request still analyzes.
                warnings.add(new AnalysisWarning(
                        relative,
                        "file path escapes the analysis base directory; skipped"));
                continue;
            }
            inputFiles.add(new FileInputFile(file, baseDir, language.get()));
            presentLanguages.add(language.get());
        }

        List<ActiveRule> activeRules = request.profileRef() != null
                ? profileRulesFrom(request.profileRef())
                : resolveActiveRules(sonarWayProfiles, presentLanguages, warnings);

        AnalysisConfiguration analysisConfig = AnalysisConfiguration.builder()
                .setBaseDir(baseDir)
                .addInputFiles(inputFiles)
                .addActiveRules(activeRules)
                .putExtraProperty("sonar.java.source", "17")
                .putExtraProperty("sonar.java.binaries", baseDir.toString())
                .putExtraProperty("sonar.java.libraries", "")
                .build();

        List<Issue> rawIssues = new ArrayList<>();
        AnalyzeCommand command = new AnalyzeCommand(
                "daemon-module",
                UUID.randomUUID(),
                TriggerType.FORCED,
                () -> analysisConfig,
                rawIssues::add,
                null,
                new SonarLintCancelMonitor(),
                new TaskManager(),
                files -> { },
                () -> Boolean.TRUE,
                Set.of(),
                Map.of());

        scheduler.post(command);
        AnalysisResults results = await(command.getFutureResult());

        List<dev.sonarcli.protocol.dto.Issue> issues = new ArrayList<>();
        for (Issue raw : rawIssues) {
            issues.add(IssueMapper.toDto(raw, baseDir, ruleCatalog));
        }

        for (ClientInputFile failed : results.failedAnalysisFiles()) {
            warnings.add(new AnalysisWarning(
                    failed.relativePath(),
                    "analysis failed for this file"));
        }

        return new AnalyzeResponse(List.copyOf(issues), List.copyOf(warnings));
    }

    /**
     * Whether a request file resolves to a real path inside {@code baseDir}.
     *
     * <p>Three guards, all required: the supplied {@code relative} name must
     * not be an absolute path, must contain no {@code ..} segment, and the
     * file's <em>real</em> path — symlinks resolved — must lie under the real
     * {@code baseDir}. The first two reject the obvious traversal payloads
     * before any filesystem touch; the third is the backstop.
     *
     * <p>The containment check uses {@link Path#toRealPath()}, not a lexical
     * {@link Path#startsWith(Path)} on {@code normalize()}-d paths.
     * {@code normalize()} only collapses {@code .} and {@code ..} textually —
     * it does not follow symbolic links. A symlink inside the project tree
     * pointing <em>outside</em> {@code baseDir} therefore passes a lexical
     * check yet reads an arbitrary target file; resolving real paths closes
     * that escape. {@code toRealPath()} throwing — a non-existent file, a
     * broken symlink, or a missing {@code baseDir} — is treated as "not
     * contained": the file is rejected and the caller warns and skips it,
     * rather than the request crashing.
     *
     * @param relative the raw file name from the request
     * @param resolved the absolute, normalized path it resolved to
     * @param baseDir  the absolute, normalized analysis base directory
     * @return {@code true} only if the file is safely contained in {@code baseDir}
     */
    private static boolean isWithinBaseDir(String relative, Path resolved, Path baseDir) {
        Path rawRelative = Path.of(relative);
        if (rawRelative.isAbsolute()) {
            return false;
        }
        for (Path segment : rawRelative) {
            if ("..".equals(segment.toString())) {
                return false;
            }
        }
        try {
            // Resolve symlinks on both sides: a symlinked component could
            // otherwise point the file outside the tree while still passing
            // a purely lexical startsWith() check.
            Path realBase = baseDir.toRealPath();
            Path realResolved = resolved.toRealPath();
            return realResolved.startsWith(realBase);
        } catch (IOException e) {
            // A non-existent file, a broken symlink, or a missing baseDir:
            // cannot prove containment, so reject — the caller skips and warns.
            return false;
        }
    }

    /**
     * The analyzer languages this service can <em>actually</em> analyze, as
     * protocol language keys — derived from the plugins that truly loaded, not
     * the full static set. An analyzer skipped at load time (e.g. JS/TS/CSS
     * when no Node.js runtime is present) is excluded, so a {@code PING}
     * response never advertises a capability the daemon does not have.
     *
     * @return a stable, sorted list of the actually-loaded language keys
     */
    public List<String> loadedLanguages() {
        return loadedLanguages.stream()
                .map(SonarLanguage::getSonarLanguageKey)
                .sorted()
                .toList();
    }

    /** The shared rule catalog, for callers serving {@code RULE_METADATA}. */
    public RuleCatalog ruleCatalog() {
        return ruleCatalog;
    }

    /** The engine work directory, for tests verifying lifecycle cleanup. */
    Path workDir() {
        return workDir;
    }

    /**
     * Stops the engine scheduler and deletes the analysis work directory.
     * The service is unusable afterwards.
     *
     * <p>Acquires the same {@link #analysisLock} {@link #analyze} holds, so a
     * {@code close()} that races an in-flight analysis blocks until that
     * analysis completes — the engine scheduler is never stopped underneath a
     * running analysis. Idempotent: a second {@code close()} is a no-op.
     */
    @Override
    public void close() {
        analysisLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            try {
                scheduler.stop();
            } finally {
                // The engine scheduler stop must never prevent the work
                // directory from being reclaimed: the recursive delete runs
                // regardless.
                deleteWorkDir();
            }
        } finally {
            analysisLock.unlock();
        }
    }

    /**
     * Installs a test-only hook run inside the {@link #analyze} locked region.
     * Production code never calls this; tests use it to pin an analysis
     * in-flight and verify {@link #close} waits for it.
     *
     * @param hook the hook to run after the analysis lock is acquired
     */
    void setAnalysisEnteredHookForTest(Runnable hook) {
        this.analysisEnteredHook = hook;
    }

    /**
     * Recursively deletes the work directory the engine wrote analysis files
     * into. Robust if the directory is already gone; deletion failures are
     * swallowed since the {@code sonar-workdir-cleanup} JVM shutdown hook is a
     * registered safety net.
     */
    private void deleteWorkDir() {
        deleteRecursively(workDir);
    }

    /**
     * Recursively deletes {@code root} and everything under it, deepest entry
     * first. A no-op if {@code root} is already gone; every per-entry failure
     * is swallowed so a partial filesystem error cannot abort cleanup or
     * propagate out of a {@code close()} or a shutdown hook.
     */
    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(root)) {
            entries.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort; the shutdown hook covers abnormal cases
                        }
                    });
        } catch (IOException ignored) {
            // best-effort cleanup; the JVM shutdown hook is the fallback
        }
    }

    /**
     * Resolves the default-profile active rules for every language present in
     * the request, using each analyzer's bundled "Sonar way" profile.
     *
     * <p>This is the no-{@code --config} path. Unlike the old curated subsets
     * (Java/Python/JS only), it covers <em>every</em> loaded language. When a
     * detected language resolves to zero available rules — its analyzer ships
     * no Sonar way profile — a project-level {@link AnalysisWarning} is added
     * to {@code warnings} so the result is never a silent clean zero.
     *
     * <p>Each rule's {@link ActiveRule#getLanguageKey() language key} is the
     * detected language's own key, so {@code .ts} files run the
     * {@code javascript} repository's rules under the {@code ts} language.
     *
     * @param profiles the bundled Sonar way profiles
     * @param languages the languages detected among the request's files
     * @param warnings  collector for per-language "no rule set" warnings
     * @return the active rules for the engine
     */
    static List<ActiveRule> resolveActiveRules(
            SonarWayProfiles profiles,
            Set<SonarLanguage> languages,
            List<AnalysisWarning> warnings) {
        List<ActiveRule> rules = new ArrayList<>();
        for (SonarLanguage language : languages) {
            List<String> ruleKeys = profiles.ruleKeysFor(language);
            if (ruleKeys.isEmpty()) {
                warnings.add(new AnalysisWarning(
                        null,
                        language.getSonarLanguageKey()
                                + ": detected but no rule set available; files not analyzed"));
                continue;
            }
            String languageKey = language.getSonarLanguageKey();
            for (String ruleKey : ruleKeys) {
                rules.add(new ActiveRule(ruleKey, languageKey));
            }
        }
        return rules;
    }

    /**
     * Repository-key → SonarLanguage-key fallbacks, for profile rules whose
     * key is not in the rule catalog. The catalog is consulted first; this map
     * only covers the SonarSource analyzer repos whose name differs from the
     * language key.
     */
    private static final Map<String, String> REPO_TO_LANGUAGE_KEY = Map.of(
            "python", SonarLanguage.PYTHON.getSonarLanguageKey(),
            "javascript", SonarLanguage.JS.getSonarLanguageKey(),
            "typescript", SonarLanguage.TS.getSonarLanguageKey(),
            "Web", SonarLanguage.HTML.getSonarLanguageKey());

    /**
     * Builds the active rules from an imported SonarQube quality profile.
     * Each profile rule becomes an {@link ActiveRule} carrying its parameters;
     * the rule's language is resolved from the {@link RuleCatalog}, falling
     * back to the repository-key prefix when the rule is not catalogued.
     *
     * @param profileRef path to the quality-profile XML
     * @return the active rules the profile activates
     * @throws QualityProfileException if the profile cannot be read or parsed
     */
    private List<ActiveRule> profileRulesFrom(String profileRef) {
        QualityProfile profile = QualityProfile.parse(Path.of(profileRef));
        List<ActiveRule> rules = new ArrayList<>();
        for (QualityProfile.ProfileRule rule : profile.rules()) {
            String languageKey = languageKeyFor(rule.ruleKey());
            if (languageKey == null) {
                // Cannot map the rule to a loaded analyzer language — skip it
                // rather than fail the whole analysis.
                continue;
            }
            ActiveRule active = new ActiveRule(rule.ruleKey(), languageKey);
            if (!rule.parameters().isEmpty()) {
                active.setParams(Map.copyOf(rule.parameters()));
            }
            rules.add(active);
        }
        return rules;
    }

    /**
     * Resolves the SonarLanguage key for a profile rule key
     * ({@code repositoryKey:key}): the catalog metadata first, then the
     * repository-prefix fallback.
     */
    private String languageKeyFor(String ruleKey) {
        var metadata = ruleCatalog.lookup(ruleKey);
        if (metadata != null && metadata.language() != null) {
            return metadata.language();
        }
        int colon = ruleKey.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String repo = ruleKey.substring(0, colon);
        return REPO_TO_LANGUAGE_KEY.getOrDefault(repo, repo);
    }

    private static Path createWorkDir() {
        try {
            Path dir = Files.createTempDirectory("sonar-daemon-work");
            // Safety net: if the process dies before close() runs (a SIGKILL,
            // an OOM, an unhandled error), a JVM shutdown hook still removes
            // the directory. File#deleteOnExit is not used: it is non-recursive
            // and silently fails once the engine has written files into the
            // directory, which is exactly when a leak matters.
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> deleteRecursively(dir), "sonar-workdir-cleanup"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("could not create analysis work directory", e);
        }
    }

    private static AnalysisResults await(CompletableFuture<AnalysisResults> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("analysis was interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("analysis failed", e.getCause());
        }
    }
}
