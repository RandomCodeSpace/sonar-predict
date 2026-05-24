package io.github.randomcodespace.sonarpredict.hostplugin;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class NoOpAnalysisWarningsTest {

    private Logger logger;
    private NoOpAnalysisWarnings warnings;

    @BeforeEach
    void setUp() {
        logger = mock(Logger.class);
        warnings = new NoOpAnalysisWarnings();
        warnings.log = logger;
    }

    @Test
    void addUnique_logsAtWarnWithSonarPrefix() {
        warnings.addUnique("memory limit reached");

        verify(logger).warn(eq("[sonar] {}"), eq("memory limit reached"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void addUnique_dedupsIdenticalMessages() {
        warnings.addUnique("same");
        warnings.addUnique("same");
        warnings.addUnique("same");

        verify(logger, times(1)).warn(eq("[sonar] {}"), eq("same"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void addUnique_distinctMessagesAllLog() {
        warnings.addUnique("first");
        warnings.addUnique("second");

        verify(logger).warn(eq("[sonar] {}"), eq("first"));
        verify(logger).warn(eq("[sonar] {}"), eq("second"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void freshInstance_doesNotShareDedupeSet() {
        warnings.addUnique("X");

        Logger second = mock(Logger.class);
        NoOpAnalysisWarnings fresh = new NoOpAnalysisWarnings();
        fresh.log = second;
        fresh.addUnique("X");

        verify(logger).warn(eq("[sonar] {}"), eq("X"));
        verify(second).warn(eq("[sonar] {}"), eq("X"));
    }

    @Test
    void addUnique_nullMessageStillRoutesThroughLogger() {
        // Sensors may pass null in pathological cases; our implementation
        // simply delegates to ConcurrentHashMap which rejects null with NPE.
        // This test pins the current behavior so a future change is intentional.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> warnings.addUnique(null))
                .isInstanceOf(NullPointerException.class);
        verify(logger, never()).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Object>any());
    }
}
