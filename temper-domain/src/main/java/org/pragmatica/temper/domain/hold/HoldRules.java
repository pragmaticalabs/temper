package org.pragmatica.temper.domain.hold;

/// Liveness of a held port group while its container runs (m1-design §1.4, spec §5.3). Pure.
///
/// **Precondition on the caller** (the worker's `HoldMonitor`; this pure function cannot see read order): two
/// holder observations at least 2 s apart, each read BEFORE the container state of its round, and the later
/// round's container state. The read order is the caller's to keep and WP4's `HoldMonitorTest` to pin. Why
/// this shape:
/// - **Holder before container.** bb-hold exits *because* the container exited. Reading the container first
///   and the holder second can see "RUNNING, then DEAD" across that clean exit and void a clean unit;
///   holder-then-container cannot, because a holder read as DEAD before a container read as RUNNING means the
///   holder died while the container still ran.
/// - **Two observations.** The second absorbs a pid-file read racing its write.
public sealed interface HoldRules {
    /// `VOID_PORT_GROUP_LOST` only when both holder observations are `DEAD` and the container is `RUNNING`;
    /// `NONE` otherwise (an exited or absent container is collected by the normal exit path).
    static HoldAction decide(HolderStatus earlier, HolderStatus later, ContainerState container) {
        return earlier == HolderStatus.DEAD && later == HolderStatus.DEAD && container == ContainerState.RUNNING
               ? HoldAction.VOID_PORT_GROUP_LOST
               : HoldAction.NONE;
    }

    record unused() implements HoldRules {}
}
