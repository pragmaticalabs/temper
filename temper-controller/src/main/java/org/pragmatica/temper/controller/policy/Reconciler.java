package org.pragmatica.temper.controller.policy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.hold.ContainerState;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;

import static org.pragmatica.lang.Option.option;


/// Reconcile after a handshake (spec §6.4, m0-design §3.10, m1-design §1.5, m4-design §4.5). Pure.
///
/// Rule 0, before all others: a disk unit whose attempt the store no longer counts on this host (a lower attempt than
/// the store's current one, wherever that is; or the same attempt after the store requeued it) →
/// [ReconcileAction.Discard] of exactly that attempt. Discards come first in the result, and a discarded unit is not on
/// disk for the rules below: a current attempt that never reached this host's disk is decided as never ran (rule 6).
/// A disk attempt above the store's (a grant the store lost, e.g. a restored database) is discarded too: no store row
/// owns or times it.
///
/// Then, for every unit the store has active on the host, the first rule that applies:
///
/// 1. an rc on disk, or the container exited without an rc → [ReconcileAction.Collect] (the worker reads rc from
///    `docker inspect`; bb-hold releases the port group by itself);
/// 2. the container runs → [ReconcileAction.Reattach], plus [ReconcileAction.Cancel] when the store says
///    CANCELLING (the worker's `HoldMonitor` handles a dead holder), preceded by [ReconcileAction.StartUnknown] when
///    the store has the unit GRANTED with no recorded start (it runs, so it must not show QUEUED; R-13 amended);
/// 3. no container, the bb-hold holder `WAITING` or `HELD` → Reattach (+ Cancel when CANCELLING): the hold is
///    alive, the worker re-arms its `GrantWatcher`, and the unit is neither re-run nor declared lost;
/// 4. no container, the holder `DEAD` before a grant (no ticket): the container never started → as rule 6's
///    "never ran" (Requeue when GRANTED, CompleteCancelled when CANCELLING). The worker inspects the container a
///    second time before it reports this, so a `docker run` child that outlived bb-hold is not missed;
/// 5. no container, the holder `DEAD` after a grant → [ReconcileAction.MarkLost], a visible loss;
/// 6. the M0 rules: a marker with neither container nor rc → MarkLost (`ERROR(lost)`); no marker: the unit never
///    ran. GRANTED → [ReconcileAction.Requeue] (Q-11; safe because the marker is written before `docker run` and
///    before bb-hold is spawned, and the worker host lock rules out a late start); CANCELLING →
///    [ReconcileAction.CompleteCancelled]. A unit whose start the store recorded cannot lack its marker unless its
///    directory is gone → MarkLost.
///
/// Every disk unit left after rule 0 with a running container that the store does not have active on this host
/// (unknown, or terminal in the same attempt) → [ReconcileAction.KillOrphan].
///
/// A disk unit whose container is labelled for another controller instance (`own` is this one's) is not this
/// controller's: it is ignored, never killed as an orphan (M1 WP7 verification, F2). An unlabelled one is this
/// instance's.
public sealed interface Reconciler {
    /// `stored` is the store's current attempt of every scanned unit it knows (`UnitStore.attempts`).
    static List<ReconcileAction> decide(InstanceId own,
                                        List<DbUnit> db,
                                        List<DiskUnit> scanned,
                                        List<StoreAttempt> stored) {
        var current = stored.stream()
                            .collect(Collectors.toUnmodifiableMap(StoreAttempt::ref,
                                                                  Function.identity(),
                                                                  (first, _) -> first));
        var disk = scanned.stream().filter(unit -> !unit.belongsToAnother(own)).toList();
        var live = disk.stream().filter(unit -> !superseded(unit, current)).toList();
        var byRef = live.stream()
                        .collect(Collectors.toUnmodifiableMap(DiskUnit::ref,
                                                              Function.identity(),
                                                              (first, _) -> first));
        var active = db.stream().map(DbUnit::ref).collect(Collectors.toUnmodifiableSet());

        return Stream.of(discards(disk, current),
                         db.stream().flatMap(unit -> actions(unit,
                                                             option(byRef.get(unit.ref())))),
                         orphans(live, active))
                     .flatMap(Function.identity())
                     .toList();
    }

