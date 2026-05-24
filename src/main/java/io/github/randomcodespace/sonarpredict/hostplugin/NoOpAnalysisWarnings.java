package io.github.randomcodespace.sonarpredict.hostplugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.notifications.AnalysisWarnings;
import org.sonarsource.api.sonarlint.SonarLintSide;

/**
 * Host-side implementation of {@link AnalysisWarnings} that the bundled analyzer sensors
 * Spring-autowire. Engine 11.3 ships zero references to {@code AnalysisWarnings}, so the
 * host plugin provides this bean.
 *
 * <p>Lifespan is {@code SINGLE_ANALYSIS} — maps to {@code ContainerLifespan.ANALYSIS}
 * in the engine. The per-analysis {@code AnalysisContainer} calls
 * {@code install(ContainerLifespan.ANALYSIS)}, so this bean is registered into the
 * same Spring container that wires sensors such as {@code HtmlSensor} which declare
 * {@code AnalysisWarnings} as a constructor parameter.
 */
@SonarLintSide(lifespan = SonarLintSide.SINGLE_ANALYSIS)
public final class NoOpAnalysisWarnings implements AnalysisWarnings {

    private static final Logger DEFAULT_LOG = LoggerFactory.getLogger(NoOpAnalysisWarnings.class);

    /**
     * The logger this instance writes through. Package-private and non-final
     * so tests can substitute a Mockito-mocked {@link Logger} without
     * pulling logback onto the test classpath (Socket Security flags
     * logback's class files as {@code obfuscatedFile}; we keep our
     * dependency surface clean by mocking the slf4j API directly).
     */
    Logger log = DEFAULT_LOG;

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public void addUnique(String message) {
        if (seen.add(message)) {
            log.warn("[sonar] {}", message);
        }
    }
}
