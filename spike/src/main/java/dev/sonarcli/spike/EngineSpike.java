package dev.sonarcli.spike;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import org.sonarsource.sonarlint.core.analysis.api.Issue;
import org.sonarsource.sonarlint.core.analysis.api.TriggerType;
import org.sonarsource.sonarlint.core.analysis.command.AnalyzeCommand;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;
import org.sonarsource.sonarlint.core.commons.log.LogOutput;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;
import org.sonarsource.sonarlint.core.commons.progress.SonarLintCancelMonitor;
import org.sonarsource.sonarlint.core.commons.progress.TaskManager;
import org.sonarsource.sonarlint.core.plugin.commons.LoadedPlugins;
import org.sonarsource.sonarlint.core.plugin.commons.PluginsLoadResult;
import org.sonarsource.sonarlint.core.plugin.commons.PluginsLoader;

/**
 * SPIKE: prove the SonarSource standalone analysis engine can be embedded in
 * plain Java 17, load the sonar-java-plugin from a file path, analyze one
 * Java source file, and emit a real issue -- fully offline.
 *
 * API verified via javap against the published jars (engine 10.24.0.81415).
 * Notable deltas from the hinted API are documented inline.
 *
 * Usage: EngineSpike &lt;plugin-jar&gt; &lt;fixture-dir&gt; &lt;fixture-file&gt;
 */
public final class EngineSpike {

