package org.pragmatica.temper.store.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.KillCause;


/// A `units` row. `steps` are the planner's argv arrays (`units.args`); `reason` is the detail of the unit's
/// terminal verdict (`disk`, `lost`, `rc`, ...); a VOID / ERROR reason is kept as reported, detail included
/// (`manifest: effective-pom rc=1`). `tcLeftovers` is the Testcontainers reap of its `unit_facts` phase `end`,
/// absent until the unit ended with end facts (or once retention deleted them). `portGroup` is the unit's hold
/// (m1-design §1.3), `portGroupSince` when it entered that state (the wait's start, then the grant), `ticket` the
/// bb-* ticket once granted. `attempt` is the unit's current attempt, its grant count (m4-design §4.1; 0 before its
/// first grant). `sharedCpu` is the CPU class of the current attempt's host, snapshotted at its grant (`units.shared_cpu`,
/// m4-design §9.4; none before a grant); `sideEffects` is `units.side_effects` (§5); `lostAttempts` the unit's
/// `unit_attempts` rows, oldest first (§2.3), read in the row's own statement.
public record UnitRow(long id,
                      UnitName name,
                      UnitKind kind,
                      UnitState state,
                      Option<String> reason,
                      Option<HostName> host,
                      List<List<String>> steps,
                      boolean requestsTests,
                      boolean needsPortGroup,
                      int memoryMb,
                      Duration timeout,
                      Option<Instant> leaseUntil,
                      Option<Instant> grantedAt,
                      Option<Instant> startedAt,
                      Option<Instant> endedAt,
                      Option<Integer> rc,
                      Option<KillCause> killedBy,
                      Option<Long> markerEpochNs,
                      Option<String> imageId,
                      Option<String> containerId,
                      Option<Long> testsAttr,
                      boolean rerun,
                      Option<TcLeftovers> tcLeftovers,
                      Option<PortGroup> portGroup,
                      Option<Instant> portGroupSince,
                      Option<String> ticket,
                      int attempt,
                      Option<Boolean> sharedCpu,
                      SideEffects sideEffects,
                      List<LostAttempt> lostAttempts) {
    public UnitRow {
        steps = steps.stream().map(List::copyOf).toList();
        lostAttempts = List.copyOf(lostAttempts);
    }

    /// A row without its M4 facts: no CPU class, no lost attempts, and `external` side effects, V11's fail-safe default
    /// (such a unit is never requeued after a host loss).
    public static UnitRow unitRow(long id,
                                  UnitName name,
                                  UnitKind kind,
                                  UnitState state,
                                  Option<String> reason,
                                  Option<HostName> host,
                                  List<List<String>> steps,
                                  boolean requestsTests,
                                  boolean needsPortGroup,
                                  int memoryMb,
                                  Duration timeout,
                                  Option<Instant> leaseUntil,
                                  Option<Instant> grantedAt,
                                  Option<Instant> startedAt,
                                  Option<Instant> endedAt,
                                  Option<Integer> rc,
                                  Option<KillCause> killedBy,
                                  Option<Long> markerEpochNs,
                                  Option<String> imageId,
                                  Option<String> containerId,
                                  Option<Long> testsAttr,
                                  boolean rerun,
                                  Option<TcLeftovers> tcLeftovers,
                                  Option<PortGroup> portGroup,
                                  Option<Instant> portGroupSince,
                                  Option<String> ticket,
                                  int attempt) {
        return new UnitRow(id,
                           name,
                           kind,
                           state,
                           reason,
                           host,
                           steps,
                           requestsTests,
                           needsPortGroup,
                           memoryMb,
                           timeout,
                           leaseUntil,
                           grantedAt,
                           startedAt,
                           endedAt,
                           rc,
                           killedBy,
                           markerEpochNs,
                           imageId,
                           containerId,
                           testsAttr,
                           rerun,
                           tcLeftovers,
                           portGroup,
                           portGroupSince,
                           ticket,
                           attempt,
                           Option.none(),
                           SideEffects.EXTERNAL,
                           List.of());
    }

    public static UnitRow unitRow(long id,
                                  UnitName name,
                                  UnitKind kind,
                                  UnitState state,
                                  Option<String> reason,
                                  Option<HostName> host,
                                  List<List<String>> steps,
                                  boolean requestsTests,
                                  boolean needsPortGroup,
                                  int memoryMb,
                                  Duration timeout,
                                  Option<Instant> leaseUntil,
                                  Option<Instant> grantedAt,
                                  Option<Instant> startedAt,
                                  Option<Instant> endedAt,
                                  Option<Integer> rc,
                                  Option<KillCause> killedBy,
                                  Option<Long> markerEpochNs,
                                  Option<String> imageId,
                                  Option<String> containerId,
                                  Option<Long> testsAttr,
                                  boolean rerun,
                                  Option<TcLeftovers> tcLeftovers,
                                  Option<PortGroup> portGroup,
                                  Option<Instant> portGroupSince,
                                  Option<String> ticket,
                                  int attempt,
                                  Option<Boolean> sharedCpu,
                                  SideEffects sideEffects,
                                  List<LostAttempt> lostAttempts) {
        return new UnitRow(id,
                           name,
                           kind,
                           state,
                           reason,
                           host,
                           steps,
                           requestsTests,
                           needsPortGroup,
                           memoryMb,
                           timeout,
                           leaseUntil,
                           grantedAt,
                           startedAt,
                           endedAt,
                           rc,
                           killedBy,
                           markerEpochNs,
                           imageId,
                           containerId,
                           testsAttr,
                           rerun,
                           tcLeftovers,
                           portGroup,
                           portGroupSince,
                           ticket,
                           attempt,
                           sharedCpu,
                           sideEffects,
                           lostAttempts);
    }
}
