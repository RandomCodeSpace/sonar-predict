package io.github.randomcodespace.sonarpredict.hostplugin;

import org.sonar.api.Plugin;

/**
 * The host plugin shipped as {@code sonar-predictor-host-${version}.jar} alongside the
 * 10 SonarSource analyzer plugins in the bundle. Registers host-side beans that
 * {@code sonarlint-analysis-engine} 11.x and later expect to find in the analysis
 * container but no longer provide.
 *
 * <p>This class uses only the public {@code org.sonar.api.Plugin} SPI and does not
 * depend on any {@code org.sonarsource.sonarlint.core.*} internal class.
 */
public final class SonarPredictHostPlugin implements Plugin {

    @Override
    public void define(Context context) {
        context.addExtension(NoOpAnalysisWarnings.class);
    }
}
