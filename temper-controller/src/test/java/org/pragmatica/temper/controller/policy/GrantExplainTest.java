package org.pragmatica.temper.controller.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.controller.scheduler.GrantRound;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// [GrantExplain] (D1). The property: on any snapshot, queue and starvation state, the units the explanation gives no
/// reason are exactly the units the real grant round grants, the round being the scheduler's [GrantRound] driven the
/// way the store's `grant` drives it (at most `cap` rounds, the units still queued each time, a decision naming no
/// listed unit ending them). The named cases pin which reason each policy rule produces.
class GrantExplainTest {
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final long GIB = 1024L * 1024 * 1024;
    private static final Duration BOUND = GrantPolicy.STARVATION_BOUND;
    private static final UnitKind[] KINDS = UnitKind.values();
    private static final HostName A = HostName.hostName("a").unwrap();
    private static final HostName B = HostName.hostName("b").unwrap();

    @Test
    void explain_noReasonExactlyForTheUnitsTheRoundGrants_overRandomSnapshots() {
        var random = new Random(20261018L);
        var starvedSeen = 0;
        var cappedSeen = 0;

        for (var run = 0; run < 20_000; run++) {
            var host = randomHost(random);
            var queue = randomQueue(random);
            var starvation = randomStarvation(random, queue);
            var granted = Set.copyOf(roundGrants(host, queue, starvation));

            var reasons = GrantExplain.explain(host, queue, starvation, NOW, BOUND);

            assertThat(reasons.keySet()).as("run %d: every queued unit is answered", run)
                                        .isEqualTo(queue.stream().map(QueuedUnit::unitId).collect(Collectors.toSet()));
            assertThat(withoutReason(reasons)).as("run %d: %s %s %s", run, host, queue, starvation).isEqualTo(granted);
            starvedSeen += count(reasons, GrantExplain.StarvationHold.class);
            cappedSeen += count(reasons, GrantExplain.Capped.class);
        }

        assertThat(starvedSeen).as("the generator reaches starvation holds").isGreaterThan(100);
        assertThat(cappedSeen).as("the generator reaches the cap").isGreaterThan(100);
    }

    @Test
    void explain_capped_everyUnitIsCapped_withTheCount() {
        var host = host(1, 1, 0, 20480, 0);

        var reasons = GrantExplain.explain(host, List.of(unit(1, 2048), unit(2, 2048)), Starvation.NONE, NOW, BOUND);

        assertThat(reasons).isEqualTo(Map.of(1L, List.of(new GrantExplain.Capped(1, 1, 1)),
                                             2L, List.of(new GrantExplain.Capped(1, 1, 1))));
    }

    /// One round grants up to the cap: the first two would start, the third is capped by them.
    @Test
    void explain_roundReachesTheCap_theRestIsCapped() {
        var host = host(2, 0, 0, 20480, 0);

        var reasons = GrantExplain.explain(host, List.of(unit(1, 2048), unit(2, 2048), unit(3, 2048)), Starvation.NONE, NOW, BOUND);

        assertThat(reasons).isEqualTo(Map.of(1L, List.of(),
                                             2L, List.of(),
                                             3L, List.of(new GrantExplain.Capped(2, 2, 0))));
    }

    /// The head does not fit, the next one is backfilled; the head's memory reason counts the backfilled unit too
    /// (reasons are judged at the end of the round).
    @Test
    void explain_headBlockedByMemory_backfillWouldStart() {
        var host = host(5, 0, 1, 8192, 4096);

        var reasons = GrantExplain.explain(host, List.of(unit(1, 6144), unit(2, 2048)), Starvation.NONE, NOW, BOUND);

        assertThat(reasons).isEqualTo(Map.of(1L, List.of(new Fit.Memory(6144, 6144, 8192)),
                                             2L, List.of()));
    }

