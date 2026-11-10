package org.pragmatica.kiln.service.pipeline;

import java.time.Instant;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The pipeline baseline's state (#102), shown on the Pipeline tab and in its JSON: never `OK` for a read that did not
/// succeed. `reason` says why a state is not OK (empty when it is); `since` is when the state began, kept while the same
/// state goes on; `lastOk` survives a failure.
public record PipelineStatus(Kind kind, String reason, Instant since, Option<Instant> lastOk) {
    public enum Kind {
        /// No cycle has run yet.
        NEVER,
        /// The last cycle succeeded, and every listed PR is measured and complete.
        OK,
        /// The last cycle succeeded and spent its slice of the budget; PRs remain to be measured.
        FILLING,
        /// The shared GitHub budget refused the cycle (its reserve or kiln's hourly cap) until `reason`'s time.
        PAUSED,
        /// A read failed: GitHub's status (a 403 when the App lacks `actions:read`, a 404), a decode error, or the
        /// deadline. Measured PRs stay; the next cycle tries again.
        FAILED,
        /// kiln reads GitHub with another credential than the kiln App, which is the only one the baseline may read
        /// with (maintainer ruling): nothing is read.
        NEEDS_APP
    }

    public static PipelineStatus never(Instant at) {
        return new PipelineStatus(Kind.NEVER, "no cycle yet", at, none());
    }

    /// `next`, with the `since` of `previous` kept when the kind stays, and the last success carried.
    public PipelineStatus then(Kind kind, String reason, Instant at) {
        var ok = kind == Kind.OK || kind == Kind.FILLING;

        return new PipelineStatus(kind,
                                  reason,
                                  kind == this.kind
                                  ? since
                                  : at,
                                  ok
                                  ? some(at)
                                  : lastOk);
    }
}
