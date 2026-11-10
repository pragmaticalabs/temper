package org.pragmatica.temper.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.temper.cli.config.CliVersion;
import org.pragmatica.temper.protocol.api.WsClientMessage;
import org.pragmatica.temper.protocol.api.WsServerMessage;
import org.pragmatica.temper.protocol.json.TemperJson;

/// An in-process stand-in for the controller: plain HTTP/1.1 routes under `/api/v1` on one loopback port,
/// written on raw sockets so it depends on nothing the real controller uses. Every route checks the bearer token
/// first (`401 {"error":"unauthorized"}`); an `Authorization: Lease <token>` passes as a task's lease token does at the
/// real router (m3-design §6.4), whose store decides the lease, here the scripted reply.
///
/// The event routes follow WP3's long-poll (m0-design §6.5 fallback, `ApiRoutes`/`EventFeed`):
/// - `GET /subscribers/{name}/next?terminal=` answers `200 {"gap":…}` when `cursor + 1 < min(seq)`, else
///   `200 {"event":…}` with the first event after the cursor; with nothing pending it waits for a new event at
///   most `pollWait` (30 s by default, as the controller) and answers `204`. It never moves the cursor.
/// - `POST /subscribers/{name}/ack` with `{"ack":{"seq":N}}` sets `cursor = max(cursor, N)` and answers `204`;
///   an unknown subscriber is `404`.
/// - A subscriber first seen by `next` starts at `max(seq)` (Q-14), so tests that expect earlier events create it
///   with [#subscriber] first. A subscriber name outside `[A-Za-z0-9_.-]{1,64}` is `400`.
final class FakeController implements AutoCloseable {
    static final Duration CONTROLLER_POLL_WAIT = Duration.ofSeconds(30);
    private static final Pattern SUBSCRIBER_ROUTE = Pattern.compile("^/subscribers/([^/]+)/(next|ack)$");
    private static final Pattern SUBSCRIBER = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    record Reply(int status, String body, String contentType) {
        static Reply json(int status, String body) {
            return new Reply(status, body, "application/json");
        }

        static Reply text(int status, String body) {
            return new Reply(status, body, "text/plain; charset=UTF-8");
        }
    }

    record Received(String method, String target, String body, String authorization) {}

    private final JsonMapper json = TemperJson.mapper();
    private final String token;
    private final ServerSocket server;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Deque<Reply>> routes = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    /// D-6: the `Temper-Version` header of every request, in order; empty when a request sent none.
    private final List<Optional<String>> sentVersions = new CopyOnWriteArrayList<>();
    private final List<WsServerMessage.WsEvent> events = new CopyOnWriteArrayList<>();
    private final Map<String, Long> cursors = new ConcurrentHashMap<>();
    private final List<Long> acks = new CopyOnWriteArrayList<>();
    private final AtomicInteger polls = new AtomicInteger();
    private final ReentrantLock appended = new ReentrantLock();
    private final Condition newEvent = appended.newCondition();
    private volatile Duration pollWait = CONTROLLER_POLL_WAIT;
    /// How long a scripted reply is held before it is sent (a long-poll the controller holds to its end).
    private volatile Duration answerAfter = Duration.ZERO;
    private volatile boolean longPoll = true;
    /// D-6: answer every ack 412, as a controller refusing a skewed CLI's write.
    private volatile boolean refuseAcks = false;
    /// D-6: the `Temper-Version` header of every answer; by default this CLI's own build version (a controller of the
    /// same build), none for a controller that predates build identity.
    private volatile Optional<String> temperVersion = Optional.of(CliVersion.bundled()
                                                                            .unwrap());
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    /// The VERDICT token, accepted on the VERDICT routes only. The full token gets 403 on a VERDICT route
    /// whether or not one is set (with none set, every caller of a VERDICT route is refused).
    private volatile Optional<String> verdictToken = Optional.empty();
    /// Routes that never answer (a controller that does not answer, G7.a).
    private final Set<String> hanging = ConcurrentHashMap.newKeySet();

    private FakeController(String token) throws IOException {
        this.token = token;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.submit(this::acceptLoop);
    }

    static FakeController start(String token) throws IOException {
        return new FakeController(token);
    }

    String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /// Scripts a route (`"GET /jobs/41"`, relative to `/api/v1`, without query). Several replies are served in
    /// order; the last one repeats.
    FakeController on(String route, Reply... replies) {
        routes.put(route, new ArrayDeque<>(List.of(replies)));
        return this;
    }

    FakeController onJson(String route, Object... bodies) {
        return on(route,
                  java.util.Arrays.stream(bodies)
                                  .map(body -> Reply.json(200, write(body)))
                                  .toArray(Reply[]::new));
    }

