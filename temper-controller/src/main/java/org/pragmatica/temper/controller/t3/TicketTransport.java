package org.pragmatica.temper.controller.t3;

import org.pragmatica.lang.Promise;


/// How one bb host's `bb-tickets` output is read: the whole stdout of one run, or a
/// [TicketError] whose message is the `last_error` the collection records. The reply is not yet checked: [TicketParse]
/// proves it complete.
public interface TicketTransport {
    Promise<String> read(String host);
}
