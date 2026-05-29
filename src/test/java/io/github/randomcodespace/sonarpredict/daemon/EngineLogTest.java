package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

    @Test
    @DisplayName("reset discards earlier messages so a single scan still sees its own lines")
    void reset_thenLog_keepsCurrentScanLines() {
        EngineLog log = new EngineLog();
        log.log("Error executing sensor: 'JavaScript/TypeScript analysis'",
                LogOutput.Level.ERROR);

        // Start of a scan: discard whatever a prior scan left behind.
        log.reset();
        assertTrue(log.messages().isEmpty());

        // The current scan's lines are still fully captured (equivalence: a
        // single scan that logs a warning surfaces exactly that warning).
        log.log("Error executing sensor: 'CSS Rules'", LogOutput.Level.ERROR);

        assertEquals(
                List.of("Error executing sensor: 'CSS Rules'"),
                log.messages());
    }

    @Test
    @DisplayName("messages() holds only the last scan's lines across many reset scans (leak guard)")
    void reset_acrossManyScans_doesNotAccumulate() {
        EngineLog log = new EngineLog();

        // Three simulated scans, each resetting at its start and adding two
        // lines. Pre-fix the backing list grew to 6 lines (the daemon-lifetime
        // leak); with reset() it stays bounded to one scan's two lines.
        for (int scan = 0; scan < 3; scan++) {
            log.reset();
            log.log("scan " + scan + " line A", LogOutput.Level.INFO);
            log.log("scan " + scan + " line B", LogOutput.Level.INFO);
        }

        assertEquals(
                List.of("scan 2 line A", "scan 2 line B"),
                log.messages(),
                "messages() must hold only the last scan's lines, not the accumulation");
    }
}
