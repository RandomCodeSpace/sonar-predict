package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.sonarcli.protocol.Json;
import dev.sonarcli.protocol.MessageCodec;
import dev.sonarcli.protocol.Method;
import dev.sonarcli.protocol.WireMessage;
import dev.sonarcli.protocol.dto.AnalyzeRequest;
import dev.sonarcli.protocol.dto.AnalyzeResponse;
import dev.sonarcli.protocol.dto.PingResponse;

class DaemonServerTest {

    /** Generous idle timeout: long enough that no test trips it accidentally. */
    private static final Duration LONG_IDLE = Duration.ofMinutes(10);
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private static AnalysisService service;

    @BeforeAll
    static void warmEngine() {
        service = new AnalysisService();
    }

    @AfterAll
    static void stopEngine() {
        service.close();
    }

    private RequestDispatcher dispatcher(AtomicBoolean shutdown, Runnable extra) {
        return new RequestDispatcher(service, service.ruleCatalog(), () -> {
            shutdown.set(true);
            extra.run();
        });
    }

    private static SocketChannel connect(Path socket) throws Exception {
        return SocketChannel.open(UnixDomainSocketAddress.of(socket));
    }

    private static WireMessage roundTrip(SocketChannel channel, WireMessage request)
            throws Exception {
        OutputStream out = Channels.newOutputStream(channel);
        InputStream in = Channels.newInputStream(channel);
        MessageCodec.writeMessage(out, request);
        return MessageCodec.readMessage(in);
    }

    @Test
    @DisplayName("a failed bind closes the opened server channel instead of leaking it")
    void bindFailureClosesServerChannel(@TempDir Path dir) throws Exception {
        // A socket path under a directory that does not exist: open() succeeds
        // but bind() fails. The opened channel must be closed, not leaked.
        Path socket = dir.resolve("missing-subdir").resolve("d.sock");
        assertFalse(Files.exists(socket.getParent()),
                "the parent directory must not exist, so bind() fails");

        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                LONG_IDLE);

