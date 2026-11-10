package org.pragmatica.temper.controller.host;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.frame.FrameCodec;
import org.pragmatica.temper.protocol.frame.FrameReader;
import org.pragmatica.temper.protocol.frame.FrameWriter;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.store.api.HostName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// One live ssh session to a worker: length-prefixed frames over the process's stdin/stdout (m0-design §4.1).
///
/// - **Requests** carry a fresh `Envelope.id`; the reply with that `replyTo` resolves the request's promise. Every
///   request has a timeout; a `Failure` reply becomes [LinkError.WorkerFailure].
/// - **The first `Hello`** resolves [#hello]; every other message without a pending `replyTo` goes to the inbound
///   handler (with this connection, so replies go back on the same session), in arrival order, on the reader thread.
/// - **Closing** happens once, on EOF, a frame error, the process exit or [#close]: the process is killed and every
///   pending request fails with [LinkError.Down].
///
/// stderr is drained into the log, so the ssh process never blocks on a full pipe; a line that reports a host-key
/// failure ([SshDiagnosis]) before any `Hello` resolves [#hostKeyFailure] (m4-design §2.2); after a `Hello` the lines
/// are the worker's, so a worker that logs such text never makes its host LOST.
public final class Connection {
    private static final Logger log = LoggerFactory.getLogger(Connection.class);

    private final HostName host;
    private final Spawned process;
    private final FrameWriter writer;
    private final JsonMapper mapper;
    private final BiConsumer<Connection, Message> inbound;
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong lastFrameNanos = new AtomicLong(System.nanoTime());
    private final AtomicBoolean open = new AtomicBoolean(true);
    /// A `Hello` arrived: from then on stderr is the worker's own, never classified (m4-design §2.2).
    private final AtomicBoolean greeted = new AtomicBoolean(false);
    /// stderr lines drained and classified so far, for tests (a line counted here was judged).
    private final AtomicInteger stderrLines = new AtomicInteger();
    private final Map<Long, Promise<Message>> pending = new ConcurrentHashMap<>();
    private final Promise<Message.Hello> hello = Promise.promise();
    private final Promise<Unit> closed = Promise.promise();
    private final Promise<Unit> hostKeyFailure = Promise.promise();

    private Connection(HostName host, Spawned process, JsonMapper mapper, BiConsumer<Connection, Message> inbound) {
        this.host = host;
        this.process = process;
        this.writer = FrameWriter.frameWriter(process.stdin());
        this.mapper = mapper;
        this.inbound = inbound;
    }

    /// Starts the reader and the stderr drain on virtual threads.
    public static Connection connection(HostName host,
                                        Spawned process,
                                        JsonMapper mapper,
                                        BiConsumer<Connection, Message> inbound) {
        var connection = new Connection(host, process, mapper, inbound);

        Thread.ofVirtual().name("temper-link-" + host).start(connection::readLoop);
        Thread.ofVirtual().name("temper-link-stderr-" + host).start(connection::drainStderr);
        // May run late: the close on exit is detached; nothing waits for it, and `close` is idempotent.
        process.exit().onResultRun(connection::close);

        return connection;
    }

    public HostName host() {
        return host;
    }

    /// The worker's first `Hello`; fails with [LinkError.Down] if the connection closes first.
    public Promise<Message.Hello> hello() {
        return hello;
    }

    /// Resolves when the connection has closed.
    public Promise<Unit> closed() {
        return closed;
    }

    /// Resolves when ssh reports on stderr that the host's key does not match the configured `knownHosts` (or has no
    /// entry there); stays pending otherwise. Read apart from [#closed], since stderr and the exit race.
    public Promise<Unit> hostKeyFailure() {
        return hostKeyFailure;
    }

    public boolean isOpen() {
        return open.get();
    }

    /// Nanoseconds since the last frame arrived (liveness, m0-design §6.4).
    public long silenceNanos() {
        return System.nanoTime() - lastFrameNanos.get();
    }

    /// Sends `message` and waits for its reply, at most `timeout`.
    public Promise<Message> request(Message message, TimeSpan timeout) {
        var id = ids.incrementAndGet();
        var reply = Promise.<Message> promise();

        pending.put(id, reply);
        // May run late: `pending` is read only by a reply frame for `id` or by `close`, and both act on the resolved
        // `reply` as a no-op; the late removal only moves when a reply after the timeout counts as unsolicited.
        return awaited(id, message, reply, timeout).onResultRun(() -> forget(id))
                      .flatMap(answer -> workerFailure(answer).async());
    }

    /// The id is registered BEFORE the open check, so a concurrent close either sees it and fails it, or ran first
    /// and this check fails it: a request never waits its whole timeout on a closed session.
    private Promise<Message> awaited(long id, Message message, Promise<Message> reply, TimeSpan timeout) {
        return open.get()
               ? replyTo(id, message, reply, timeout)
               : LinkError.Down.FACTORY.apply(host).promise();
    }

    private Promise<Message> replyTo(long id, Message message, Promise<Message> reply, TimeSpan timeout) {
        return write(Envelope.envelope(id, message)).async()
                    .flatMap(_ -> reply.timeout(timeout));
    }

