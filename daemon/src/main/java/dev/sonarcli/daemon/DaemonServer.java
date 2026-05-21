package dev.sonarcli.daemon;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.sonarcli.protocol.MessageCodec;
import dev.sonarcli.protocol.WireMessage;

/**
 * The daemon's socket server: a Unix-domain {@link ServerSocketChannel} accept
 * loop that serves framed {@link WireMessage} requests.
 *
 * <p><b>Lifecycle.</b> {@link #start} binds the socket and spawns the accept
 * thread; {@link #awaitListening} blocks until the socket is bound (so a test
 * client never races the bind). The server stops on either a {@code SHUTDOWN}
 * request (the supplied dispatcher's shutdown callback should call
 * {@link #stop}) or an idle-timeout expiry; {@link #awaitStopped} blocks until
 * the server has fully stopped and the socket file is removed. Both latches
 * make the server's observable state directly waitable — tests need no sleeps.
 *
 * <p><b>Idle timeout.</b> A single-thread scheduler holds one delayed "stop"
 * task. Every dispatched request reschedules it, so the timer only fires after
 * a full idle window with no traffic. Default 50 minutes; tests pass a short
 * window.
 *
 * <p><b>Connections.</b> Each accepted connection is served by a worker drawn
 * from a <em>bounded</em> handler pool ({@link #MAX_CONNECTION_HANDLERS}): a
 * read→dispatch→write loop over the channel's stream view
 * ({@link Channels#newInputStream}/{@link Channels#newOutputStream}). EOF or a
 * protocol error ends that connection without affecting the server. v1 expects
 * the CLI to use one connection at a time; concurrent connections are accepted
 * but the warm {@link AnalysisService} is single-threaded, so the dispatcher
 * must serialize — see {@code RequestDispatcher} usage.
 *
 * <p><b>Connection resource bounds.</b> A raw, per-connection thread would let
 * a flood of connections — accidental or hostile — exhaust threads and file
 * descriptors. Two guards prevent that: handlers come from a fixed pool, and
 * every connection carries a read/idle watchdog that closes a channel which
 * sends no frame within the connection timeout, so a silent client can never
 * pin a handler indefinitely. {@link #stop} closes every active channel.
 */
public final class DaemonServer {

    /** Default idle window before an unused daemon shuts itself down. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(50);

    /** Upper bound on concurrently-served connection handlers. */
    public static final int MAX_CONNECTION_HANDLERS = 16;

    /**
     * Upper bound on connections waiting for a free handler. Accepted sockets
     * beyond {@link #MAX_CONNECTION_HANDLERS} queue here; a socket that arrives
     * once this queue is also full is rejected and closed immediately rather
     * than left as an untracked, indefinitely-open file descriptor.
     */
    public static final int CONNECTION_QUEUE_CAPACITY = 64;

    /**
     * Default per-connection read/idle timeout. A connection that sends no
     * frame within this window is closed so it cannot pin a handler. Generous
     * enough that a well-behaved client pausing between requests is never cut.
     */
    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofMinutes(5);

    private final Path socketPath;
    private final Duration idleTimeout;
    private final Duration connectionTimeout;

    private volatile java.util.function.Function<WireMessage, WireMessage> dispatcher;
    private volatile Runnable onStop = () -> { };

    private final CountDownLatch listeningLatch = new CountDownLatch(1);
    private final CountDownLatch stoppedLatch = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    /**
     * Requests currently between "frame read" and "response written". While
     * this is non-zero the idle timer is suppressed: a long analysis must not
     * let the idle timer fire {@link #stop} mid-dispatch and lose its
     * response. The timer is (re)armed only when this falls back to zero.
     */
    private final AtomicInteger inFlight = new AtomicInteger();

    /** Channels of connections currently being served; closed by {@link #stop}. */
    private final Set<SocketChannel> activeChannels = ConcurrentHashMap.newKeySet();

    /**
     * The channel the current handler thread is serving. {@link #stop} skips
     * this thread's own channel so a {@code SHUTDOWN} request — whose dispatcher
     * callback calls {@code stop()} mid-dispatch — can still write its response
     * before the handler loop exits and closes the channel itself.
     */
    private final ThreadLocal<SocketChannel> currentChannel = new ThreadLocal<>();

    private ServerSocketChannel serverChannel;

