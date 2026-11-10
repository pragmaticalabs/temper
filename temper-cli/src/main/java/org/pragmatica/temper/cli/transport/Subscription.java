package org.pragmatica.temper.cli.transport;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.protocol.api.WsServerMessage;


/// One named subscriber's event delivery (m0-design §6.5), with a window of one: the controller answers the
/// message after the subscriber's cursor and moves the cursor only on [#ack], so asking again without an ack
/// answers the same message.
public interface Subscription {
    /// The next message, or `none()` when nothing arrived within the controller's poll window; fails with a
    /// `CliError` when the controller cannot be asked.
    Promise<Option<WsServerMessage>> next();
    /// Acknowledges `seq`, which advances the subscriber's cursor on the controller.
    Promise<Unit> ack(long seq);

    /// Long-poll against the controller's `subscribers/{name}/next` and `subscribers/{name}/ack` routes (the
    /// m0-design §6.5 fallback that replaced `WS /events`: rc4's http-server WebSocket never reaches its handler).
    /// The bearer token travels in the `Authorization` header of each request, as on every other route.
    static Subscription polled(Controller controller, String subscriber, boolean terminal) {
        return PolledSubscription.polledSubscription(controller, subscriber, terminal);
    }
}
