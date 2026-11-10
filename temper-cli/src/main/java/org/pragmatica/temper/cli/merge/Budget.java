package org.pragmatica.temper.cli.merge;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// The merge-gate time budget: measured from the start on a clock that never steps; every
/// HTTP and git call gets `min(remaining, 35 s)`, and a call that would start with nothing left fails
/// [GateFault.General#BUDGET_SPENT] instead.
record Budget(LongSupplier nanos, long start, Duration total, Duration call) {
    static final Duration CALL = Duration.ofSeconds(35);
    /// `temper merge` and `verdict` have no overall budget; each call still gets [#CALL].
    static final Duration UNBOUNDED = Duration.ofDays(1);

    static Budget budget(LongSupplier nanos, Duration total) {
        return budget(nanos, total, CALL);
    }

    /// `call` caps each call (production [#CALL]; [MergePorts] lets a test shorten it).
    static Budget budget(LongSupplier nanos, Duration total, Duration call) {
        return new Budget(nanos, nanos.getAsLong(), total, call);
    }

    Duration remaining() {
        return total.minusNanos(nanos.getAsLong() - start);
    }

    boolean isSpent() {
        return ! remaining().isPositive();
    }

    /// The bound for the next call, or the spent budget.
    Result<Duration> bound() {
        var left = remaining();

        return left.isPositive()
               ? success(left.compareTo(call) < 0
                         ? left
                         : call)
               : GateFault.General.BUDGET_SPENT.result();
    }
}