        boolean startFailed = false;
        try {
            server.start();
        } catch (RuntimeException expected) {
            startFailed = true;
        }
        assertTrue(startFailed, "start() must fail when the socket cannot be bound");
        assertFalse(server.lastOpenedChannelIsOpen(),
                "a failed bind must close the opened server channel, not leak it");
    }

    @Test
    @DisplayName("a client connects, sends PING, and reads back a valid response")
    void ping_roundTrip(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                LONG_IDLE);
        server.start();
        server.awaitListening();

        try (SocketChannel channel = connect(socket)) {
            WireMessage response =
                    roundTrip(channel, new WireMessage("p1", Method.PING, null));
            assertEquals("p1", response.id());
            PingResponse ping =
                    Json.mapper().convertValue(response.payload(), PingResponse.class);
            assertFalse(ping.loadedLanguages().isEmpty());
        } finally {
            server.stop();
            server.awaitStopped();
        }
        assertFalse(Files.exists(socket), "socket file must be removed on stop");
    }

    @Test
    @DisplayName("multiple sequential requests on one connection all succeed")
    void multipleSequentialRequests(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                LONG_IDLE);
        server.start();
        server.awaitListening();

        try (SocketChannel channel = connect(socket)) {
            OutputStream out = Channels.newOutputStream(channel);
            InputStream in = Channels.newInputStream(channel);

            for (int i = 0; i < 3; i++) {
                MessageCodec.writeMessage(out, new WireMessage("p" + i, Method.PING, null));
                WireMessage response = MessageCodec.readMessage(in);
                assertEquals("p" + i, response.id(), "request " + i + " id must echo");
            }

            AnalyzeRequest analyze = new AnalyzeRequest(
                    FIXTURES.toAbsolutePath().toString(),
                    List.of("java/UtilityClass.java"), List.of(), null, List.of());
            MessageCodec.writeMessage(out, new WireMessage(
                    "a1", Method.ANALYZE, Json.mapper().valueToTree(analyze)));
            WireMessage analyzeResponse = MessageCodec.readMessage(in);
            AnalyzeResponse parsed =
                    Json.mapper().convertValue(analyzeResponse.payload(), AnalyzeResponse.class);
            assertTrue(parsed.issues().stream().anyMatch(i -> "java:S1118".equals(i.ruleKey())),
                    "ANALYZE over the socket must still raise java:S1118");
        } finally {
            server.stop();
            server.awaitStopped();
        }
    }

    @Test
    @DisplayName("a SHUTDOWN request stops the server")
    void shutdownRequestStopsServer(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        AtomicBoolean shutdownSeen = new AtomicBoolean(false);
        DaemonServer server = new DaemonServer(socket, null, LONG_IDLE);
        server.setDispatcher(
                req -> dispatcher(shutdownSeen, server::stop).dispatch(req));
        server.start();
        server.awaitListening();

        try (SocketChannel channel = connect(socket)) {
            WireMessage response =
                    roundTrip(channel, new WireMessage("s1", Method.SHUTDOWN, null));
            assertEquals("s1", response.id());
        }

        // The server stops itself; awaitStopped blocks on the real terminal
        // state — no sleep, fully deterministic.
        server.awaitStopped();
        assertTrue(shutdownSeen.get(), "SHUTDOWN must have reached the dispatcher");
        assertFalse(Files.exists(socket), "socket file must be removed after SHUTDOWN");
    }

    @Test
    @DisplayName("the server self-stops after the idle timeout with no activity")
    void idleTimeoutStopsServer(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        // A short, explicit idle timeout — the test waits on the real stopped
        // state, never a fixed sleep.
        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                Duration.ofMillis(200));
        server.start();
        server.awaitListening();

        // No client activity at all — the idle timer must fire and stop the server.
        server.awaitStopped();
        assertFalse(Files.exists(socket), "socket file must be removed on idle stop");
    }

    @Test
    @DisplayName("a connection that never sends a frame is closed after the read timeout")
    void silentConnectionIsTimedOut(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        // A short connection read timeout: a client that opens the connection
        // and sends nothing must be reaped, not pin a handler forever.
        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                LONG_IDLE,
                Duration.ofMillis(250));
        server.start();
        server.awaitListening();

        try (SocketChannel channel = connect(socket)) {
            // Send NOTHING. The server-side handler is blocked on the frame
            // read; the read-timeout watchdog must close the connection.
            InputStream in = Channels.newInputStream(channel);
            // A blocking read returns -1 (EOF) once the server closes our
            // channel — a deterministic signal, no sleep-and-poll.
            int firstByte = in.read();
            assertEquals(-1, firstByte,
                    "a silent connection must be closed by the server's read timeout");
        } finally {
            server.stop();
            server.awaitStopped();
        }
    }

    @Test
    @DisplayName("concurrent connection handlers are bounded by a fixed pool")
    void connectionHandlersAreBounded(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                LONG_IDLE,
                Duration.ofSeconds(30));
        server.start();
        server.awaitListening();

        assertTrue(server.maxConnectionHandlers() > 0,
                "the server must cap concurrent connection handlers");

        try {
            // Open more connections than the handler cap. Well-behaved
            // requests must still all succeed — the surplus connections queue
            // for a handler rather than spawning an unbounded thread each.
            int connections = server.maxConnectionHandlers() + 4;
            java.util.List<SocketChannel> channels = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < connections; i++) {
                    SocketChannel ch = connect(socket);
                    channels.add(ch);
                    WireMessage response =
                            roundTrip(ch, new WireMessage("b" + i, Method.PING, null));
                    assertEquals("b" + i, response.id(),
                            "request on connection " + i + " must succeed");
                }
            } finally {
                for (SocketChannel ch : channels) {
                    ch.close();
                }
            }
        } finally {
            server.stop();
            server.awaitStopped();
        }
    }

    @Test
    @DisplayName("connections beyond the bounded queue are rejected and closed, not queued as leaked fds")
    void connectionQueueIsBounded(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        // A dispatcher that parks every request until released: this pins all
        // handlers so the connection pool's bounded queue can be filled and
        // overflowed deterministically.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch dispatching = new CountDownLatch(1);
        DaemonServer server = new DaemonServer(
                socket,
                req -> {
                    dispatching.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new RequestDispatcher(service, service.ruleCatalog(), () -> { })
                            .dispatch(req);
                },
                LONG_IDLE,
                Duration.ofSeconds(30));
        server.start();
        server.awaitListening();

        java.util.List<SocketChannel> channels = new java.util.ArrayList<>();
        try {
            // Open more connections than the server can ever absorb: every
            // handler is parked in the dispatcher and the bounded queue holds
            // at most connectionQueueCapacity() more. A surplus beyond
            // handlers + queueCapacity therefore CANNOT be served — with the
            // bounded queue the accept loop closes each surplus socket; with
            // an unbounded queue they would all queue silently as leaked fds.
            // The extra margin guarantees at least one socket is in surplus
            // even as the accept loop drains the OS listen backlog.
            int surplus = server.maxConnectionHandlers()
                    + server.connectionQueueCapacity() + 16;
            boolean sawRejection = false;
            for (int i = 0; i < surplus; i++) {
                SocketChannel ch = connect(socket);
                channels.add(ch);
                // Write the frame in blocking mode (the stream view requires
                // it), then switch to non-blocking for the close probe below.
                // A write that fails here is itself a rejection signal: the
                // server already closed this surplus socket.
                try {
                    MessageCodec.writeMessage(
                            Channels.newOutputStream(ch),
                            new WireMessage("sat" + i, Method.PING, null));
                } catch (java.io.IOException reset) {
                    sawRejection = true;
                }
                ch.configureBlocking(false);
            }
            assertTrue(dispatching.await(5, TimeUnit.SECONDS),
                    "a handler must have started dispatching");

            // Poll every channel for a server-side close. A rejected socket
            // reads -1; a served-or-queued socket reads 0 (nothing yet — its
            // request is parked or still queued). Bounded overall wait, no
            // fixed sleep: the loop ends the instant a rejection is observed.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!sawRejection && System.nanoTime() < deadline) {
                for (SocketChannel ch : channels) {
                    java.nio.ByteBuffer probe = java.nio.ByteBuffer.allocate(1);
                    try {
                        if (ch.read(probe) == -1) {
                            sawRejection = true;
                            break;
                        }
                    } catch (java.io.IOException reset) {
                        sawRejection = true; // closed under the read.
                        break;
                    }
                }
            }
            assertTrue(sawRejection,
                    "a connection beyond the bounded queue must be closed, not leaked");
        } finally {
            release.countDown();
            for (SocketChannel ch : channels) {
                ch.close();
            }
            server.stop();
            server.awaitStopped();
        }
    }

    @Test
    @DisplayName("stop() closes active connection channels")
    void stopClosesActiveConnections(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                LONG_IDLE,
                Duration.ofSeconds(30));
        server.start();
        server.awaitListening();

        SocketChannel channel = connect(socket);
        try {
            // A real request, so the handler is established and the channel
            // is tracked as active.
            WireMessage response =
                    roundTrip(channel, new WireMessage("c1", Method.PING, null));
            assertEquals("c1", response.id());

            server.stop();
            server.awaitStopped();

            // The server closed our channel on stop(): a blocking read on it
            // now returns EOF immediately — deterministic, no sleep.
            InputStream in = Channels.newInputStream(channel);
            assertEquals(-1, in.read(),
                    "stop() must close active connection channels");
        } finally {
            channel.close();
        }
    }

    @Test
    @DisplayName("a request longer than the idle window is not aborted mid-flight by idle shutdown")
    void inFlightRequestSuppressesIdleShutdown(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        Duration idle = Duration.ofMillis(200);
        // The dispatcher signals when it has begun, then blocks well past the
        // idle window before producing a response. With the idle timer armed
        // only on frame RECEIPT, the timer would fire stop() during this
        // dispatch and the client would read EOF instead of a response.
        CountDownLatch dispatchStarted = new CountDownLatch(1);
        DaemonServer server = new DaemonServer(
                socket,
                req -> {
                    dispatchStarted.countDown();
                    try {
                        // Several idle windows long — deterministic, not a
                        // race against a fixed sleep: the assertion is that
                        // the response survives, regardless of exact timing.
                        Thread.sleep(idle.toMillis() * 5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new RequestDispatcher(service, service.ruleCatalog(), () -> { })
                            .dispatch(req);
                },
                idle);
        server.start();
        server.awaitListening();

        try (SocketChannel channel = connect(socket)) {
            OutputStream out = Channels.newOutputStream(channel);
            InputStream in = Channels.newInputStream(channel);
            MessageCodec.writeMessage(out, new WireMessage("slow", Method.PING, null));
            assertTrue(dispatchStarted.await(5, TimeUnit.SECONDS),
                    "the dispatcher must have started");

            // The long dispatch outlasts the idle window. The in-flight
            // request must suppress idle shutdown, so a full response still
            // comes back.
            WireMessage response = MessageCodec.readMessage(in);
            assertEquals("slow", response.id(),
                    "a request in flight must not be aborted by idle shutdown");
        } finally {
            server.stop();
            server.awaitStopped();
        }
    }

    @Test
    @DisplayName("the idle timer is armed only after a response completes, so idle shutdown still fires")
    void idleShutdownFiresAfterRequestCompletes(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        Duration idle = Duration.ofMillis(250);
        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                idle);
        server.start();
        server.awaitListening();

        // One request, then go quiet. Suppressing idle shutdown while a
        // request is in flight must not disable it: once the response is
        // written and the connection idles, the timer must still stop the
        // server.
        try (SocketChannel channel = connect(socket)) {
            WireMessage response =
                    roundTrip(channel, new WireMessage("q1", Method.PING, null));
            assertEquals("q1", response.id());
        }
        // awaitStopped blocks on the real terminal state — no sleep.
        server.awaitStopped();
        assertFalse(Files.exists(socket),
                "idle shutdown must still fire once requests are no longer in flight");
    }

    @Test
    @DisplayName("a frame claimed as the idle window elapses is not dropped by idle shutdown")
    void frameClaimedAtIdleExpiryIsNotDropped(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        // A short idle window — the idle timer is armed at start(). The test
        // drives the frame-read vs idle-shutdown race deterministically with
        // latches: the idle task is fired by hand at the exact instant a frame
        // has been claimed, with no fixed sleeps.
        Duration idle = Duration.ofMillis(200);
        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                idle);

        // claimReached  — counted down once, inside the synchronized claim,
        //                  after inFlight was incremented: a frame is claimed.
        // idleAttempted — counted down by the guarded idle task at its very
        //                  start, before it contends for the server monitor.
        // releaseClaim  — released once the idle task is confirmed running, so
        //                  the claim commits and the monitor is released.
        CountDownLatch claimReached = new CountDownLatch(1);
        CountDownLatch idleAttempted = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        AtomicBoolean firstClaim = new AtomicBoolean(true);

        server.setOnIdleTaskFired(idleAttempted::countDown);
        server.setOnRequestClaimed(() -> {
            if (!firstClaim.compareAndSet(true, false)) {
                return; // only the first request drives the race.
            }
            // The frame has been read and inFlight incremented; the server
            // monitor is held. Fire the guarded idle task on another thread —
            // it must block on the monitor here and, once released, observe
            // the in-flight request and abort instead of stopping the server.
            Thread idleFirer = new Thread(server::forceIdleTimeoutCheck, "test-idle-firer");
            idleFirer.setDaemon(true);
            idleFirer.start();
            claimReached.countDown();
            try {
                // Wait until the idle task is confirmed running before letting
                // the claim commit — the worst-case interleaving, forced.
                releaseClaim.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        server.start();
        server.awaitListening();

        try (SocketChannel channel = connect(socket)) {
            OutputStream out = Channels.newOutputStream(channel);
            InputStream in = Channels.newInputStream(channel);
            MessageCodec.writeMessage(out, new WireMessage("race", Method.PING, null));

            // The handler reads the frame and enters the synchronized claim.
            assertTrue(claimReached.await(5, TimeUnit.SECONDS),
                    "the handler must have claimed the frame");
            // The guarded idle task has started and is contending for the
            // server monitor the claim still holds.
            assertTrue(idleAttempted.await(5, TimeUnit.SECONDS),
                    "the idle task must have fired during the claim");
            // Let the claim commit: the idle task now acquires the monitor,
            // observes the in-flight request, and aborts instead of stopping.
            releaseClaim.countDown();

            WireMessage response = MessageCodec.readMessage(in);
            assertEquals("race", response.id(),
                    "a frame claimed as the idle window elapsed must still complete");
            assertFalse(server.stopping(),
                    "idle shutdown must not have fired under the claimed request");
        } finally {
            server.stop();
            server.awaitStopped();
        }
    }

    @Test
    @DisplayName("a request resets the idle timer so an active connection is not killed")
    void requestResetsIdleTimer(@TempDir Path dir) throws Exception {
        Path socket = dir.resolve("d.sock");
        Duration idle = Duration.ofMillis(300);
        DaemonServer server = new DaemonServer(
                socket,
                req -> new RequestDispatcher(service, service.ruleCatalog(), () -> { }).dispatch(req),
                idle);
        server.start();
        server.awaitListening();

        try (SocketChannel channel = connect(socket)) {
            OutputStream out = Channels.newOutputStream(channel);
            InputStream in = Channels.newInputStream(channel);
            // Fire several requests spaced under the idle window; each must
            // succeed, proving the timer is reset rather than expiring.
            for (int i = 0; i < 5; i++) {
                MessageCodec.writeMessage(out, new WireMessage("k" + i, Method.PING, null));
                WireMessage response = MessageCodec.readMessage(in);
                assertEquals("k" + i, response.id(),
                        "request " + i + " must succeed — idle timer must have been reset");
                Thread.sleep(idle.toMillis() / 3);
            }
        } finally {
            server.stop();
            server.awaitStopped();
        }
    }
}