    /**
     * The most recent channel returned by {@code ServerSocketChannel.open()}
     * in {@link #start}, retained only so a test can assert it is closed when
     * {@code bind()} fails. Not used by production logic.
     */
    private volatile ServerSocketChannel lastOpenedChannel;

    private Thread acceptThread;
    private ScheduledExecutorService idleScheduler;
    private volatile ScheduledFuture<?> idleTask;

    /**
     * Identity token of the currently-armed idle task. A scheduled idle task
     * captures the token live when it was armed; when it fires it commits to
     * {@code stop()} only if this still holds that same token (so a task
     * cancelled-but-already-running aborts) and {@link #inFlight} is zero.
     * Written under the same monitor as {@link #resetIdleTimer} and
     * {@link #claimRequest}; volatile so a test helper can read the live token
     * without contending for that monitor.
     */
    private volatile Object idleTaskToken;

    /**
     * Test-only seam: run inside the synchronized {@link #claimRequest} block,
     * after {@link #inFlight} has been incremented but before the monitor is
     * released. A test sets this to force the idle timer to fire at exactly the
     * frame-read→claim instant and prove the claimed request still completes.
     */
    private volatile Runnable onRequestClaimed = () -> { };

    /**
     * Test-only seam: run at the very start of a scheduled idle task, before
     * it contends for the server monitor. A test uses it to observe that the
     * idle timer fired while a request claim is in progress.
     */
    private volatile Runnable onIdleTaskFired = () -> { };

    /** Bounded pool that serves accepted connections; created in {@link #start}. */
    private ExecutorService connectionPool;

    /**
     * @param socketPath  the Unix domain socket path to bind
     * @param dispatcher  maps a request message to a response message; may be
     *                    {@code null} here and supplied later via
     *                    {@link #setDispatcher} (lets a dispatcher capture a
     *                    reference to this server for its shutdown callback)
     * @param idleTimeout the idle window before self-shutdown
     */
    public DaemonServer(
            Path socketPath,
            java.util.function.Function<WireMessage, WireMessage> dispatcher,
            Duration idleTimeout) {
        this(socketPath, dispatcher, idleTimeout, DEFAULT_CONNECTION_TIMEOUT);
    }

