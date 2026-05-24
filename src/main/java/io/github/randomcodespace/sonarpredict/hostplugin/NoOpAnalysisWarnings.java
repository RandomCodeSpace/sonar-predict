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
 * <p>Lifespan is {@code INSTANCE} (per-analysis) — the engine instantiates a fresh
 * instance per analysis, which resets the dedupe set automatically.
 */
@SonarLintSide(lifespan = SonarLintSide.INSTANCE)
public final class NoOpAnalysisWarnings implements AnalysisWarnings {

    private static final Logger LOG = LoggerFactory.getLogger(NoOpAnalysisWarnings.class);

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public void addUnique(String message) {
        if (seen.add(message)) {
            LOG.warn("[sonar] {}", message);
        }
    }
}