    private static Stream<ReconcileAction> discards(List<DiskUnit> disk, Map<UnitRef, StoreAttempt> current) {
        return disk.stream()
                   .filter(unit -> superseded(unit, current))
                   .map(unit -> new ReconcileAction.Discard(unit.ref(),
                                                            unit.attempt()));
    }

    private static boolean superseded(DiskUnit unit, Map<UnitRef, StoreAttempt> current) {
        return option(current.get(unit.ref())).filter(stored -> stored.supersedes(unit.attempt()))
                     .isPresent();
    }

    /// The unit's current attempt is not on this host after all: the worker answered a `Collect` or `Reattach` of it
    /// `stale-attempt` (it holds another attempt). Decided as rule 6's never-ran unit, as if its directory were absent.
    static List<ReconcileAction> notOnDisk(DbUnit unit) {
        return neverRan(unit).toList();
    }

    private static Stream<ReconcileAction> actions(DbUnit unit, Option<DiskUnit> onDisk) {
        return onDisk.map(found -> onDisk(unit, found))
                     .or(() -> neverRan(unit));
    }

    private static Stream<ReconcileAction> onDisk(DbUnit unit, DiskUnit found) {
        return found.rc()
                    .isPresent() || found.container() == ContainerState.EXITED
               ? Stream.of(new ReconcileAction.Collect(unit.ref()))
               : notExited(unit, found);
    }

    private static Stream<ReconcileAction> notExited(DbUnit unit, DiskUnit found) {
        return found.container() == ContainerState.RUNNING
               ? Stream.concat(startUnknown(unit), running(unit))
               : withoutContainer(unit, found);
    }

    private static Stream<ReconcileAction> startUnknown(DbUnit unit) {
        return unit.state() == UnitState.GRANTED && !unit.started()
               ? Stream.of(new ReconcileAction.StartUnknown(unit.ref()))
               : Stream.empty();
    }

    /// Rules 3 to 5 for a unit started through bb-hold; rule 6 (the M0 rules) for a direct unit.
    private static Stream<ReconcileAction> withoutContainer(DbUnit unit, DiskUnit found) {
        return found.holder()
                    .map(holder -> holding(unit, found, holder))
                    .or(() -> absent(unit, found));
    }

    private static Stream<ReconcileAction> holding(DbUnit unit, DiskUnit found, HolderStatus holder) {
        return switch (holder) {
            case WAITING, HELD -> running(unit);
            case DEAD -> deadHolder(unit, found);
        };
    }

    private static Stream<ReconcileAction> deadHolder(DbUnit unit, DiskUnit found) {
        return found.granted()
               ? Stream.of(new ReconcileAction.MarkLost(unit.ref()))
               : neverRan(unit);
    }

    private static Stream<ReconcileAction> running(DbUnit unit) {
        var reattach = new ReconcileAction.Reattach(unit.ref(), unit.timeout());

        return unit.state() == UnitState.CANCELLING
               ? Stream.of(reattach,
                           new ReconcileAction.Cancel(unit.ref()))
               : Stream.of(reattach);
    }

    private static Stream<ReconcileAction> absent(DbUnit unit, DiskUnit found) {
        return found.hasMarker()
               ? Stream.of(new ReconcileAction.MarkLost(unit.ref()))
               : neverRan(unit);
    }

    private static Stream<ReconcileAction> neverRan(DbUnit unit) {
        return unit.started()
               ? Stream.of(new ReconcileAction.MarkLost(unit.ref()))
               : unstarted(unit);
    }

    private static Stream<ReconcileAction> unstarted(DbUnit unit) {
        return unit.state() == UnitState.CANCELLING
               ? Stream.of(new ReconcileAction.CompleteCancelled(unit.ref()))
               : Stream.of(new ReconcileAction.Requeue(unit.ref()));
    }

    private static Stream<ReconcileAction> orphans(List<DiskUnit> disk, Set<UnitRef> active) {
        return disk.stream()
                   .filter(unit -> unit.container() == ContainerState.RUNNING)
                   .filter(unit -> !active.contains(unit.ref()))
                   .map(unit -> new ReconcileAction.KillOrphan(unit.ref()));
    }

    record unused() implements Reconciler {}
}