    /**
     * @param socketPath        the Unix domain socket path to bind
     * @param dispatcher        maps a request message to a response message;
     *                          may be {@code null} and supplied via
     *                          {@link #setDispatcher}
     * @param idleTimeout       the idle window before server self-shutdown
     * @param connectionTimeout the per-connection read/idle window — a
     *                          connection idle this long is closed
     */
    public DaemonServer(
            Path socketPath,
            java.util.function.Function<WireMessage, WireMessage> dispatcher,
            Duration idleTimeout,
            Duration connectionTimeout) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
        this.dispatcher = dispatcher;
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.connectionTimeout =
                Objects.requireNonNull(connectionTimeout, "connectionTimeout");
    }

    /** The cap on concurrently-served connection handlers. */
    int maxConnectionHandlers() {
        return MAX_CONNECTION_HANDLERS;
    }

    /** The cap on connections waiting for a free handler — for tests. */
    int connectionQueueCapacity() {
        return CONNECTION_QUEUE_CAPACITY;
    }

    /**
     * Whether the most recently opened server channel is currently open — for
     * tests. After a {@link #start} whose {@code bind()} failed this must be
     * {@code false}: the channel was opened but never bound, so it must have
     * been closed rather than leaked.
     */
    boolean lastOpenedChannelIsOpen() {
        ServerSocketChannel channel = lastOpenedChannel;
        return channel != null && channel.isOpen();
    }

    /** The count of connections currently being served — for tests. */
    int activeConnectionCount() {
        return activeChannels.size();
    }

    /** Whether {@link #stop} has been entered — for tests. */
    boolean stopping() {
        return stopping.get();
    }

    /**
     * Test-only: runs the guarded idle task body against the currently-armed
     * idle token, exactly as the scheduled idle timer would. Used to drive the
     * frame-read vs idle-shutdown race deterministically from another thread.
     */
    void forceIdleTimeoutCheck() {
        idleTimeoutFired(idleTaskToken);
    }

    /**
     * Test-only seam: registers a callback run inside the synchronized request
     * claim — after {@link #inFlight} has been incremented and the idle timer
     * cancelled, while the server monitor is still held. A test uses it to
     * force the idle timer to fire at the exact frame-read→claim instant and
     * verify the claimed request still completes.
     *
     * @param onRequestClaimed the callback; must not block on the server monitor
     */
    void setOnRequestClaimed(Runnable onRequestClaimed) {
        this.onRequestClaimed = Objects.requireNonNull(onRequestClaimed, "onRequestClaimed");
    }

    /**
     * Test-only seam: registers a callback run at the start of a scheduled
     * idle task, before it contends for the server monitor.
     *
     * @param onIdleTaskFired the callback; must not block on the server monitor
     */
    void setOnIdleTaskFired(Runnable onIdleTaskFired) {
        this.onIdleTaskFired = Objects.requireNonNull(onIdleTaskFired, "onIdleTaskFired");
    }

    /** Sets the request dispatcher; must be set before {@link #start}. */
    public void setDispatcher(java.util.function.Function<WireMessage, WireMessage> dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /**
     * Sets a callback run exactly once as part of {@link #stop}, before the
     * socket file is removed and {@link #awaitStopped} unblocks.
     *
     * <p>This is how the idle-timeout self-stop reaches the owning
     * {@link Daemon}'s full teardown (closing the warm engine, removing the
     * pidfile). Without it the idle timer would stop only the socket server
     * and leak the engine's work directory. The callback runs on whichever
     * thread calls {@code stop()} — the idle-timer thread, a {@code SHUTDOWN}
     * dispatcher thread, or a JVM shutdown hook — so it must be idempotent.
     *
     * @param onStop the teardown callback; must be set before {@link #start}
     */
    public void setOnStop(Runnable onStop) {
        this.onStop = Objects.requireNonNull(onStop, "onStop");
    }

    /**
     * Binds the socket and starts the accept loop on a background thread, then
     * arms the idle timer. Returns immediately; use {@link #awaitListening}.
     *
     * @throws IllegalStateException if no dispatcher has been set
     */
    public void start() {
        if (dispatcher == null) {
            throw new IllegalStateException("dispatcher must be set before start()");
        }
        // Open into a local so a bind() failure does not leak the channel:
        // ServerSocketChannel.open() can succeed and bind() then throw (a
        // missing parent directory, a permission error, an address already in
        // use). The field is assigned only after a successful bind, so a
        // failed start() leaves no half-open listening socket behind.
        try {
            Files.deleteIfExists(socketPath);
            ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            lastOpenedChannel = channel;
            try {
                channel.bind(UnixDomainSocketAddress.of(socketPath));
            } catch (IOException | RuntimeException bindFailure) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    bindFailure.addSuppressed(closeFailure);
                }
                throw bindFailure;
            }
            serverChannel = channel;
        } catch (IOException e) {
            throw new UncheckedIOException("could not bind daemon socket: " + socketPath, e);
        }

        idleScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daemon-idle-timer");
            t.setDaemon(true);
            return t;
        });
        resetIdleTimer();

        AtomicInteger handlerSeq = new AtomicInteger();
        // A fixed pool of handlers behind a *bounded* queue. Executors
        // .newFixedThreadPool uses an unbounded LinkedBlockingQueue, so a
        // flood of connections beyond the handler cap would queue without
        // limit — each an open fd untracked by activeChannels and the
        // watchdog. ArrayBlockingQueue caps the backlog; a submit() once it
        // is full throws RejectedExecutionException (default abort policy),
        // and the accept loop closes the surplus socket on that signal.
        connectionPool = new ThreadPoolExecutor(
                MAX_CONNECTION_HANDLERS, MAX_CONNECTION_HANDLERS,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CONNECTION_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "daemon-conn-" + handlerSeq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });

        acceptThread = new Thread(this::acceptLoop, "daemon-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** Blocks until the socket is bound and the accept loop is running. */
    public void awaitListening() throws InterruptedException {
        listeningLatch.await();
    }

    /** Blocks until the server has fully stopped and the socket file is removed. */
    public void awaitStopped() throws InterruptedException {
        stoppedLatch.await();
    }

    /**
     * Stops the server: closes the listening socket, cancels the idle timer,
     * runs the {@link #setOnStop onStop} teardown callback, and removes the
     * socket file. Idempotent and safe to call from any thread, including from
     * inside a dispatcher's shutdown callback or the idle-timer thread.
     *
     * <p><b>Ordering matters.</b> {@code onStop} runs <em>before</em>
     * {@link #stoppedLatch} counts down, so a caller blocked in
     * {@link #awaitStopped} is released only once teardown is complete. This is
     * what stops the daemon's JVM from exiting (its {@code main} returns when
     * {@code awaitStopped} unblocks) while the warm engine is still being
     * closed — the race that previously leaked the engine work directory.
     */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
            // Closing the listening socket unblocks the accept loop; a failure
            // here is not actionable — the loop exits on the closed channel.
        }
        // Close every active connection channel: a handler blocked in a frame
        // read unblocks (the channel is closed under it), so no handler can
        // outlive the server. Done before the schedulers shut down so the
        // per-connection watchdogs do not race the close.
        //
        // The caller's own channel is skipped: a SHUTDOWN request runs stop()
        // from inside its dispatcher, before its response is written. Closing
        // that channel here would truncate the response. Its handler loop sees
        // stopping==true after the write and closes the channel itself.
        SocketChannel callerChannel = currentChannel.get();
        for (SocketChannel channel : activeChannels) {
            if (channel == callerChannel) {
                continue;
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // Best effort — the handler exits on the closed channel.
            }
        }
        // Graceful shutdown — NOT shutdownNow(): shutdownNow() interrupts the
        // pool threads, and the SHUTDOWN handler that called stop() runs on
        // such a thread. An interrupt set on it would make the pending
        // response write throw ClosedByInterruptException and truncate the
        // SHUTDOWN ack. With shutdown(), no new tasks are accepted, in-flight
        // handlers finish their write, and every other connection's channel
        // was already closed above so its handler exits promptly.
        if (connectionPool != null) {
            connectionPool.shutdown();
        }
        if (idleScheduler != null) {
            idleScheduler.shutdownNow();
        }
        try {
            onStop.run();
        } catch (RuntimeException ignored) {
            // A teardown failure must not leave the socket file behind or
            // strand a caller waiting on awaitStopped().
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // Best effort — a stale socket file is cleaned by the next start().
        }
        stoppedLatch.countDown();
    }

    private void acceptLoop() {
        listeningLatch.countDown();
        try {
            while (!stopping.get()) {
                SocketChannel connection = serverChannel.accept();
                try {
                    connectionPool.submit(() -> serveConnection(connection));
                } catch (RejectedExecutionException rejected) {
                    // Two causes, one response: either the pool was shut down
                    // by a concurrent stop() (the accept loop is about to exit
                    // on the closed channel), or the server is saturated — all
                    // handlers busy and the bounded queue full. Either way the
                    // surplus socket is closed immediately so it cannot linger
                    // as an untracked open file descriptor.
                    closeQuietly(connection);
                }
            }
        } catch (ClosedChannelException expected) {
            // stop() closed the listening socket — normal shutdown path.
        } catch (IOException e) {
            // An unexpected accept failure: stop the server rather than spin.
            if (!stopping.get()) {
                stop();
            }
        }
    }

    private void serveConnection(SocketChannel connection) {
        activeChannels.add(connection);
        currentChannel.set(connection);
        // The read/idle watchdog: if no frame arrives within connectionTimeout
        // the channel is closed, which unblocks the frame read below. A silent
        // client can therefore never pin this handler indefinitely.
        ScheduledFuture<?> watchdog = scheduleConnectionTimeout(connection);
        try (SocketChannel channel = connection) {
            InputStream in = Channels.newInputStream(channel);
            OutputStream out = Channels.newOutputStream(channel);
            while (!stopping.get()) {
                WireMessage request;
                try {
                    request = MessageCodec.readMessage(in);
                } catch (EOFException eof) {
                    return; // client closed the connection — normal.
                }
                // A frame has been fully read. Claim it as in-flight FIRST —
                // before any other work — so the read→dispatch window is
                // covered: claimRequest() atomically marks the request in
                // flight and disarms the idle timer under the server monitor,
                // and any idle task that fires after this point re-checks
                // inFlight and aborts. Doing the claim before re-arming the
                // connection watchdog closes the race where the idle timer
                // could fire stop() in the gap between the frame read and the
                // increment, closing the channel under an already-read request.
                claimRequest();
                // A frame arrived: re-arm the connection watchdog so a silent
                // client between frames is still reaped.
                watchdog = rearmConnectionTimeout(watchdog, connection);
                try {
                    WireMessage response = dispatcher.apply(request);
                    MessageCodec.writeMessage(out, response);
                } finally {
                    if (inFlight.decrementAndGet() == 0) {
                        resetIdleTimer();
                    }
                }
            }
        } catch (IOException e) {
            // Connection-level failure (truncated frame, reset, the watchdog
            // closing a silent connection, …): drop this connection only; the
            // server keeps serving others.
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            activeChannels.remove(connection);
            currentChannel.remove();
        }
    }

    /**
     * Schedules a one-shot task that closes {@code connection} after the
     * connection timeout. Closing the channel unblocks a handler stuck in a
     * frame read. Returns {@code null} if the scheduler is already shutting
     * down (stop() will close the channel anyway).
     */
    private ScheduledFuture<?> scheduleConnectionTimeout(SocketChannel connection) {
        ScheduledExecutorService scheduler = idleScheduler;
        if (scheduler == null || scheduler.isShutdown()) {
            return null;
        }
        try {
            return scheduler.schedule(
                    () -> closeQuietly(connection),
                    connectionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            return null;
        }
    }

    /** Cancels the current connection watchdog and schedules a fresh one. */
    private ScheduledFuture<?> rearmConnectionTimeout(
            ScheduledFuture<?> current, SocketChannel connection) {
        if (current != null) {
            current.cancel(false);
        }
        return scheduleConnectionTimeout(connection);
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best effort — the handler exits on the closed channel.
        }
    }

    /**
     * Claims a freshly-read frame as an in-flight request: increments
     * {@link #inFlight} and disarms the idle timer, atomically under the
     * server monitor.
     *
     * <p>This is the close of the frame-read→dispatch race. The increment and
     * the idle-timer cancellation happen under the same monitor a guarded idle
     * task acquires before it commits to {@code stop()}. So an idle task that
     * fires concurrently with a claim is fully ordered: it either runs before
     * this claim (and {@code stop()} is in progress — the connection retries),
     * or after it (and the guard observes {@code inFlight > 0} and aborts).
     * The previous design incremented {@code inFlight} only several statements
     * after the frame was read, leaving a window in which the idle timer could
     * close the channel under an already-read request.
     */
    private synchronized void claimRequest() {
        // Increment inFlight FIRST: from here on any guarded idle task that
        // acquires the monitor observes inFlight > 0 and aborts.
        inFlight.incrementAndGet();
        // Test-only seam — see onRequestClaimed. Runs while the monitor is
        // held and inFlight is already incremented, so a concurrently-firing
        // idle task is forced to block here and then abort on inFlight > 0.
        onRequestClaimed.run();
        if (idleTask != null) {
            idleTask.cancel(false);
            idleTask = null;
        }
        idleTaskToken = null;
    }

    /**
     * (Re)arms the idle-shutdown timer. Cancelling the previous task and
     * scheduling a fresh one means the timer only elapses after a full idle
     * window with no request traffic.
     *
     * <p>While any request is in flight the timer is left disarmed: the
     * previous task is cancelled but no new one is scheduled, so idle
     * shutdown cannot fire mid-dispatch. The handler loop calls this again
     * once {@link #inFlight} falls back to zero, which (re)arms the timer for
     * a genuinely idle server.
     */
    private synchronized void resetIdleTimer() {
        if (stopping.get() || idleScheduler == null || idleScheduler.isShutdown()) {
            return;
        }
        if (idleTask != null) {
            idleTask.cancel(false);
            idleTask = null;
        }
        idleTaskToken = null;
        if (inFlight.get() > 0) {
            // A request is executing — defer arming until it completes.
            return;
        }
        Object token = new Object();
        idleTaskToken = token;
        try {
            idleTask = idleScheduler.schedule(
                    () -> idleTimeoutFired(token),
                    idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            idleTaskToken = null;
        }
    }

    /**
     * The body of a scheduled idle task. It commits to {@link #stop} only if,
     * under the server monitor, it is still the armed task ({@code token}
     * matches {@link #idleTaskToken}) and no request is in flight. A task that
     * was cancelled but had already started running, or one that fires just as
     * a frame is claimed, therefore aborts harmlessly instead of closing the
     * channel under an active request.
     *
     * @param token the identity token captured when this task was armed
     */
    private void idleTimeoutFired(Object token) {
        onIdleTaskFired.run();
        synchronized (this) {
            if (stopping.get() || idleTaskToken != token || inFlight.get() > 0) {
                return; // superseded, or a request is in flight — do not stop.
            }
            idleTaskToken = null;
        }
        stop();
    }
}
