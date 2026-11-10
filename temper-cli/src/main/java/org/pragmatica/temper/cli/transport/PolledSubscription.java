package org.pragmatica.temper.cli.transport;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.protocol.api.WsServerMessage;


/// [Subscription] over the controller's long-poll routes. Holds no connection: each [#next] is one request,
/// so there is nothing to close, and a dead link surfaces as that request's failure.
record PolledSubscription(Controller controller, String subscriber, boolean terminal) implements Subscription {
    static PolledSubscription polledSubscription(Controller controller, String subscriber, boolean terminal) {
        return new PolledSubscription(controller, subscriber, terminal);
    }

    @Override
    public Promise<Option<WsServerMessage>> next() {
        return controller.next(subscriber, terminal);
    }

    @Override
    public Promise<Unit> ack(long seq) {
        return controller.ack(subscriber, seq);
    }
}