    /// The head has been blocked for more than the bound: the host holds its capacity, so a unit that fits is held.
    @Test
    void explain_starvedHeadHolding_aFittingUnitIsHeld_theHeadNamesItsFit() {
        var host = host(5, 0, 1, 8192, 4096);
        var since = NOW.minus(BOUND).minusSeconds(1);

        var reasons = GrantExplain.explain(host,
                                           List.of(unit(1, 6144), unit(2, 2048)),
                                           Starvation.starvation(some(1L), some(since)),
                                           NOW,
                                           BOUND);

        assertThat(reasons).isEqualTo(Map.of(1L, List.of(new Fit.Memory(4096, 6144, 8192)),
                                             2L, List.of(new GrantExplain.StarvationHold(1, since))));
    }

    /// Blocked for exactly the bound is not starved (rule 4: MORE than the bound): the backfill still starts.
    @Test
    void explain_headBlockedExactlyTheBound_notHeld() {
        var host = host(5, 0, 1, 8192, 4096);

        var reasons = GrantExplain.explain(host,
                                           List.of(unit(1, 6144), unit(2, 2048)),
                                           Starvation.starvation(some(1L), some(NOW.minus(BOUND))),
                                           NOW,
                                           BOUND);

        assertThat(reasons.get(2L)).isEmpty();
    }

    @Test
    void explain_portGroupFull_holdUnitBlocked_plainUnitWouldStart() {
        var host = HostSnapshot.hostSnapshot(5, 0, 1, 20480, 6144, 100 * GIB, 50 * GIB, false, 1, 1);
        var portGroup = QueuedUnit.queuedUnit(1, -20, NOW, 2048, true, UnitKind.MODULE_TEST);

        var reasons = GrantExplain.explain(host, List.of(portGroup, unit(2, 2048)), Starvation.NONE, NOW, BOUND);

        assertThat(reasons).isEqualTo(Map.of(1L, List.of(new Fit.PortGroup(1, 1)),
                                             2L, List.of()));
    }

    @Test
    void explain_emptyQueue_answersNothing() {
        assertThat(GrantExplain.explain(host(5, 0, 0, 20480, 0), List.of(), Starvation.NONE, NOW, BOUND)).isEmpty();
    }

    /// Every fit rule that fails is named, not only the first.
    @Test
    void explain_severalFitFailures_allNamed() {
        var host = HostSnapshot.hostSnapshot(5, 0, 1, 8192, 6144, 50 * GIB - 1, 50 * GIB, false, 3, 2);
        var unit = QueuedUnit.queuedUnit(1, -20, NOW, 4096, true, UnitKind.MVN);

        var reasons = GrantExplain.explain(host, List.of(unit), Starvation.NONE, NOW, BOUND);

        assertThat(reasons.get(1L)).containsExactly(new Fit.Memory(6144, 4096, 8192),
                                                    new Fit.DiskFloor(50 * GIB - 1, 50 * GIB),
                                                    new Fit.PortGroup(3, 2));
    }

    /// The explanation runs the round as a tick does, so a unit the floor refuses only because of an earlier grant of
    /// the round names that grant as pending.
    @Test
    void explain_memFloor_secondUnitNamesTheRoundsGrantAsPending() {
        var host = HostSnapshot.hostSnapshot(5, 0, 0, 40960, 0, 100 * GIB, 50 * GIB, false, 0, 1, 10240, 0, 4096);

        var reasons = GrantExplain.explain(host, List.of(unit(1, 4096), unit(2, 4096)), Starvation.NONE, NOW, BOUND);

        assertThat(reasons).isEqualTo(Map.of(1L, List.of(),
                                             2L, List.of(new Fit.MemFloor(10240, 4096, 4096, 4096))));
    }

    /// The tick's skip checks, in its order; `facts` is reported as it is, whatever the link state.
    @Test
    void skipReason_downWithFacts_upWithoutFacts_busy() {
        assertThat(GrantExplain.skipReason("DOWN", false, true, false)).isEqualTo(new GrantExplain.NoLink("DOWN", true));
        assertThat(GrantExplain.skipReason("DOWN", false, false, true)).isEqualTo(new GrantExplain.NoLink("DOWN", false));
        assertThat(GrantExplain.skipReason("UP", true, false, false)).isEqualTo(new GrantExplain.NoLink("UP", false));
        assertThat(GrantExplain.skipReason("UP", true, true, true)).isEqualTo(new GrantExplain.Busy());
    }

