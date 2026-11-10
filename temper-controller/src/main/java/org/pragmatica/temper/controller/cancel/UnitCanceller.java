package org.pragmatica.temper.controller.cancel;

import java.util.List;

import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.model.UnitRef;


/// Sends `CancelUnit` for units the store has put in CANCELLING (cancel, supersede), on the link of the host that
/// holds each one. Fire-and-forget for the caller: the unit is CANCELLING in the store whatever happens to the send, and
/// a send that fails resets that host's session, so the reconnect's reconcile re-sends the cancel (m0-design §6.6).
@FunctionalInterface
public interface UnitCanceller {
    Unit cancel(List<UnitRef> units);
}
