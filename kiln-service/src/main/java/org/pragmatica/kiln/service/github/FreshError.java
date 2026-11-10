package org.pragmatica.kiln.service.github;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Why `GET /api/v1/pr-fresh` cannot answer: each message is the 503 reply's `detail`, so it
/// names the status and path, the budget, the deadline or what failed to decode, and never an origin's text (an
/// origin travels as a component, read where it is logged).
public sealed interface FreshError extends Cause {
    /// GitHub answered a status other than 200.
    record Status(Integer status, String path, String message) implements FreshError {
        public static final Fn2<Status, Integer, String> FACTORY = Causes.forTwoValues("github %d %s", Status::new);
    }

    /// The exchange itself failed (connect, TLS, the exchange timeout, a refused path).
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unreachable(String path, Cause origin, String message) implements FreshError, Cause.Wrapped {
        public static final Fn2<Unreachable, String, Cause> FACTORY = Causes.forTwoValues("github unreachable %s",
                                                                                          Unreachable::new);
    }

    /// [RateBudget#spend] refused the request.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Budget(Cause origin, String message) implements FreshError, Cause.Wrapped {
        public static final Fn1<Budget, Cause> FACTORY = Causes.forOneValue("rate-budget", Budget::new);
    }

    /// The whole call passed its deadline.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Deadline(Cause origin, String message) implements FreshError, Cause.Wrapped {
        public static final Fn1<Deadline, Cause> FACTORY = Causes.forOneValue("deadline", Deadline::new);
    }

    /// A read asked for after the call answered ([FreshReads#close]); no client sees it, it only stops the chain.
    record Closed(String path, String message) implements FreshError {
        public static final Fn1<Closed, String> FACTORY = Causes.forOneValue("closed before %s", Closed::new);
    }

    /// An answer, or `what` in it, did not decode.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Decode(String what, Cause origin, String message) implements FreshError, Cause.Wrapped {
        public static final Fn2<Decode, String, Cause> FACTORY = Causes.forTwoValues("decode %s", Decode::new);
    }
}