    /// The verifier's repro: two hosts of cap 1 and two units. The first host takes unit 1, so the second is asked only
    /// about unit 2 and would start it; neither unit is blocked anywhere.
    @Test
    void explainHosts_twoHostsCapOne_eachTakesOne() {
        var a = GrantExplain.Ready.ready(A, host(1, 0, 0, 20480, 0), Starvation.NONE);
        var b = GrantExplain.Ready.ready(B, host(1, 0, 0, 20480, 0), Starvation.NONE);

        var explained = GrantExplain.explain(List.of(a, b), List.of(unit(1, 2048), unit(2, 2048)), NOW, BOUND);

        assertThat(explained).containsExactly(GrantExplain.HostReasons.hostReasons(A, Map.of(1L, List.of(),
                                                                                             2L, List.of(new GrantExplain.Capped(1, 1, 0)))),
                                              GrantExplain.HostReasons.hostReasons(B, Map.of(2L, List.of())));
    }

    /// A skipped host takes nothing: the next host still sees every unit; the skipped one answers its reason for all.
    @Test
    void explainHosts_skippedHostTakesNothing() {
        var down = GrantExplain.Skipped.skipped(A, new GrantExplain.Busy());
        var b = GrantExplain.Ready.ready(B, host(1, 0, 0, 20480, 0), Starvation.NONE);

        var explained = GrantExplain.explain(List.of(down, b), List.of(unit(1, 2048), unit(2, 2048)), NOW, BOUND);

        assertThat(explained).containsExactly(GrantExplain.HostReasons.hostReasons(A, Map.of(1L, List.of(new GrantExplain.Busy()),
                                                                                             2L, List.of(new GrantExplain.Busy()))),
                                              GrantExplain.HostReasons.hostReasons(B, Map.of(1L, List.of(),
                                                                                             2L, List.of(new GrantExplain.Capped(1, 1, 0)))));
    }

    /// The property over several hosts: hosts explained in order, each against the real [GrantRound] run over the
    /// units the earlier hosts' rounds left (the store's later transaction after an earlier one committed).
    @Test
    void explainHosts_noReasonExactlyForWhatEachHostsRoundGrantsInOrder_overRandomCases() {
        var random = new Random(20261019L);
        var laterHostGrants = 0;
        var sharedCpuSeen = 0;

        for (var run = 0; run < 10_000; run++) {
            var queue = randomQueue(random);
            var hosts = IntStream.range(0, 1 + random.nextInt(3))
                                 .mapToObj(index -> randomInput(random, index, queue))
                                 .toList();

            var explained = GrantExplain.explain(hosts, queue, NOW, BOUND);

            var left = new ArrayList<>(queue);
            assertThat(explained).hasSize(hosts.size());
            for (var index = 0; index < hosts.size(); index++) {
                var input = hosts.get(index);
                var answer = explained.get(index);
                var granted = Set.copyOf(input instanceof GrantExplain.Ready ready
                                         ? roundGrants(ready.snapshot(), readBy(ready, left), ready.starvation())
                                         : List.of());

                assertThat(answer.host()).isEqualTo(input.host());
                assertThat(answer.reasons().keySet()).as("run %d host %d: asked about exactly the units left", run, index)
                                                     .isEqualTo(left.stream().map(QueuedUnit::unitId).collect(Collectors.toSet()));
                assertThat(withoutReason(answer.reasons())).as("run %d host %d: %s %s", run, index, input, left).isEqualTo(granted);
                for (var unit : left) {
                    if (input instanceof GrantExplain.Ready ready && ready.sharedCpu() && unit.dedicatedCpu()) {
                        assertThat(answer.reasons().get(unit.unitId())).as("run %d host %d: dedicated on shared", run, index)
                                                                       .containsExactly(new GrantExplain.SharedCpu());
                        sharedCpuSeen++;
                    }
                }
                laterHostGrants += index > 0 ? granted.size() : 0;
                left.removeIf(unit -> granted.contains(unit.unitId()));
            }
        }

        assertThat(laterHostGrants).as("the generator has later hosts grant").isGreaterThan(100);
        assertThat(sharedCpuSeen).as("the generator puts dedicated units before shared hosts").isGreaterThan(100);
    }

