package org.pragmatica.temper.worker.link;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.protocol.message.Message;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// What handling one controller request produced.
public sealed interface Reply {
    /// Work that must start only after the reply frame is written (`Collect`: the `Ack` goes out before
    /// `UnitExited` and the report chunks).
    @FunctionalInterface
    interface FollowUp {
        Promise<Unit> run();
    }

    /// Answer the request with `message`, then start `followUp` if present.
    record Respond(Message message, Option<FollowUp> followUp) implements Reply {}

    /// The request expects no reply (`UnitReportAck`).
    record Silent() implements Reply {}

    static Reply respond(Message message) {
        return new Respond(message, none());
    }

    static Reply respond(Message message, FollowUp followUp) {
        return new Respond(message, some(followUp));
    }

    static Reply silent() {
        return new Silent();
    }
}
