package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.api.FleetAlarmsView.Alarm;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbTicket;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// `fleet/alarms` for the port-group alarms (issue #10): `portgroup-held` and `portgroup-long` are both listed while the
/// ticket runs with `group=1`; the held alarm's `ageMin` is the stall (`hostAt − progress`), the long one's the age
/// (`hostAt − started`). Mutation: list the held alarm's age from `started` -> 240 instead of 45, red.
class FleetAlarmsPortGroupTest {
    private static final long AT = 1_793_900_000L;
    private static final Instant NOW = Instant.ofEpochSecond(AT + 20);

    private static BbTicket running(String... kv) {
        var fields = new LinkedHashMap<String, String>();

        for (var i = 0; i < kv.length; i += 2) {
            fields.put(kv[i], kv[i + 1]);
        }

        return BbTicket.bbTicket(1, "r1", "running", some("alive"), fields, List.of());
    }

    private static List<Alarm> alarms(BbTicket ticket, String... thresholds) {
        var rows = new java.util.ArrayList<BbAlarm>();

        for (var i = 0; i < thresholds.length; i += 2) {
            rows.add(BbAlarm.bbAlarm("h1", thresholds[i], "r1", thresholds[i + 1], NOW));
        }

        var snapshot = BbSnapshot.bbSnapshot("h1",
                                             NOW,
                                             some("present"),
                                             List.of(ticket),
                                             some(Instant.ofEpochSecond(AT)),
                                             some(0));

        return FleetAlarms.view(NOW,
                                List.of(TicketRoutes.Expected.expected("h1",
                                                                       "hostlink",
                                                                       Option.<Instant> none())),
                                List.of(snapshot),
                                List.of(),
                                rows)
                          .hosts()
                          .getFirst()
                          .alarms();
    }

    @Test
    void heldAndLong_areListed_heldWithItsStall_longWithItsAge() {
        var ticket = running("started",
                             Long.toString(AT - 400 * 60),
                             "progressAt",
                             Long.toString(AT - 45 * 60),
                             "group",
                             "1");
        var listed = alarms(ticket, BbAlarm.PORTGROUP_HELD, "30", BbAlarm.PORTGROUP_LONG, "360");

        assertThat(listed).extracting(Alarm::kind).containsExactly(BbAlarm.PORTGROUP_HELD, BbAlarm.PORTGROUP_LONG);
        assertThat(listed.get(0).ageMin()).isEqualTo(some(45));
        assertThat(listed.get(0).limitMin()).isEqualTo(some(30));
        assertThat(listed.get(1).ageMin()).isEqualTo(some(400));
        assertThat(listed.get(1).limitMin()).isEqualTo(some(360));
    }

    @Test
    void heldWithoutProgressAt_ageIsTheStallFromStarted() {
        var listed = alarms(running("started", Long.toString(AT - 40 * 60), "group", "1"), BbAlarm.PORTGROUP_HELD, "30");

        assertThat(listed).extracting(Alarm::ageMin).containsExactly(some(40));
    }

    @Test
    void aTicketThatIsNotAGroupHolder_listsNeither() {
        var listed = alarms(running("started", Long.toString(AT - 400 * 60), "group", "0"),
                            BbAlarm.PORTGROUP_HELD,
                            "30",
                            BbAlarm.PORTGROUP_LONG,
                            "360");

        assertThat(listed).isEmpty();
    }
}
