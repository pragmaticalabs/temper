package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.List;

import org.pragmatica.temper.protocol.api.FleetAlarmsView;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbSnapshot;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// #103: a host past its `retireAt` is no longer collected, so the marks its last snapshot bears out are not current and
/// `fleet/alarms` lists none of them (no `host-alarms` from a frozen snapshot). Mutation: list them regardless of
/// retirement → red.
class FleetAlarmsRetiredTest {
    private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");

    private static final BbSnapshot SNAPSHOT = BbSnapshot.bbSnapshot("cloudbb-3",
                                                                     NOW.minusSeconds(3600),
                                                                     some("present"),
                                                                     List.of(),
                                                                     some(NOW.minusSeconds(3600)),
                                                                     some(45));

    private static final BbAlarm SKEW = BbAlarm.bbAlarm("cloudbb-3",
                                                        BbAlarm.CLOCK_SKEW,
                                                        BbAlarm.NO_TICKET,
                                                        "30",
                                                        NOW.minusSeconds(3600));

    private static List<String> alarmsWith(Instant retireAt) {
        var view = FleetAlarms.view(NOW,
                                    List.of(TicketRoutes.Expected.expected("cloudbb-3", "hostlink", some(retireAt))),
                                    List.of(SNAPSHOT),
                                    List.of(),
                                    List.of(SKEW));

        return view.hosts()
                   .getFirst()
                   .alarms()
                   .stream()
                   .map(FleetAlarmsView.Alarm::kind)
                   .toList();
    }

    @Test
    void aRetiredHost_listsNoCollectedMark() {
        assertThat(alarmsWith(NOW.minusSeconds(60))).isEmpty();
        assertThat(alarmsWith(NOW)).as("retired at the instant itself").isEmpty();
    }

    /// The control: the same snapshot and mark on a host not yet retired lists the mark.
    @Test
    void aHostNotYetRetired_listsItsMark() {
        assertThat(alarmsWith(NOW.plusSeconds(60))).containsExactly(BbAlarm.CLOCK_SKEW);
    }

    @Test
    void aHostWithoutRetireAt_listsItsMark() {
        var view = FleetAlarms.view(NOW,
                                    List.of(TicketRoutes.Expected.expected("cloudbb-3", "hostlink", none())),
                                    List.of(SNAPSHOT),
                                    List.of(),
                                    List.of(SKEW));

        assertThat(view.hosts().getFirst().alarms()).extracting(FleetAlarmsView.Alarm::kind)
                  .containsExactly(BbAlarm.CLOCK_SKEW);
    }

    /// #107: the status of a retired host is `RETIRED` even though its collection row aged out; before the instant the
    /// aged-out row still reads `UNREACHABLE`. Mutation: drop the retired branch of the status → red.
    @Test
    void aRetiredHost_statusIsRetired_notUnreachable() {
        assertThat(statusWith(NOW)).isEqualTo("RETIRED");
        assertThat(statusWith(NOW.minusSeconds(60))).isEqualTo("RETIRED");
        assertThat(statusWith(NOW.plusSeconds(1))).isEqualTo("UNREACHABLE");
    }

    private static String statusWith(Instant retireAt) {
        return FleetAlarms.view(NOW,
                                List.of(TicketRoutes.Expected.expected("cloudbb-3", "hostlink", some(retireAt))),
                                List.of(SNAPSHOT),
                                List.of(),
                                List.of())
                          .hosts()
                          .getFirst()
                          .status();
    }
}
