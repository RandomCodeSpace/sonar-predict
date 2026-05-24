package io.github.randomcodespace.sonarpredict.hostplugin;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class NoOpAnalysisWarningsTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private NoOpAnalysisWarnings warnings;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(NoOpAnalysisWarnings.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        warnings = new NoOpAnalysisWarnings();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void addUnique_logsAtWarn() {
        warnings.addUnique("memory limit reached");

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("[sonar]", "memory limit reached");
    }

    @Test
    void addUnique_dedupsIdenticalMessages() {
        warnings.addUnique("same");
        warnings.addUnique("same");
        warnings.addUnique("same");

        assertThat(appender.list).hasSize(1);
    }

    @Test
    void addUnique_distinctMessagesAllLog() {
        warnings.addUnique("first");
        warnings.addUnique("second");

        assertThat(appender.list).hasSize(2);
    }

    @Test
    void freshInstance_doesNotShareDedupeSet() {
        warnings.addUnique("X");
        new NoOpAnalysisWarnings().addUnique("X");

        assertThat(appender.list).hasSize(2);
    }
}