    private Unit forget(long id) {
        pending.remove(id);

        return unit();
    }

    /// The reply must be of the `expected` type; anything else is a protocol mismatch.
    public static Result<Message> expect(Message reply, Class<? extends Message> expected) {
        return expected.isInstance(reply)
               ? success(reply)
               : LinkError.Unexpected.FACTORY.apply(expected.getSimpleName(),
                                                    reply.getClass().getSimpleName())
                                             .result();
    }

    /// Sends `message` without waiting for a reply.
    public Result<Unit> send(Message message) {
        return write(Envelope.envelope(ids.incrementAndGet(), message));
    }

    /// Closes once: kills the ssh process and fails every pending request. Idempotent.
    public Unit close() {
        return open.compareAndSet(true, false)
               ? closeOnce()
               : unit();
    }

    private Unit closeOnce() {
        var down = LinkError.Down.FACTORY.apply(host);

        process.kill();
        pending.keySet().forEach(id -> option(pending.remove(id)).onPresent(reply -> reply.fail(down)));
        hello.fail(down);
        closed.succeed(unit());

        return unit();
    }

    private Result<Unit> write(Envelope envelope) {
        return frame(envelope).flatMap(this::transmit)
                    .mapError(cause -> LinkError.SendFailed.FACTORY.apply(host, cause));
    }

    /// Write and flush one whole frame.
    private Result<Unit> transmit(byte[] frame) {
        return writer.write(frame);
    }

    private Result<byte[]> frame(Envelope envelope) {
        return FrameCodec.encode(envelope, mapper);
    }

    private static Result<Message> workerFailure(Message reply) {
        return switch (reply) {
            case Message.Failure failure -> LinkError.WorkerFailure.FACTORY.apply(failure.code(), failure.detail()).result();
            default -> success(reply);
        };
    }

    // ---- reader ------------------------------------------------------------------------------------------------
    /// Thread body: reads frames until EOF or a frame the stream cannot recover from, then closes.
    @Contract
    private void readLoop() {
        var reader = FrameReader.frameReader(process.stdout());

        while (open.get() && readOne(reader)) {
            lastFrameNanos.set(System.nanoTime());
        }

        close();
    }

    /// One frame: `true` to keep reading. A clean EOF, a broken stream or an undecodable frame ends the session.
    private boolean readOne(FrameReader reader) {
        return reader.next()
                     .flatMap(this::envelope)
                     .onFailure(cause -> log.info("[{}] link ends: {}",
                                                  host,
                                                  cause.message()))
                     .onSuccess(this::dispatch)
                     .isSuccess();
    }

    /// A clean EOF (no frame) ends the session like a broken stream does.
    private Result<Envelope> envelope(Option<byte[]> frame) {
        return frame.toResult(LinkError.Down.FACTORY.apply(host))
                    .flatMap(this::decoded);
    }

    private Result<Envelope> decoded(byte[] payload) {
        return FrameCodec.decode(payload, mapper);
    }

    private Unit dispatch(Envelope envelope) {
        return envelope.replyTo()
                       .flatMap(this::awaiting)
                       .map(reply -> answer(reply,
                                            envelope.msg()))
                       .or(() -> unsolicited(envelope.msg()));
    }

    private Option<Promise<Message>> awaiting(long id) {
        return option(pending.remove(id));
    }

    /// The reply's continuations (the next request, store writes) run on the async executor, never on this reader
    /// thread: a continuation that writes a frame must not stall the read loop, or a full pipe in both directions
    /// would deadlock the session.
    private static Unit answer(Promise<Message> reply, Message message) {
        reply.async(promise -> promise.succeed(message));

        return unit();
    }

    private Unit unsolicited(Message message) {
        return switch (message) {
            case Message.Hello first when!hello.isResolved() -> greet(first);
            default -> deliver(message);
        };
    }

    private Unit greet(Message.Hello first) {
        greeted.set(true);
        hello.succeed(first);

        return unit();
    }

    private Unit deliver(Message message) {
        inbound.accept(this, message);

        return unit();
    }

    // ---- stderr ------------------------------------------------------------------------------------------------
    /// Thread body: logs the ssh/worker stderr line by line until it closes.
    @Contract
    private void drainStderr() {
        try (var lines = new BufferedReader(new InputStreamReader(process.stderr(), StandardCharsets.UTF_8))) {
            lines.lines().forEach(this::stderrLine);
        } catch (IOException | RuntimeException e) {
            log.debug("[{}] stderr closed: {}", host, e.getMessage());
        }
    }

    private Unit stderrLine(String line) {
        log.info("[{}] worker: {}", host, line);
        var judged = !greeted.get() && SshDiagnosis.hostKeyFailure(line)
                     ? hostKeyFailed()
                     : unit();

        stderrLines.incrementAndGet();

        return judged;
    }

    /// stderr lines judged so far, for tests.
    int stderrLines() {
        return stderrLines.get();
    }

    private Unit hostKeyFailed() {
        hostKeyFailure.succeed(unit());

        return unit();
    }
}
