package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.Issue;

class AnalysisServiceTest {

    /** Fixture root, relative to the daemon module root. */
    private static final Path FIXTURES = Paths.get("src/test/resources/daemon/fixtures");

    private static final Set<String> VALID_SEVERITIES =
            Set.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO");
    private static final Set<String> VALID_TYPES =
            Set.of("BUG", "CODE_SMELL", "VULNERABILITY", "SECURITY_HOTSPOT");

    private AnalyzeRequest request(String... files) {
        return new AnalyzeRequest(
                FIXTURES.toAbsolutePath().toString(),
                List.of(files),
                List.of(),
                null,
                List.of());
    }

    @Test
    @DisplayName("Java fixture UtilityClass.java still raises java:S1118")
    void analyze_java_stillWorks() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("java/UtilityClass.java"));

            boolean hasS1118 = response.issues().stream()
                    .anyMatch(i -> "java:S1118".equals(i.ruleKey()));
            assertTrue(hasS1118, "expected java:S1118, got: " + response.issues());
        }
    }

    @Test
    @DisplayName("a warm AnalysisService is constructed once and reused across many analyze() calls")
    void analyze_warmServiceReusedAcrossCalls() {
        try (AnalysisService service = new AnalysisService()) {
            // First call: Java.
            AnalyzeResponse first = service.analyze(request("java/UtilityClass.java"));
            assertTrue(first.issues().stream().anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "first call must raise java:S1118");

            // Second call on the SAME service instance — no reload, results still correct.
            AnalyzeResponse second = service.analyze(request("java/UtilityClass.java"));
            assertTrue(second.issues().stream().anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "second call on the same warm service must still raise java:S1118");

            // Third call, different language — the same loaded plugins serve every language.
            AnalyzeResponse third = service.analyze(request("python/bad.py"));
            assertFalse(third.issues().stream().filter(i -> i.ruleKey().startsWith("python:"))
                    .toList().isEmpty(), "third call must still produce python issues");

            // Clean file last: confirms the reused engine has no leaked state.
            AnalyzeResponse fourth = service.analyze(request("java/Clean.java"));
            assertFalse(fourth.issues().stream().anyMatch(i -> "Clean.java".equals(i.filePath())),
                    "Clean.java must raise no issues even on a reused warm service");
        }
    }

    @Test
    @DisplayName("Python fixture raises a python: issue with metadata-resolved severity/type")
    void analyze_python_raisesPythonIssue() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("python/bad.py"));

            List<Issue> pyIssues = response.issues().stream()
                    .filter(i -> i.ruleKey().startsWith("python:"))
                    .toList();
            assertFalse(pyIssues.isEmpty(),
                    "expected at least one python: issue, got: " + response.issues());
            for (Issue i : pyIssues) {
                assertTrue(VALID_SEVERITIES.contains(i.severity()),
                        "python issue severity must be metadata-resolved, got: " + i.severity());
                assertTrue(VALID_TYPES.contains(i.type()),
                        "python issue type must be metadata-resolved, got: " + i.type());
            }
        }
    }

    @Test
    @DisplayName("JavaScript fixture raises a javascript: issue with metadata-resolved severity/type")
    void analyze_javascript_raisesJsIssue() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("js/bad.js"));

            List<Issue> jsIssues = response.issues().stream()
                    .filter(i -> i.ruleKey().startsWith("javascript:"))
                    .toList();
            assertFalse(jsIssues.isEmpty(),
                    "expected at least one javascript: issue, got: " + response.issues());
            for (Issue i : jsIssues) {
                assertTrue(VALID_SEVERITIES.contains(i.severity()),
                        "js issue severity must be metadata-resolved, got: " + i.severity());
                assertTrue(VALID_TYPES.contains(i.type()),
                        "js issue type must be metadata-resolved, got: " + i.type());
            }
        }
    }

    @Test
    @DisplayName("TypeScript fixture raises a typescript: issue (was a silent clean zero)")
    void analyze_typescript_raisesTsIssue() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("ts/bad.ts"));

            // The JS plugin's TypeScript sensor activates the `typescript` rule
            // repository. If the Sonar way active rules carry the `javascript:`
            // prefix, the sensor finds none of them and every .ts file is a
            // silent clean zero. A typescript: issue here proves the fix.
            List<Issue> tsIssues = response.issues().stream()
                    .filter(i -> i.ruleKey().startsWith("typescript:"))
                    .toList();
            assertFalse(tsIssues.isEmpty(),
                    "expected at least one typescript: issue (path was a silent no-op), "
                            + "got: " + response.issues() + " warnings: " + response.warnings());
            for (Issue i : tsIssues) {
                assertTrue(VALID_SEVERITIES.contains(i.severity()),
                        "ts issue severity must be a valid value, got: " + i.severity());
                assertTrue(VALID_TYPES.contains(i.type()),
                        "ts issue type must be a valid value, got: " + i.type());
            }
        }
    }

    @Test
    @DisplayName("a mixed-language request analyzes every file in one pass")
    void analyze_mixedLanguageRequest() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(
                    request("java/UtilityClass.java", "python/bad.py", "js/bad.js"));

            assertNotNull(response.issues());
            assertTrue(response.issues().stream().anyMatch(i -> i.ruleKey().startsWith("java:")),
                    "expected java issues in mixed request");
            assertTrue(response.issues().stream().anyMatch(i -> i.ruleKey().startsWith("python:")),
                    "expected python issues in mixed request");
            assertTrue(response.issues().stream().anyMatch(i -> i.ruleKey().startsWith("javascript:")),
                    "expected javascript issues in mixed request");
        }
    }

    @Test
    @DisplayName("Clean.java raises no issues")
    void analyze_cleanFile_noIssues() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("java/Clean.java"));

            boolean anyForClean = response.issues().stream()
                    .anyMatch(i -> "Clean.java".equals(i.filePath()));
            assertFalse(anyForClean,
                    "expected no issues on Clean.java, got: " + response.issues());
        }
    }

    @Test
    @DisplayName("with a profileRef the active rules come from the imported quality profile")
    void analyze_withProfileRef_usesProfileRules(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        // A profile activating ONLY java:S1118 — none of the other curated rules.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <profile>
                  <name>OnlyS1118</name>
                  <language>java</language>
                  <rules>
                    <rule>
                      <repositoryKey>java</repositoryKey>
                      <key>S1118</key>
                    </rule>
                  </rules>
                </profile>
                """;
        Path profile = tmp.resolve("profile.xml");
        java.nio.file.Files.writeString(profile, xml);

        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(new AnalyzeRequest(
                    FIXTURES.toAbsolutePath().toString(),
                    List.of("java/UtilityClass.java"),
                    List.of(),
                    profile.toString(),
                    List.of()));

            // S1118 is in the profile, so it must still fire.
            assertTrue(response.issues().stream()
                            .anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "the profiled rule java:S1118 must fire, got: " + response.issues());
            // S106 is in the curated set but NOT the profile — it must be silent.
            assertFalse(response.issues().stream()
                            .anyMatch(i -> "java:S106".equals(i.ruleKey())),
                    "a rule absent from the profile must not fire, got: " + response.issues());
        }
    }

    @Test
    @DisplayName("a null profileRef keeps the curated per-language rule sets")
    void analyze_nullProfileRef_usesCuratedRules() {
        try (AnalysisService service = new AnalysisService()) {
            // request(...) builds an AnalyzeRequest with a null profileRef.
            AnalyzeResponse response = service.analyze(request("java/ConsolePrinter.java"));

            // ConsolePrinter triggers java:S106, which is in the curated set.
            assertTrue(response.issues().stream()
                            .anyMatch(i -> "java:S106".equals(i.ruleKey())),
                    "the curated rule set must still drive analysis when no profile is given, "
                            + "got: " + response.issues());
        }
    }

    @Test
    @DisplayName("a missing profileRef file fails the analysis with a clear error")
    void analyze_missingProfileRef_throws(@org.junit.jupiter.api.io.TempDir Path tmp) {
        Path missing = tmp.resolve("no-such-profile.xml");
        try (AnalysisService service = new AnalysisService()) {
            QualityProfileException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    QualityProfileException.class,
                    () -> service.analyze(new AnalyzeRequest(
                            FIXTURES.toAbsolutePath().toString(),
                            List.of("java/UtilityClass.java"),
                            List.of(),
                            missing.toString(),
                            List.of())));
            assertTrue(ex.getMessage().contains("no-such-profile.xml"),
                    "the error must name the missing profile, got: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("with no profile, Java analysis uses Sonar way defaults and raises java:S1118")
    void analyze_noProfile_usesSonarWayDefaults() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("java/UtilityClass.java"));
            assertTrue(response.issues().stream()
                            .anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "Sonar way default profile must activate java:S1118, got: "
                            + response.issues());
        }
    }

    @Test
    @DisplayName("a detected language with no available rules yields an AnalysisWarning, not a silent zero")
    void resolveActiveRules_languageWithNoRules_warns() {
        // An empty SonarWayProfiles stands in for an analyzer that ships no
        // profile resource: the detected language must surface a warning.
        SonarWayProfiles empty = SonarWayProfiles.load(java.nio.file.Path.of("plugins"))
                .restrictedToNone();
        List<dev.sonarcli.protocol.dto.AnalysisWarning> warnings = new java.util.ArrayList<>();
        AnalysisService.resolveActiveRules(
                empty, RuleParameterDefaults.empty(),
                Set.of(org.sonarsource.sonarlint.core.commons.api.SonarLanguage.GO), warnings);
        assertFalse(warnings.isEmpty(),
                "a detected language with zero rules must emit a warning");
        assertTrue(warnings.stream().anyMatch(w ->
                        w.message().contains("go") && w.message().contains("no rule set")),
                "the warning must name the language and explain the gap, got: " + warnings);
    }

    @Test
    @DisplayName("resolveActiveRules emits no warning when the language has Sonar way rules")
    void resolveActiveRules_languageWithRules_noWarning() {
        SonarWayProfiles profiles = SonarWayProfiles.load(java.nio.file.Path.of("plugins"));
        List<dev.sonarcli.protocol.dto.AnalysisWarning> warnings = new java.util.ArrayList<>();
        var rules = AnalysisService.resolveActiveRules(
                profiles, RuleParameterDefaults.empty(),
                Set.of(org.sonarsource.sonarlint.core.commons.api.SonarLanguage.GO), warnings);
        assertTrue(warnings.isEmpty(),
                "Go has a Sonar way profile, so no warning is expected, got: " + warnings);
        assertFalse(rules.isEmpty(), "Go must resolve to a non-empty active rule list");
    }

    @Test
    @DisplayName("Go fixture raises a go: issue with metadata-resolved severity/type")
    void analyze_go_raisesGoIssue() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("go/bad.go"));

            List<Issue> goIssues = response.issues().stream()
                    .filter(i -> i.ruleKey().startsWith("go:"))
                    .toList();
            assertFalse(goIssues.isEmpty(),
                    "expected at least one go: issue (path was previously a silent no-op), "
                            + "got: " + response.issues());
            for (Issue i : goIssues) {
                assertTrue(VALID_SEVERITIES.contains(i.severity()),
                        "go issue severity must be metadata-resolved, got: " + i.severity());
                assertTrue(VALID_TYPES.contains(i.type()),
                        "go issue type must be metadata-resolved, got: " + i.type());
            }
        }
    }

    @Test
    @DisplayName("an exported PascalCase Go function does not trip go:S100 (was a false positive)")
    void analyze_go_exportedFunction_noS100FalsePositive() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("go/idiomatic.go"));

            // go:S100 (function naming) run with the bare camelCase default
            // regex wrongly flagged every exported Go function — Go mandates
            // PascalCase for exported identifiers. Carrying the analyzer's own
            // Go `format` default into the active rule makes idiomatic Go clean.
            assertTrue(response.issues().stream().noneMatch(i -> "go:S100".equals(i.ruleKey())),
                    "exported PascalCase Go is idiomatic and must not raise go:S100, got: "
                            + response.issues());
        }
    }

    @Test
    @DisplayName("a crashed JS sensor surfaces an AnalysisWarning instead of a silent zero")
    void sensorFailureWarnings_crashedJsSensor_warns() {
        // The SonarLint engine swallows a sensor crash: it logs
        // "Error executing sensor: '<name>'" and leaves failedAnalysisFiles
        // empty. A JS/TS/CSS file that hit such a crash must still surface a
        // visible warning rather than be reported as a silent clean zero.
        List<String> engineLog = List.of(
                "Execute Sensor: JavaScript/TypeScript analysis",
                "Error executing sensor: 'JavaScript/TypeScript analysis'",
                "java.lang.IllegalStateException: Analysis of JS/TS files failed");

        List<dev.sonarcli.protocol.dto.AnalysisWarning> warnings =
                AnalysisService.sensorFailureWarnings(engineLog);

        assertFalse(warnings.isEmpty(),
                "a crashed JS sensor must emit a warning, got none");
        assertTrue(warnings.stream().anyMatch(w ->
                        w.message().toLowerCase().contains("javascript")
                                && w.message().toLowerCase().contains("not analyzed")),
                "the warning must name the analyzer and explain the files were "
                        + "not analyzed, got: " + warnings);
    }

    @Test
    @DisplayName("a crashed CSS sensor surfaces an AnalysisWarning")
    void sensorFailureWarnings_crashedCssSensor_warns() {
        List<String> engineLog = List.of(
                "Execute Sensor: CSS Rules",
                "Error executing sensor: 'CSS Rules'");

        List<dev.sonarcli.protocol.dto.AnalysisWarning> warnings =
                AnalysisService.sensorFailureWarnings(engineLog);

        assertFalse(warnings.isEmpty(), "a crashed CSS sensor must emit a warning");
        assertTrue(warnings.stream().anyMatch(w ->
                        w.message().toLowerCase().contains("css")),
                "the warning must name the CSS analyzer, got: " + warnings);
    }

    @Test
    @DisplayName("a clean engine log produces no sensor-failure warnings")
    void sensorFailureWarnings_noErrors_noWarnings() {
        List<String> engineLog = List.of(
                "Execute Sensor: JavaScript/TypeScript analysis",
                "1/1 source file has been analyzed",
                "Execute Sensor: HTML");

        assertTrue(AnalysisService.sensorFailureWarnings(engineLog).isEmpty(),
                "an engine log with no sensor errors must produce no warnings");
    }

    @Test
    @DisplayName("a crash in a non-JS-family sensor is not turned into a JS warning")
    void sensorFailureWarnings_nonJsSensor_ignored() {
        // Only the out-of-process JS/TS/CSS sensors are swallowed silently;
        // an in-process analyzer crash propagates as a real exception, so a
        // 'JavaSensor' error line must NOT be converted into a warning here.
        List<String> engineLog = List.of(
                "Error executing sensor: 'JavaSensor'",
                "Error executing sensor: 'Python Sensor'");

        assertTrue(AnalysisService.sensorFailureWarnings(engineLog).isEmpty(),
                "a non-JS-family sensor error must not produce a JS-family warning");
    }

    @Test
    @DisplayName("a successful JS analysis produces issues and no spurious sensor-failure warning")
    void analyze_javascript_successHasNoSensorFailureWarning() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("js/bad.js"));

            // The JS analyzer ran fine, so a real javascript: issue is present
            // and the sensor-failure path must stay silent — a healthy
            // analysis must never emit the "analyzer failed" warning.
            assertTrue(response.issues().stream()
                            .anyMatch(i -> i.ruleKey().startsWith("javascript:")),
                    "a healthy JS analysis must still raise a javascript: issue, "
                            + "got: " + response.issues());
            assertFalse(response.warnings().stream()
                            .anyMatch(w -> w.message().contains("analyzer failed")),
                    "a successful JS analysis must not emit a sensor-failure "
                            + "warning, got: " + response.warnings());
        }
    }

    @Test
    @DisplayName("close() deletes the analysis work directory from disk")
    void close_deletesWorkDir() {
        AnalysisService service = new AnalysisService();
        Path workDir = service.workDir();
        assertTrue(java.nio.file.Files.isDirectory(workDir),
                "work dir must exist while the service is open: " + workDir);
        service.close();
        assertFalse(java.nio.file.Files.exists(workDir),
                "work dir must be deleted after close(): " + workDir);
    }

    @Test
    @DisplayName("close() is idempotent and safe when the work dir is already gone")
    void close_idempotentWhenWorkDirGone() {
        AnalysisService service = new AnalysisService();
        service.close();
        // A second close() must not throw even though the dir is already deleted.
        service.close();
    }

    @Test
    @DisplayName("close() deletes the work dir even after an analysis populated it")
    void close_deletesNonEmptyWorkDir() {
        AnalysisService service = new AnalysisService();
        Path workDir = service.workDir();
        // An analysis makes the engine write a nested .sonarlinttmp_* directory
        // into the work dir; close() must still recursively remove the whole
        // tree (a non-recursive delete would leak the populated directory).
        service.analyze(request("java/UtilityClass.java"));
        assertTrue(java.nio.file.Files.isDirectory(workDir),
                "work dir must exist while the service is open: " + workDir);
        service.close();
        assertFalse(java.nio.file.Files.exists(workDir),
                "populated work dir must be fully deleted after close(): " + workDir);
    }

    @Test
    @DisplayName("two concurrent analyze() calls both complete with correct, uncorrupted results")
    void analyze_concurrentCalls_bothCorrect() throws Exception {
        try (AnalysisService service = new AnalysisService()) {
            int threads = 4;
            var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            // A start barrier makes every thread enter analyze() at the same
            // instant, maximizing contention on the serialization lock.
            var ready = new java.util.concurrent.CyclicBarrier(threads);
            var tasks = new java.util.ArrayList<
                    java.util.concurrent.Future<AnalyzeResponse>>();
            try {
                for (int i = 0; i < threads; i++) {
                    tasks.add(pool.submit(() -> {
                        ready.await();
                        return service.analyze(request("java/UtilityClass.java"));
                    }));
                }
                for (var task : tasks) {
                    AnalyzeResponse response = task.get(60, java.util.concurrent.TimeUnit.SECONDS);
                    assertTrue(response.issues().stream()
                                    .anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                            "every concurrent analysis must still raise java:S1118, got: "
                                    + response.issues());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("close() waits for an in-flight analysis to finish before stopping the engine")
    void close_waitsForInFlightAnalysis() throws Exception {
        AnalysisService service = new AnalysisService();
        var analysisFinished = new java.util.concurrent.atomic.AtomicBoolean(false);
        var closeReturned = new java.util.concurrent.atomic.AtomicBoolean(false);
        var insideLockedAnalysis = new java.util.concurrent.CountDownLatch(1);
        var releaseAnalysis = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<AnalyzeResponse>();

        // The test hook fires INSIDE the analyze() locked region. It signals
        // that the analysis now holds the serialization lock, then blocks
        // until the test releases it — pinning an analysis in-flight with no
        // sleeps and no timing guesses.
        service.setAnalysisEnteredHookForTest(() -> {
            insideLockedAnalysis.countDown();
            try {
                releaseAnalysis.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread analyzer = new Thread(() -> {
            result.set(service.analyze(request("java/UtilityClass.java")));
            analysisFinished.set(true);
        }, "analyzer");
        analyzer.start();

        // The analysis is now provably holding the lock.
        insideLockedAnalysis.await();

        Thread closer = new Thread(() -> {
            service.close();
            closeReturned.set(true);
        }, "closer");
        closer.start();

        // close() is contending for the same lock the pinned analysis holds.
        // While the analysis is pinned, close() MUST NOT have returned.
        // (Deterministic: the analysis cannot finish until releaseAnalysis is
        // counted down, so this is a true invariant, not a timing window.)
        Thread.sleep(100); // only to give a buggy close() a chance to escape
        assertFalse(closeReturned.get(),
                "close() must block while an analysis holds the serialization lock");
        assertFalse(analysisFinished.get(),
                "the pinned analysis must still be in-flight");

        // Release the analysis: it completes, then close() may proceed.
        releaseAnalysis.countDown();
        closer.join(60_000);
        analyzer.join(60_000);

        assertTrue(closeReturned.get(), "close() must return once the analysis finished");
        assertTrue(analysisFinished.get(), "the in-flight analysis must have finished");
        assertNotNull(result.get(), "the in-flight analysis must have produced a result");
        assertTrue(result.get().issues().stream()
                        .anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                "the in-flight analysis result must be correct, got: " + result.get().issues());
    }

    @Test
    @DisplayName("loadedLanguages() reflects only languages whose analyzer plugin actually loaded")
    void loadedLanguages_reflectsActuallyLoadedPlugins() {
        try (AnalysisService service = new AnalysisService()) {
            List<String> languages = service.loadedLanguages();
            assertFalse(languages.isEmpty(), "at least the java analyzer must load");

            // The reported set must be exactly the set PluginRuntime derives
            // from the truly-loaded plugins — never the full static set when a
            // plugin was skipped. We recompute the expected set independently.
            var loadedPlugins = PluginRuntime.loadAll(java.nio.file.Path.of("plugins"));
            var expected = PluginRuntime.loadedLanguagesFor(
                            loadedPlugins.getAllPluginInstancesByKeys().keySet())
                    .stream()
                    .map(org.sonarsource.sonarlint.core.commons.api.SonarLanguage
                            ::getSonarLanguageKey)
                    .sorted()
                    .toList();
            org.junit.jupiter.api.Assertions.assertEquals(expected, languages,
                    "loadedLanguages() must be derived from the actually-loaded plugins");

            // Every reported language must have a backing loaded plugin — a
            // skipped analyzer must never appear.
            var loadedKeys = loadedPlugins.getAllPluginInstancesByKeys().keySet();
            for (String key : languages) {
                var lang = org.sonarsource.sonarlint.core.commons.api.SonarLanguage
                        .forKey(key).orElseThrow();
                assertTrue(loadedKeys.contains(lang.getPluginKey()),
                        "reported language '" + key + "' must have a loaded plugin");
            }
        }
    }

    @Test
    @DisplayName("response is well-formed: every issue field is populated")
    void analyze_responseWellFormed() {
        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(request("java/UtilityClass.java"));

            assertNotNull(response.issues());
            assertNotNull(response.warnings());
            for (Issue issue : response.issues()) {
                assertNotNull(issue.ruleKey());
                assertNotNull(issue.filePath());
                assertNotNull(issue.severity());
                assertNotNull(issue.type());
                assertNotNull(issue.message());
            }
        }
    }

    @Test
    @DisplayName("a request file using .. to escape baseDir is rejected with a warning")
    void analyze_rejectsParentTraversalFile() {
        try (AnalysisService service = new AnalysisService()) {
            // A '..' segment that climbs above the fixture base directory.
            String escaping = "../../../../../../etc/passwd";
            AnalyzeResponse response = service.analyze(request(escaping));

            assertTrue(response.issues().isEmpty(),
                    "an out-of-tree file must produce no issues");
            assertTrue(response.warnings().stream()
                            .anyMatch(w -> escaping.equals(w.filePath())),
                    "an escaping '..' path must be reported as a warning: "
                            + response.warnings());
        }
    }

    @Test
    @DisplayName("a request file given as an absolute path is rejected with a warning")
    void analyze_rejectsAbsolutePathFile() {
        try (AnalysisService service = new AnalysisService()) {
            String absolute = "/etc/hostname";
            AnalyzeResponse response = service.analyze(request(absolute));

            assertTrue(response.issues().isEmpty(),
                    "an absolute-path file must produce no issues");
            assertTrue(response.warnings().stream()
                            .anyMatch(w -> absolute.equals(w.filePath())),
                    "an absolute path must be reported as a warning: "
                            + response.warnings());
        }
    }

    @Test
    @DisplayName("an out-of-tree file is rejected but the rest of the request still analyzes")
    void analyze_rejectsBadFileButKeepsGoodOnes() {
        try (AnalysisService service = new AnalysisService()) {
            String escaping = "../../../../../../etc/passwd";
            AnalyzeResponse response =
                    service.analyze(request("java/UtilityClass.java", escaping));

            assertTrue(response.issues().stream()
                            .anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "the in-tree file must still be analyzed: " + response.issues());
            assertTrue(response.warnings().stream()
                            .anyMatch(w -> escaping.equals(w.filePath())),
                    "the escaping file must still be warned about: "
                            + response.warnings());
        }
    }

    /** UtilityClass.java fixture body — triggers java:S1118 when analyzed. */
    private static final String UTILITY_CLASS_SOURCE = """
            public class UtilityClass {
                public static int doubleIt(int value) {
                    return value * 2;
                }
                public static int tripleIt(int value) {
                    return value * 3;
                }
            }
            """;

    /**
     * Writes a real, analyzable Java file into {@code baseDir} and returns an
     * {@link AnalyzeRequest} rooted at that directory for the given file names.
     */
    private static AnalyzeRequest requestIn(Path baseDir, String... files) {
        return new AnalyzeRequest(
                baseDir.toAbsolutePath().toString(),
                List.of(files),
                List.of(),
                null,
                List.of());
    }

    @Test
    @DisplayName("a symlink under baseDir pointing outside the tree is rejected with a warning")
    void analyze_rejectsSymlinkEscapingBaseDir(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        // The analysis base directory; an in-tree real file lives inside it.
        Path baseDir = java.nio.file.Files.createDirectory(tmp.resolve("project"));
        java.nio.file.Files.writeString(
                baseDir.resolve("UtilityClass.java"), UTILITY_CLASS_SOURCE);

        // A secret OUTSIDE the base directory the daemon must never read.
        Path outsideSecret = tmp.resolve("secret.java");
        java.nio.file.Files.writeString(outsideSecret, "public class Secret {}");

        // A symlink INSIDE baseDir whose name has no '..' and is not absolute,
        // so the old lexical startsWith() check passed it — yet it resolves to
        // a file outside the tree.
        Path escapeLink = baseDir.resolve("escape.java");
        try {
            java.nio.file.Files.createSymbolicLink(escapeLink, outsideSecret);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not support symbolic links; skipping: " + e);
            return;
        }

        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response =
                    service.analyze(requestIn(baseDir, "escape.java", "UtilityClass.java"));

            // The symlink escape must be rejected: warned, never analyzed.
            assertTrue(response.warnings().stream()
                            .anyMatch(w -> "escape.java".equals(w.filePath())),
                    "a symlink escaping baseDir must be warned about: "
                            + response.warnings());
            // The genuinely in-tree file must still be analyzed.
            assertTrue(response.issues().stream()
                            .anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "the in-tree real file must still be analyzed: " + response.issues());
        }
    }

    @Test
    @DisplayName("a symlink under baseDir pointing back inside the tree is still analyzed")
    void analyze_acceptsSymlinkResolvingInsideBaseDir(
            @org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path baseDir = java.nio.file.Files.createDirectory(tmp.resolve("project"));
        java.nio.file.Files.writeString(
                baseDir.resolve("UtilityClass.java"), UTILITY_CLASS_SOURCE);

        // A symlink inside baseDir pointing at another in-tree file: it resolves
        // to a real path still under baseDir, so it must be accepted.
        Path link = baseDir.resolve("alias.java");
        try {
            java.nio.file.Files.createSymbolicLink(
                    link, baseDir.resolve("UtilityClass.java"));
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not support symbolic links; skipping: " + e);
            return;
        }

        try (AnalysisService service = new AnalysisService()) {
            AnalyzeResponse response = service.analyze(requestIn(baseDir, "alias.java"));
            assertTrue(response.issues().stream()
                            .anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "a symlink resolving inside baseDir must still be analyzed: "
                            + response.issues());
        }
    }

    @Test
    @DisplayName("a non-existent request file is skipped with a warning, not a crash")
    void analyze_nonExistentFileSkippedGracefully(
            @org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path baseDir = java.nio.file.Files.createDirectory(tmp.resolve("project"));
        java.nio.file.Files.writeString(
                baseDir.resolve("UtilityClass.java"), UTILITY_CLASS_SOURCE);

        try (AnalysisService service = new AnalysisService()) {
            // 'ghost.java' does not exist on disk: resolving its real path
            // throws, and the request must skip-with-warning, never crash.
            AnalyzeResponse response =
                    service.analyze(requestIn(baseDir, "ghost.java", "UtilityClass.java"));

            assertTrue(response.warnings().stream()
                            .anyMatch(w -> "ghost.java".equals(w.filePath())),
                    "a non-existent file must be reported as a warning: "
                            + response.warnings());
            assertTrue(response.issues().stream()
                            .anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "the existing in-tree file must still be analyzed: "
                            + response.issues());
        }
    }
}
