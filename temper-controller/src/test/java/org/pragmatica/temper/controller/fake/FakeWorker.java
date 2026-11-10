package org.pragmatica.temper.controller.fake;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.frame.FrameCodec;
import org.pragmatica.temper.protocol.frame.FrameReader;
import org.pragmatica.temper.protocol.frame.FrameWriter;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.Message;

/// A fake worker speaking real frames over a [PipeProcess]: it records every envelope the controller sends and
/// answers requests through `responder` (a request → the replies to send, in order; the first one carries `replyTo`).
public final class FakeWorker {
    private static final JsonMapper MAPPER = TemperJson.mapper();

    private final PipeProcess process;
    private final FrameWriter writer;
    private final LinkedBlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
    public final List<Envelope> all = new CopyOnWriteArrayList<>();
    private volatile Function<Message, List<Message>> responder;
    private long ids = 1000;

    public FakeWorker(PipeProcess process, Function<Message, List<Message>> responder) {
        this.process = process;
        this.writer = FrameWriter.frameWriter(process.workerOut());
        this.responder = responder;
        Thread.ofVirtual().start(this::readLoop);
    }

    public void respondWith(Function<Message, List<Message>> next) {
        responder = next;
    }

    public synchronized void send(Message message) {
        writer.write(FrameCodec.encode(Envelope.envelope(++ids, message), MAPPER).unwrap());
    }

    private synchronized void reply(long replyTo, Message message) {
        writer.write(FrameCodec.encode(Envelope.envelope(++ids, replyTo, message), MAPPER).unwrap());
    }

    /// The next envelope from the controller of the given message type, waiting up to `seconds`.
    public <T extends Message> T expect(Class<T> type, int seconds) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            var next = received.poll(100, TimeUnit.MILLISECONDS);
            if (next != null && type.isInstance(next.msg())) {
                return type.cast(next.msg());
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " within " + seconds + " s; got " + all);
    }

    public <T extends Message> long count(Class<T> type) {
        return all.stream().filter(envelope -> type.isInstance(envelope.msg())).count();
    }

    private void readLoop() {
        var reader = FrameReader.frameReader(process.workerIn());
        while (true) {
            var next = reader.next();
            if (next.isFailure() || next.unwrap().isEmpty()) {
                return;
            }
            var envelope = FrameCodec.decode(next.unwrap().unwrap(), MAPPER).unwrap();
            all.add(envelope);
            received.add(envelope);
            var replies = responder.apply(envelope.msg());
            if (!replies.isEmpty()) {
                reply(envelope.id(), replies.getFirst());
                replies.stream().skip(1).forEach(this::send);
            }
        }
    }

    /// The worker process exits (EOF on the controller's side).
    public void close() {
        process.workerExits(0);
    }

    public static Option<Message> none() {
        return Option.none();
    }
}
