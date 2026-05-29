package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.analysis.command.AnalyzeCommand;

/**
 * Guards the narrow SonarLint internal surface {@link AnalysisService} depends
 * on, so an LTA engine upgrade that changes it fails the build here instead of
 * silently breaking analysis at runtime.
 *
 * <p>{@code AnalysisService} constructs {@link AnalyzeCommand} via its wide
 * positional constructor. If a future engine version drops or reshapes that
 * constructor, this test fails — a loud, compile-adjacent signal to revisit
 * {@code AnalysisService}'s engine wiring rather than discovering it through a
 * production analysis returning nothing.
 */
class EngineApiGuardTest {

    @Test
    @DisplayName("AnalyzeCommand still exposes the wide positional constructor AnalysisService uses")
    void analyzeCommandRetainsWideConstructor() {
        int maxParams = 0;
        for (Constructor<?> ctor : AnalyzeCommand.class.getDeclaredConstructors()) {
            maxParams = Math.max(maxParams, ctor.getParameterCount());
        }
        assertTrue(maxParams >= 10,
                "AnalyzeCommand's widest constructor now has " + maxParams
                        + " params (<10) — the embedded engine API changed; "
                        + "revisit AnalysisService's AnalyzeCommand construction after the bump");
    }
}
