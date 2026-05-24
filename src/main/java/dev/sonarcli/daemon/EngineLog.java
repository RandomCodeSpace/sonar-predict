package dev.sonarcli.daemon;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sonarsource.sonarlint.core.commons.log.LogOutput;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;

/**
 * {@link LogOutput} implementation that routes SonarLint engine logs.
 *
 * <p>The SonarLint engine logs through a thread-local {@link SonarLintLogger}.
 * A target MUST be registered via {@link #install()} before any
 * {@code PluginsLoader.load()} call or the loader throws
 * {@code IllegalStateException: No log output configured}.
 *
 * <p>{@code LogOutput} is not a functional interface — both {@code log}
 * methods are {@code default} — so this is a named class. Each instance also
 * collects the messages it receives, which makes engine logs inspectable in
 * tests.
 */
public final class EngineLog implements LogOutput {

    private final List<String> messages = new CopyOnWriteArrayList<>();

    /**
     * Registers a fresh {@code EngineLog} as the global SonarLint log target.
     * Idempotent and safe to call repeatedly.
     */
    public static void install() {
        installAndCapture();
    }

    /**
     * Registers a fresh {@code EngineLog} as the global SonarLint log target
     * and returns it, so a caller can later inspect the engine messages that
     * target receives.
     *
     * <p>The SonarLint engine logs every sensor message — including a swallowed
     * {@code Error executing sensor: '<name>'} — through the global
     * {@link SonarLintLogger} target. Capturing the installed instance is what
     * lets {@code AnalysisService} detect a crashed JS/TS/CSS analyzer and
     * surface it as a warning rather than a silent zero.
     *
     * @return the {@code EngineLog} now installed as the global target
     */
    public static EngineLog installAndCapture() {
        EngineLog target = new EngineLog();
        SonarLintLogger.get().setTarget(target);
        return target;
    }

    @Override
    public void log(String formattedMessage, Level level) {
        if (formattedMessage != null) {
            messages.add(formattedMessage);
        }
    }

    /** Messages received by this instance, in arrival order. */
    public List<String> messages() {
        return List.copyOf(messages);
    }
}