    /// Appends an event and wakes every waiting `next`.
    FakeController event(WsServerMessage.WsEvent event) {
        appended.lock();
        try {
            events.add(event);
            newEvent.signalAll();
        } finally {
            appended.unlock();
        }
        return this;
    }

    /// Creates (or moves) a subscriber's cursor.
    FakeController subscriber(String name, long cursor) {
        cursors.put(name, cursor);
        return this;
    }

    /// Every scripted reply is sent `delay` after its request arrived.
    FakeController answerAfter(Duration delay) {
        answerAfter = delay;
        return this;
    }

    /// How long `next` holds a request with nothing pending before answering `204`.
    FakeController pollWait(Duration wait) {
        pollWait = wait;
        return this;
    }

    /// Plays a controller of another build (`some`) or one that sends no `Temper-Version` header (`empty`).
    FakeController temperVersion(Optional<String> version) {
        temperVersion = version;
        return this;
    }

    /// Accepts `token` as the VERDICT token: on a VERDICT route only it passes, and the bearer token gets
    /// `403 forbidden: not permitted for this token`; on any other route it gets the same 403.
    FakeController verdictToken(String token) {
        verdictToken = Optional.of(token);
        return this;
    }

    /// Holds every request to `route` (`"GET /fleet/pr-fresh"`) without answering until the controller closes.
    FakeController hang(String route) {
        hanging.add(route);
        return this;
    }

    /// The VERDICT routes.
    static boolean isVerdictRoute(String method, String path) {
        return method.equals("POST") && (path.matches("^/fleet/go/[^/]+/(consume|outcome)$")
                                         || path.equals("/fleet/verdicts")
                                         || path.matches("^/fleet/merge-queue(/remove|/move)?$")
                                         || path.matches("^/fleet/ci-control-paths(/remove)?$")
                                         || path.equals("/fleet/pr-authors")
                                         || path.equals("/fleet/reviews"));
    }

    /// Plays a controller that refuses every ack with 412 (its version check on the CLI's write, D-6).
    FakeController refuseAcks() {
        refuseAcks = true;
        return this;
    }

    /// Plays a controller that predates the long-poll routes: they fall through to the scripted routes (`404`).
    FakeController withoutLongPoll() {
        longPoll = false;
        return this;
    }

    Optional<Long> cursor(String name) {
        return Optional.ofNullable(cursors.get(name));
    }

