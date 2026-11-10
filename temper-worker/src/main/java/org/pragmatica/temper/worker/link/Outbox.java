package org.pragmatica.temper.worker.link;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.frame.FrameCodec;
import org.pragmatica.temper.protocol.frame.FrameWriter;
import org.pragmatica.temper.protocol.message.Message;


/// Everything the worker writes to the controller goes through one outbox: frames on the dedicated stdout
/// stream, with sender-unique ids. The writer serialises frames, so concurrent senders never interleave.
public interface Outbox {
    /// An unsolicited message (`Heartbeat`, `UnitExited`, `UnitReport`, an unsolicited `Failure`).
    Result<Unit> post(Message message);
    /// The reply to the request whose envelope id is `requestId`.
    Result<Unit> reply(long requestId, Message message);

    /// Sends `messages` in order, stopping at the first failure (a broken stream fails every later frame too).
    /// Atomic per outbox: two report streams for one unit (an exit watcher and a `Collect` finishing together) are
    /// sent one after the other, never interleaved chunk by chunk.
    default Result<Unit> postAll(List<Message> messages) {
        synchronized (this) {
            return messages.stream()
                           .reduce(Result.unitResult(),
                                   (posted, message) -> posted.flatMap(_ -> post(message)),
                                   (first, second) -> first.flatMap(_ -> second));
        }
    }

    static Outbox framed(FrameWriter writer, JsonMapper mapper) {
        return new FramedOutbox(writer, mapper, new AtomicLong());
    }

    /// [Outbox] over a [FrameWriter].
    record FramedOutbox(FrameWriter writer, JsonMapper mapper, AtomicLong ids) implements Outbox {
        @Override
        public Result<Unit> post(Message message) {
            return written(Envelope.envelope(ids.incrementAndGet(), message));
        }

        @Override
        public Result<Unit> reply(long requestId, Message message) {
            return written(Envelope.envelope(ids.incrementAndGet(), requestId, message));
        }

        private Result<Unit> written(Envelope envelope) {
            return FrameCodec.encode(envelope, mapper).flatMap(this::emitted);
        }

        private Result<Unit> emitted(byte[] frame) {
            return writer.write(frame);
        }
    }
}
