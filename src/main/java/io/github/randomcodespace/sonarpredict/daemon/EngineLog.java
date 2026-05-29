package io.github.randomcodespace.sonarpredict.daemon;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final AtomicReference<EngineLog> CURRENT = new AtomicReference<>();

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
        CURRENT.set(target);
        return target;
    }

    /**
     * Returns the most recently {@link #install installed} {@code EngineLog},
     * or {@code null} if none has been installed yet. Tests use this to assert
     * on engine messages emitted by code paths (such as
     * {@code PluginRuntime.loadAll}) that install their own {@code EngineLog}
     * internally without exposing the reference.
     */
    public static EngineLog current() {
        return CURRENT.get();
    }

    @Override
    public void log(String formattedMessage, Level level) {
        if (formattedMessage != null) {
            messages.add(formattedMessage);
        }
    }

    /**
     * Discards every message collected so far, scoping this target to the lines
     * received from now on.
     *
     * <p>A single {@code EngineLog} lives for the whole daemon and receives a
     * line for every engine log message of every scan. Without this reset the
     * backing list grows unbounded across the daemon's lifetime — hundreds of
     * scans over its ~50-minute life — a memory leak. {@code AnalysisService}
     * calls it at the start of each analysis (holding its analysis lock, before
     * the engine worker thread is posted), so the list is bounded to one scan's
     * lines.
     *
     * <p>{@code clear()} on the backing {@link CopyOnWriteArrayList} is atomic
     * and publishes through the same volatile array as {@link #log}, so the
     * reset cannot race or tear against an {@link #log add} from the engine
     * worker thread.
     */
    public void reset() {
        messages.clear();
    }

    /** Messages received by this instance, in arrival order. */
    public List<String> messages() {
        return List.copyOf(messages);
    }
}