    /// m4-design §9.2: a dedicated unit at the head of the queue is filtered out on the shared host A, which starts the
    /// plain unit behind it; the dedicated host B is then asked only about the dedicated unit, and would start it.
    @Test
    void explainHosts_sharedHostSkipsTheDedicatedHead_dedicatedHostTakesIt() {
        var a = GrantExplain.Ready.ready(A, host(3, 0, 0, 18432, 0), Starvation.NONE, true);
        var b = GrantExplain.Ready.ready(B, host(1, 0, 0, 20480, 0), Starvation.NONE);
        var dedicatedHead = QueuedUnit.queuedUnit(1, -20, NOW, 2048, false, UnitKind.MVN, true);

        var explained = GrantExplain.explain(List.of(a, b), List.of(dedicatedHead, unit(2, 2048)), NOW, BOUND);

        assertThat(explained).containsExactly(GrantExplain.HostReasons.hostReasons(A, Map.of(1L, List.of(new GrantExplain.SharedCpu()),
                                                                                             2L, List.of())),
                                              GrantExplain.HostReasons.hostReasons(B, Map.of(1L, List.of())));
    }

    /// A starvation state naming a dedicated head does not hold the shared host: the head is not among the units it reads,
    /// so the plain unit still starts there.
    @Test
    void explain_sharedHost_staleDedicatedHeadHoldsNothing() {
        var a = GrantExplain.Ready.ready(A, host(3, 0, 0, 18432, 0),
                                         Starvation.starvation(some(1L), some(NOW.minus(BOUND.multipliedBy(3)))), true);
        var dedicatedHead = QueuedUnit.queuedUnit(1, -20, NOW, 99_999, false, UnitKind.MVN, true);

        var explained = GrantExplain.explain(List.of(a), List.of(dedicatedHead, unit(2, 2048)), NOW, BOUND);

        assertThat(explained.getFirst().reasons()).isEqualTo(Map.of(1L, List.of(new GrantExplain.SharedCpu()), 2L, List.of()));
    }

    /// m4-design §10: a retired host is skipped; the next host still sees every unit.
    @Test
    void explainHosts_retiredHostTakesNothing() {
        var at = NOW.minusSeconds(1);
        var retired = GrantExplain.Skipped.skipped(A, new GrantExplain.Retired(at));
        var b = GrantExplain.Ready.ready(B, host(2, 0, 0, 20480, 0), Starvation.NONE);

        var explained = GrantExplain.explain(List.of(retired, b), List.of(unit(1, 2048)), NOW, BOUND);

        assertThat(explained).containsExactly(GrantExplain.HostReasons.hostReasons(A, Map.of(1L, List.of(new GrantExplain.Retired(at)))),
                                              GrantExplain.HostReasons.hostReasons(B, Map.of(1L, List.of())));
    }

    // ---- the reference round and the generators ---------------------------------------------------------------------

    /// The units a host's grant reads (the store's candidate filter, written out independently of the explanation's).
    private static List<QueuedUnit> readBy(GrantExplain.Ready ready, List<QueuedUnit> left) {
        return left.stream()
                   .filter(unit -> !(ready.sharedCpu() && unit.dedicatedCpu()))
                   .toList();
    }