    /** java:S1118 -- utility classes should not have public constructors. */
    private static final String RULE_KEY = "java:S1118";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: EngineSpike <plugin-jar> <fixture-dir> <fixture-file>");
            System.exit(2);
        }
        Path pluginJar = Paths.get(args[0]).toAbsolutePath();
        Path fixtureDir = Paths.get(args[1]).toAbsolutePath();
        Path fixtureFile = Paths.get(args[2]).toAbsolutePath();

        require(Files.isRegularFile(pluginJar), "plugin jar not found: " + pluginJar);
        require(Files.isRegularFile(fixtureFile), "fixture file not found: " + fixtureFile);

        Path workDir = Files.createTempDirectory("engine-spike-work");

        System.out.println("[spike] plugin jar : " + pluginJar);
        System.out.println("[spike] fixture    : " + fixtureFile);
        System.out.println("[spike] work dir   : " + workDir);

        // ---- 0. Register a global log target -------------------------------
        // GOTCHA: PluginsLoader (and the analyzer) log through the thread-local
        // SonarLintLogger. Without a target registered BEFORE PluginsLoader
        // .load(), it throws "No log output configured". The LogOutput handed
        // to AnalysisScheduler is NOT enough -- it must be set globally first.
        // LogOutput's two log() methods are both default (not a functional
        // interface) -- override one explicitly with an anonymous class.
        LogOutput logOutput = new LogOutput() {
            @Override
            public void log(String formattedMessage, Level level) {
                System.out.println("[engine:" + level + "] " + formattedMessage);
            }
        };
        SonarLintLogger.get().setTarget(logOutput);

        // ---- 1. Load the plugin from disk (file: classloading only) --------
        // PluginsLoader.Configuration(Set<Path> jars, Set<SonarLanguage> langs,
        //                             boolean enableDataflowBugDetection,
        //                             Optional<Version> nodeVersion)
        PluginsLoader.Configuration pluginConfig = new PluginsLoader.Configuration(
                Set.of(pluginJar),
                Set.of(SonarLanguage.JAVA),
                false,
                Optional.empty());
        PluginsLoadResult loadResult = new PluginsLoader().load(pluginConfig, Set.of());
        LoadedPlugins loadedPlugins = loadResult.getLoadedPlugins();
        System.out.println("[spike] loaded plugins: "
                + loadedPlugins.getAllPluginInstancesByKeys().keySet());
        require(loadedPlugins.getAllPluginInstancesByKeys().containsKey("java"),
                "java plugin failed to load");

        // ---- 2. Build the scheduler ----------------------------------------
        // AnalysisSchedulerConfiguration.Builder: setWorkDir / setClientPid /
        // setExtraProperties / setModulesProvider (NO setFileSystemProvider in
        // the published jar -- master HEAD diverged). No modules => omit it.
        AnalysisSchedulerConfiguration schedulerConfig = AnalysisSchedulerConfiguration.builder()
                .setWorkDir(workDir)
                .setClientPid(ProcessHandle.current().pid())
                .build();

        // AnalysisScheduler(AnalysisSchedulerConfiguration, LoadedPlugins, LogOutput)
        // -- the published jar exposes AnalysisScheduler, NOT AnalysisEngine.
        AnalysisScheduler scheduler = new AnalysisScheduler(schedulerConfig, loadedPlugins, logOutput);

        int issueCount;
        try {
            // ---- 3. Active rules (explicit -- no built-in profile) ---------
            // ActiveRule is org.sonarsource.sonarlint.core.analysis.api.ActiveRule,
            // a CONCRETE class ActiveRule(String ruleKey, String languageKey) --
            // NOT the hinted org.sonar.api.batch.rule.ActiveRule interface.
            ActiveRule s1118 = new ActiveRule(RULE_KEY, SonarLanguage.JAVA.getSonarLanguageKey());

            FileClientInputFile inputFile =
                    new FileClientInputFile(fixtureFile, fixtureDir.relativize(fixtureFile).toString());

            AnalysisConfiguration analysisConfig = AnalysisConfiguration.builder()
                    .setBaseDir(fixtureDir)
                    .addInputFile(inputFile)
                    .addActiveRule(s1118)
                    // The Java analyzer wants binaries; point it at the fixture
                    // dir so it does not abort. No compiled classes needed for
                    // a source-only rule like S1118.
                    .putExtraProperty("sonar.java.source", "17")
                    .putExtraProperty("sonar.java.binaries", fixtureDir.toString())
                    .putExtraProperty("sonar.java.libraries", "")
                    .build();

            // ---- 4. Post the analyze command -------------------------------
            List<Issue> issues = new ArrayList<>();
            // 12-arg constructor (published jar). The engine creates the result
            // future internally; retrieve it via getFutureResult().
            AnalyzeCommand command = new AnalyzeCommand(
                    "spike-module",
                    UUID.randomUUID(),
                    TriggerType.FORCED,
                    () -> analysisConfig,
                    issues::add,                       // Consumer<Issue>
                    null,                              // Trace (nullable)
                    new SonarLintCancelMonitor(),
                    new TaskManager(),
                    files -> { },                      // analysis-started consumer
                    () -> Boolean.TRUE,                // isReady supplier
                    Set.of(),                          // files (URIs) -- unused here
                    Map.of());                         // per-command extra props

            scheduler.post(command);

            CompletableFuture<AnalysisResults> future = command.getFutureResult();
            AnalysisResults results = future.get();

            System.out.println("[spike] analysis duration : " + results.getDuration());
            System.out.println("[spike] failed files      : " + results.failedAnalysisFiles().size());
            require(results.failedAnalysisFiles().isEmpty(), "analyzer failed on the fixture file");

            issueCount = issues.size();
            System.out.println("[spike] issues raised     : " + issueCount);
            for (Issue issue : issues) {
                System.out.println("  - ruleKey=" + issue.getRuleKey()
                        + " message=\"" + issue.getMessage() + "\""
                        + " line=" + (issue.getTextRange() != null
                                ? issue.getTextRange().getStartLine() : "?"));
            }

            boolean sawTargetRule = issues.stream()
                    .anyMatch(i -> RULE_KEY.equals(i.getRuleKey()));
            require(sawTargetRule, "expected rule " + RULE_KEY + " was not raised");
        } finally {
            scheduler.stop();
            deleteRecursively(workDir);
        }

        System.out.println("[spike] SUCCESS: engine embedded offline, "
                + issueCount + " issue(s) raised, including " + RULE_KEY);
    }

    /**
     * Best-effort recursive delete of the engine work directory. Walks the tree
     * and deletes children before parents; any IO error is swallowed so cleanup
     * never masks a real analysis failure.
     */
    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort: ignore
                }
            });
        } catch (IOException e) {
            // best-effort: ignore
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("SPIKE FAILED: " + message);
        }
    }

    private EngineSpike() {
    }
}
