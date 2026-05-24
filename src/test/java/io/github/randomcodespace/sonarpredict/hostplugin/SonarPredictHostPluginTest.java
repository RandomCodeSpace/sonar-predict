package io.github.randomcodespace.sonarpredict.hostplugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.sonar.api.Plugin;

class SonarPredictHostPluginTest {

    @Test
    void define_addsNoOpAnalysisWarningsExtension() {
        SonarPredictHostPlugin plugin = new SonarPredictHostPlugin();
        Plugin.Context context = mock(Plugin.Context.class);

        plugin.define(context);

        verify(context).addExtension(NoOpAnalysisWarnings.class);
    }

    @Test
    void implementsPluginSpi() {
        assertThat(new SonarPredictHostPlugin()).isInstanceOf(Plugin.class);
    }
}
