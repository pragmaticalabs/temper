package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.api.FleetAlarmsView.Alarm;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbOffQueue;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbStore.Listing;
import org.pragmatica.temper.store.api.NewFleetEvent;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// `off-queue-reactor` and `lingering-reactor` (CTO ruling 2026-11-20), pure, over the marks the previous rounds left. The limit is
/// written here as 1800 s on purpose: the test pins the figure, not the constant.
///
/// Probes: change `LINGERING_REACTOR_MIN` -> [#lingering_isSilentOneSecondBeforeThirtyMinutes_andFiresAtThirty] red; stop listing the
/// two kinds in `FleetAlarms.collected` -> [#fleetAlarms_listsTheReactorsWhileTheSnapshotHoldsThem_withOwnerAndCwd] red; fire without
/// the mark check -> [#offQueueReactor_firesOncePerLauncher_andNamesItsOwnerAndCwd] red; keep the marks of a launcher that left ->
/// [#aLauncherThatLeaves_clearsItsMarks_andAReusedPidIsAnotherLauncher] red.
class OffQueueReactorAlarmsTest {
    private static final String HOST = "h1";
    private static final long AT = 1_793_900_000L;
    private static final String CWD = "/home/aether/v3455d";

    private static final class Rounds {
        private final List<BbAlarm> rows = new ArrayList<>();
        private final List<NewFleetEvent> events = new ArrayList<>();

        List<NewFleetEvent> round(long at, BbOffQueue... reactors) {
            var listing = Listing.listing("present", List.of(), Instant.ofEpochSecond(at), 0, List.of(reactors));
            var write = TicketAlarms.alarms(HOST,
                                            Instant.ofEpochSecond(at + 20),
                                            listing,
                                            Option.<BbSnapshot> none(),
                                            List.copyOf(rows),
                                            TicketAlarms.Limits.DEFAULT);

            write.clear().forEach(gone -> rows.removeIf(gone::sameKey));
            rows.addAll(write.fire());
            events.addAll(write.events());

            return write.events();
        }
    }

    private static BbOffQueue reactor(long pid, long startS) {
        return BbOffQueue.bbOffQueue(pid, startS, "unowned", CWD);
    }

    @Test
    void offQueueReactor_firesOncePerLauncher_andNamesItsOwnerAndCwd() {
        var rounds = new Rounds();
        var first = rounds.round(AT,
                                 BbOffQueue.bbOffQueue(1465069, AT - 120, "v3436i", "/home/aether/work/co/v3436i-head"));

        assertThat(first).extracting(NewFleetEvent::kind).containsExactly(BbAlarm.OFF_QUEUE_REACTOR);
        assertThat(first.getFirst().subject()).isEqualTo(HOST + "/1465069:" + (AT - 120));
        var payload = TemperJson.mapper().readTree(first.getFirst().payload()).unwrap();

        assertThat(payload.path("owner").stringValue()).isEqualTo("v3436i");
        assertThat(payload.path("cwd").stringValue()).isEqualTo("/home/aether/work/co/v3436i-head");
        assertThat(payload.path("pid").longValue()).isEqualTo(1465069L);
        assertThat(payload.path("ageMin").intValue()).isEqualTo(2);
        assertThat(rounds.round(AT + 30,
                                BbOffQueue.bbOffQueue(1465069, AT - 120, "v3436i", "/home/aether/work/co/v3436i-head"))).as("once")
                  .isEmpty();
    }

    @Test
    void lingering_isSilentOneSecondBeforeThirtyMinutes_andFiresAtThirty() {
        var rounds = new Rounds();
        var launcher = reactor(77, AT - 1800 + 1);

        assertThat(rounds.round(AT, launcher)).extracting(NewFleetEvent::kind)
                  .as("29 min 59 s: only the off-queue alarm")
                  .containsExactly(BbAlarm.OFF_QUEUE_REACTOR);
        var at = rounds.round(AT + 1, launcher);

        assertThat(at).extracting(NewFleetEvent::kind).as("exactly 30 min").containsExactly(BbAlarm.LINGERING_REACTOR);
        assertThat(TemperJson.mapper().readTree(at.getFirst().payload()).unwrap().path("limitMin").intValue()).isEqualTo(30);
        assertThat(rounds.round(AT + 600, launcher)).as("once").isEmpty();
    }

    @Test
    void aLauncherThatLeaves_clearsItsMarks_andAReusedPidIsAnotherLauncher() {
        var rounds = new Rounds();

        rounds.round(AT, reactor(77, AT - 3600));
        assertThat(rounds.rows).extracting(BbAlarm::kind)
                  .containsExactlyInAnyOrder(BbAlarm.OFF_QUEUE_REACTOR, BbAlarm.LINGERING_REACTOR);
        assertThat(rounds.round(AT + 30)).isEmpty();
        assertThat(rounds.rows).as("the launcher left: its marks are gone").isEmpty();
        assertThat(rounds.round(AT + 60,
                                reactor(77, AT + 40))).as("same pid, new start: a new launcher")
                  .extracting(NewFleetEvent::kind)
                  .containsExactly(BbAlarm.OFF_QUEUE_REACTOR);
    }

    @Test
    void fleetAlarms_listsTheReactorsWhileTheSnapshotHoldsThem_withOwnerAndCwd() {
        var now = Instant.ofEpochSecond(AT + 20);
        var launcher = BbOffQueue.bbOffQueue(77, AT - 45 * 60, "unowned", CWD);
        var rows = List.of(BbAlarm.bbAlarm(HOST, BbAlarm.OFF_QUEUE_REACTOR, launcher.key(), "0", now),
                           BbAlarm.bbAlarm(HOST, BbAlarm.LINGERING_REACTOR, launcher.key(), "30", now),
                           BbAlarm.bbAlarm(HOST, BbAlarm.OFF_QUEUE_REACTOR, "99:1", "0", now));
        var expected = List.of(TicketRoutes.Expected.expected(HOST, "hostlink", Option.<Instant> none()));
        var holding = BbSnapshot.bbSnapshot(HOST,
                                            now,
                                            some("present"),
                                            List.of(),
                                            some(Instant.ofEpochSecond(AT)),
                                            some(0),
                                            List.of(launcher));
        var listed = FleetAlarms.view(now, expected, List.of(holding), List.of(), rows).hosts().getFirst().alarms();

        assertThat(listed).extracting(Alarm::kind).containsExactly(BbAlarm.OFF_QUEUE_REACTOR, BbAlarm.LINGERING_REACTOR);
        assertThat(listed).extracting(Alarm::pid).containsOnly(some(77L));
        assertThat(listed).extracting(Alarm::owner).containsOnly(some("unowned"));
        assertThat(listed).extracting(Alarm::cwd).containsOnly(some(CWD));
        assertThat(listed).extracting(Alarm::ageMin).containsOnly(some(45));
        assertThat(listed).extracting(Alarm::limitMin).containsExactly(none(), some(30));
        var left = BbSnapshot.bbSnapshot(HOST,
                                         now,
                                         some("present"),
                                         List.of(),
                                         some(Instant.ofEpochSecond(AT)),
                                         some(0),
                                         List.of());

        assertThat(FleetAlarms.view(now,
                                    expected,
                                    List.of(left),
                                    List.of(),
                                    rows).hosts().getFirst().alarms()).as("a mark whose launcher is gone from the latest snapshot is not current")
                  .isEmpty();
    }
}
