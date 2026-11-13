package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// A `task_report` row: what is known of a task's lease holder besides the lease itself.
///
/// - `lastHbAt`: the last accepted heartbeat, or the claim when none came after it.
/// - `reported`: the tree the agent SELF-REPORTED at a heartbeat. The controller cannot observe the tree, so this is
///   only as true as the agent's last report, as of `Reported.at`; `Reported.epoch` is the lease that sent it.
/// - `observed`: context and turns MEASURED by the parent and posted through the full-scope observe route; the agent's
///   lease token cannot write it.
public record TaskReport(Option<Instant> lastHbAt, Option<Reported> reported, Option<Observed> observed) {
    public static final TaskReport NONE = new TaskReport(Option.none(), Option.none(), Option.none());

    public record Reported(String head, int dirty, int unpushed, long epoch, Instant at) {
        /// The tree was not clean when reported.
        public boolean isDirty() {
            return dirty > 0 || unpushed > 0;
        }
    }

    public record Observed(int ctxK, int turns, Instant at) {}

    /// The tree an agent reports at a heartbeat.
    public record Tree(String head, int dirty, int unpushed) {}
}
