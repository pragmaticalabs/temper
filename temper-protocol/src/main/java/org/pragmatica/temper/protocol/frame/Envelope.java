package org.pragmatica.temper.protocol.frame;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.Message;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One frame's JSON: a sender-unique `id`, the `id` it answers (`replyTo`), and the message.
public record Envelope(long id, Option<Long> replyTo, Message msg) {
    public static Envelope envelope(long id, Message msg) {
        return new Envelope(id, none(), msg);
    }

    public static Envelope envelope(long id, long replyTo, Message msg) {
        return new Envelope(id, some(replyTo), msg);
    }
}