    int polls() {
        return polls.get();
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    List<Optional<String>> sentVersions() {
        return List.copyOf(sentVersions);
    }

    List<Long> acks() {
        return List.copyOf(acks);
    }

    String write(Object body) {
        return json.writeAsString(body)
                   .fold(cause -> {
                             throw new IllegalStateException(cause.message());
                         },
                         text -> text);
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (var socket : open) {
            socket.close();
        }
        threads.shutdownNow();
    }

    // --- connection handling ---
    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                var socket = server.accept();

                threads.submit(() -> serve(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        open.add(socket);
        try (socket) {
            var in = socket.getInputStream();
            var out = socket.getOutputStream();
            var requestLine = readLine(in);
            var headers = readHeaders(in);
            var parts = requestLine.split(" ");
            var method = parts[0];
            var target = parts[1];

            var length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            var body = new String(in.readNBytes(length), StandardCharsets.UTF_8);

            sentVersions.add(Optional.ofNullable(headers.get("temper-version")));
            http(out, method, target, body, headers.getOrDefault("authorization", ""));
        } catch (IOException | RuntimeException | InterruptedException dropped) {
            // connection ended; nothing to report
        }
    }

    private void http(OutputStream out, String method, String target, String body, String authorization)
            throws IOException, InterruptedException {
        received.add(new Received(method, target, body, authorization));

        var path = target.split("\\?")[0].substring("/api/v1".length());
        var subscriberRoute = SUBSCRIBER_ROUTE.matcher(path);
        if (hanging.contains(method + " " + path)) {
            Thread.sleep(Long.MAX_VALUE);
        }
        var verdictCall = verdictToken.map(verdict -> authorization.equals("Bearer " + verdict))
                                      .orElse(false);
        var verdictRoute = isVerdictRoute(method, path);
        var reply = !authorization.equals("Bearer " + token) && !authorization.startsWith("Lease ") && !verdictCall
                    ? Reply.json(401, "{\"error\":\"unauthorized\"}")
                    : verdictRoute != verdictCall
                      ? Reply.json(403, "{\"error\":\"forbidden: not permitted for this token\"}")
                    : longPoll && subscriberRoute.matches()
                      ? subscriberRoute(method, subscriberRoute, body)
                      : next(method + " " + path);
        var bytes = reply.body()
                         .getBytes(StandardCharsets.UTF_8);
        var head = "HTTP/1.1 " + reply.status() + " X\r\nContent-Type: " + reply.contentType() + "\r\nContent-Length: "
                   + bytes.length + temperVersion.map(version -> "\r\nTemper-Version: " + version).orElse("")
                   + "\r\nConnection: close\r\n\r\n";

        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private Reply next(String route) {
        var replies = routes.get(route);

        held(answerAfter);
        if (replies == null) {
            return Reply.json(404, "{\"error\":\"no route " + route + "\"}");
        }
        synchronized (replies) {
            return replies.size() > 1
                   ? replies.poll()
                   : replies.peek();
        }
    }

    private static void held(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- long-poll events (WP3 ApiRoutes: subscribers/{name}/next, subscribers/{name}/ack) ---
    private Reply subscriberRoute(String method, Matcher route, String body) throws InterruptedException {
        var name = route.group(1);

        if (!SUBSCRIBER.matcher(name).matches()) {
            return Reply.json(400, "{\"error\":\"Invalid subscriber: " + name + "\"}");
        }
        return switch (method + " " + route.group(2)) {
            case "GET next" -> nextEvent(name);
            case "POST ack" -> refuseAcks
                               ? Reply.json(412, "{\"error\":\"version skew\"}")
                               : ack(name, body);
            default -> Reply.json(404, "{\"error\":\"Not found\"}");
        };
    }

    private Reply nextEvent(String name) throws InterruptedException {
        polls.incrementAndGet();
        cursors.computeIfAbsent(name, _ -> maxSeq());

        var deadline = System.nanoTime() + pollWait.toNanos();

        appended.lock();
        try {
            var pending = pending(name);

            while (pending.isEmpty()) {
                var left = deadline - System.nanoTime();

                if (left <= 0) {
                    return new Reply(204, "", "application/json");
                }
                newEvent.await(left, TimeUnit.NANOSECONDS);
                pending = pending(name);
            }
            return Reply.json(200, write(pending.get()));
        } finally {
            appended.unlock();
        }
    }

    /// The gap first (retention passed the cursor), else the first event after the cursor.
    private Optional<WsServerMessage> pending(String name) {
        var cursor = cursors.get(name);
        var minSeq = events.stream()
                           .mapToLong(WsServerMessage.WsEvent::seq)
                           .min()
                           .orElse(0);

        if (minSeq > 0 && cursor + 1 < minSeq) {
            return Optional.of(new WsServerMessage.WsGap(cursor + 1, minSeq - 1));
        }
        return events.stream()
                     .filter(event -> event.seq() > cursor)
                     .findFirst()
                     .map(WsServerMessage.class::cast);
    }

    private long maxSeq() {
        return events.stream()
                     .mapToLong(WsServerMessage.WsEvent::seq)
                     .max()
                     .orElse(0);
    }

    private Reply ack(String name, String body) {
        var message = json.readString(body, WsClientMessage.class)
                          .fold(cause -> {
                                    throw new IllegalStateException(cause.message());
                                },
                                value -> value);

        if (!(message instanceof WsClientMessage.WsAck ack)) {
            return Reply.json(400, "{\"error\":\"Invalid ack body\"}");
        }
        if (!cursors.containsKey(name)) {
            return Reply.json(404, "{\"error\":\"Not found: subscriber " + name + "\"}");
        }
        acks.add(ack.seq());
        cursors.merge(name, ack.seq(), Math::max);
        return new Reply(204, "", "application/json");
    }

    // --- HTTP/1.1 head parsing ---
    private static int readByte(InputStream in) throws IOException {
        var value = in.read();

        if (value < 0) {
            throw new IOException("closed");
        }
        return value;
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new ByteArrayOutputStream();

        while (true) {
            var value = readByte(in);

            if (value == '\n') {
                var bytes = line.toByteArray();
                var length = bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                             ? bytes.length - 1
                             : bytes.length;

                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            line.write(value);
        }
    }

    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        var headers = new HashMap<String, String>();

        while (true) {
            var line = readLine(in);

            if (line.isEmpty()) {
                return Collections.unmodifiableMap(headers);
            }
            var colon = line.indexOf(':');

            headers.put(line.substring(0, colon)
                            .trim()
                            .toLowerCase(Locale.ROOT),
                        line.substring(colon + 1)
                            .trim());
        }
    }

    /// Output lines, keeping blank lines: only the final line terminator is dropped, so a stray empty line shows.
    static List<String> lines(String text) {
        var parts = new ArrayList<>(List.of(text.split("\n", -1)));
        if (text.endsWith("\n")) {
            parts.removeLast();
        }
        return text.isEmpty()
               ? List.of()
               : parts;
    }
}
