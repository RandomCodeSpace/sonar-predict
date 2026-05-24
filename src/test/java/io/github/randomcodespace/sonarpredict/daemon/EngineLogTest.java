package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.commons.log.LogOutput;

class EngineLogTest {

    @Test
    @DisplayName("install registers a log target without throwing")
    void install_doesNotThrow() {
        assertDoesNotThrow(EngineLog::install);
    }

    @Test
    @DisplayName("a constructed EngineLog collects messages it receives")
    void engineLog_collectsMessages() {
        EngineLog log = new EngineLog();
        assertTrue(log.messages().isEmpty());

        log.log("hello engine", LogOutput.Level.INFO);

        assertFalse(log.messages().isEmpty());
        assertTrue(log.messages().contains("hello engine"));
    }
}