    /// The store's `grant` loop over the real [GrantRound]: `maxGrants` is the host's cap (`Scheduler.grantOn`).
    private static List<Long> roundGrants(HostSnapshot host, List<QueuedUnit> ordered, Starvation starvation) {
        var round = GrantRound.grantRound(host, new AtomicReference<>(starvation), NOW, BOUND);
        var queued = new ArrayList<>(ordered);
        var granted = new ArrayList<Long>();

        for (var left = host.cap(); left > 0; left--) {
            var snapshot = List.copyOf(queued);
            var grant = round.apply(snapshot)
                             .grant()
                             .filter(id -> snapshot.stream().anyMatch(unit -> unit.unitId() == id));

            if (grant.isEmpty()) {
                break;
            }
            var id = grant.unwrap();

            granted.add(id);
            queued.removeIf(unit -> unit.unitId() == id);
        }
        return granted;
    }

    private static Set<Long> withoutReason(Map<Long, List<GrantExplain.Reason>> reasons) {
        return reasons.entrySet()
                      .stream()
                      .filter(entry -> entry.getValue().isEmpty())
                      .map(Map.Entry::getKey)
                      .collect(Collectors.toSet());
    }

    private static int count(Map<Long, List<GrantExplain.Reason>> reasons, Class<?> type) {
        return (int) reasons.values().stream().flatMap(List::stream).filter(type::isInstance).count();
    }

    /// Small memory steps of 2048 against budgets of 2048 multiples, and free disk around the floor, so the generator
    /// lands on every boundary (`= budget`, `= floor`, `= maxHolding`) often.
    private static HostSnapshot randomHost(Random random) {
        var budget = 2048 * (2 + random.nextInt(5));
        var floor = 50 * GIB;

        return HostSnapshot.hostSnapshot(random.nextInt(5),
                                         random.nextInt(3),
                                         random.nextInt(3),
                                         budget,
                                         2048 * random.nextInt(budget / 2048 + 2),
                                         floor + random.nextInt(3) - 1,
                                         floor,
                                         random.nextBoolean(),
                                         random.nextInt(3),
                                         1 + random.nextInt(2));
    }

    /// Mostly ready hosts, sometimes skipped; a ready host's starvation may name a unit of the queue.
    private static GrantExplain.HostInput randomInput(Random random, int index, List<QueuedUnit> queue) {
        var name = HostName.hostName("h" + index).unwrap();

        return random.nextInt(5) == 0
               ? GrantExplain.Skipped.skipped(name, new GrantExplain.Busy())
               : GrantExplain.Ready.ready(name, randomHost(random), randomStarvation(random, queue), random.nextInt(3) == 0);
    }

    private static List<QueuedUnit> randomQueue(Random random) {
        return IntStream.rangeClosed(1, random.nextInt(8))
                        .mapToObj(id -> QueuedUnit.queuedUnit(id,
                                                              -20,
                                                              NOW,
                                                              2048 * (1 + random.nextInt(4)),
                                                              random.nextInt(3) == 0,
                                                              KINDS[random.nextInt(KINDS.length)],
                                                              random.nextInt(3) == 0))
                        .toList();
    }

    /// None, a stale head, or the current head, blocked since now, exactly the bound ago, or longer.
    private static Starvation randomStarvation(Random random, List<QueuedUnit> queue) {
        var since = List.of(NOW, NOW.minus(BOUND), NOW.minus(BOUND).minusSeconds(1), NOW.minus(BOUND.multipliedBy(3)))
                        .get(random.nextInt(4));
        var head = queue.isEmpty() || random.nextInt(4) == 0
                   ? 99L
                   : queue.getFirst().unitId();

        return random.nextInt(4) == 0
               ? Starvation.NONE
               : Starvation.starvation(some(head), some(since));
    }

    private static HostSnapshot host(int cap, int foreign, int active, int budget, int activeMemory) {
        return HostSnapshot.hostSnapshot(cap, foreign, active, budget, activeMemory, 100 * GIB, 50 * GIB, false, 0, 1);
    }

    private static QueuedUnit unit(long id, int memoryMb) {
        return QueuedUnit.queuedUnit(id, -20, NOW, memoryMb, false, UnitKind.MVN);
    }
}
