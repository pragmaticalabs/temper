package org.pragmatica.temper.worker.unit;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.Message;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A `StartUnit`'s reply, and the grant watch to start once that reply is written (m1-design §1.2): a hold unit's
/// `GrantWatcher` sends the unsolicited `UnitStarted` or `UnitStartFailed`, which must never overtake the
/// `UnitHolding` it follows. The link starts `watch` as the reply's follow-up ([GrantWatcher#watch], which starts
/// nothing for a unit already watched).
public record StartReply(Message message, Option<GrantWatcher.Watch> watch) {
    public static StartReply startReply(Message message) {
        return new StartReply(message, none());
    }

    public static StartReply startReply(Message message, GrantWatcher.Watch watch) {
        return new StartReply(message, some(watch));
    }
}
