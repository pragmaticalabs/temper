package org.pragmatica.temper.controller.host;

import java.time.Instant;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A host link's lifecycle (m0-design §6.4): `DOWN → CONNECTING → HANDSHAKING → UP → DOWN`, plus `Stopped` at
/// shutdown. Changed only through guarded transitions in [HostLink]. `attempt` counts consecutive reconnects for
/// the backoff. `wire()` is the `hosts.state` value (the table knows DOWN, CONNECTING and UP; HANDSHAKING is shown as
/// CONNECTING).
public sealed interface LinkState {
    String wire();
    /// The session, once ssh is running.
    Option<Connection> connection();

    record Down(int attempt) implements LinkState {
        @Override
        public String wire() {
            return "DOWN";
        }

        @Override
        public Option<Connection> connection() {
            return none();
        }
    }

    /// ssh is being started (no connection yet) or waits for the worker's `Hello`.
    record Connecting(int attempt, Option<Connection> connection) implements LinkState {
        @Override
        public String wire() {
            return "CONNECTING";
        }
    }

    /// `Hello` accepted, `Welcome` sent, reconcile running; grants are not enabled yet.
    record Handshaking(int attempt, Connection session) implements LinkState {
        @Override
        public String wire() {
            return "CONNECTING";
        }

        @Override
        public Option<Connection> connection() {
            return some(session);
        }
    }

    /// Reconciled; the scheduler grants on this host. `attempt` is the connect attempt that got here: it still counts
    /// for the backoff if the link drops before it was UP for `timing.stableUp`.
    record Up(Connection session, Instant since, int attempt) implements LinkState {
        @Override
        public String wire() {
            return "UP";
        }

        @Override
        public Option<Connection> connection() {
            return some(session);
        }
    }

    record Stopped() implements LinkState {
        @Override
        public String wire() {
            return "DOWN";
        }

        @Override
        public Option<Connection> connection() {
            return none();
        }
    }
}
