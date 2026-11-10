package org.pragmatica.temper.controller.flow;

import org.pragmatica.lang.Cause;


/// One log line for a failure and everything it wraps: `outer <- origin <- origin's origin`. Wrapped origins are never
/// printed into the client-facing message (they can carry store or git internals); this is where they are read.
public sealed interface CauseChain {
    static String render(Cause cause) {
        return cause.message() + cause.source()
                                      .map(origin -> " <- " + render(origin))
                                      .or("");
    }

    record unused() implements CauseChain {}
}
