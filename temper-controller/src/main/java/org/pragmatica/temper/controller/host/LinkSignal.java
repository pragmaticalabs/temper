package org.pragmatica.temper.controller.host;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.store.api.HostName;

import static org.pragmatica.lang.Unit.unit;


/// Who hears a link's UP and its leaving UP (`host-answering`, `host-unreachable`). Called once
/// per transition, after the guarded state change succeeded, on the link's own thread; it must not block.
public record LinkSignal(Fn1<Unit, HostName> up, Fn1<Unit, HostName> down) {
    /// Nobody listens.
    public static final LinkSignal NONE = new LinkSignal(_ -> unit(), _ -> unit());

    public static LinkSignal linkSignal(Fn1<Unit, HostName> up, Fn1<Unit, HostName> down) {
        return new LinkSignal(up, down);
    }
}
