package org.pragmatica.temper.controller.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// m4-design §12.2, R-M4-4, two fake hosts (`bb`, `cb`): the host running the unit is LOST and the unit is granted to
/// the other one as attempt 2. The lost host returns with attempt 1 on its disk: its reconcile discards attempt 1 before
/// it is UP, and attempt 1's late report is acked in its own attempt and discarded (nothing written; the unit stays
/// RUNNING on the survivor in attempt 2). The survivor's report completes the unit.
///
/// Probe P9 (rule 0 removed): no `DiscardUnit` is ever sent (red). The report comes from another host, so it is
/// stored only with the link fence (`WorkerEvents.settle` supersedes a unit active `Elsewhere`) AND the store's whole
/// completion guard (host and attempt) off; either alone keeps it green. Both off: the unit is completed by the lost
/// attempt (red: "expected: RUNNING but was: PASSED"), and without that check the stored testcases hold
/// `org.x.FromLost` instead of `org.x.FromSurvivor` (red: "the lost attempt's report stored nothing").
@Tag("pg")
class LateReportRejectedIT {
    private LostHostFixture fixture;

    @AfterEach
    void stop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void lateReportOfTheLostAttempt_isAckedAndDiscarded_andTheNewHostsReportCompletesTheUnit() throws Exception {
        fixture = new LostHostFixture(true);
        var survivor = fixture.workers.get(fixture.survivor);
        fixture.lose();
        assertThat(survivor.expect(Message.StartUnit.class, 20).attempt()).as("the survivor runs attempt 2").isEqualTo(2);
        LostHostFixture.waitUntil(() -> fixture.current().state() == UnitState.RUNNING, "attempt 2 RUNNING on the survivor");

        var back = fixture.back(fixture::answer);
        back.send(LostHostFixture.hello(fixture.lost, List.of(fixture.onDisk(1, ContainerStatus.EXITED))));
        assertThat(back.expect(Message.DiscardUnit.class, 10)).isEqualTo(new Message.DiscardUnit(fixture.job, "main", 1));
        assertThat(fixture.hostsAtDiscard.get()).as("the returning host is not UP while its superseded attempt is discarded")
                                                .doesNotContain("\"name\":\"" + fixture.lost.value() + "\",\"state\":\"UP\"");

        back.send(LostHostFixture.exited(fixture.job, 1));
        back.send(LostHostFixture.report(fixture.job, 1, "org.x.FromLost"));
        assertThat(back.expect(Message.UnitReportAck.class, 10).attempt()).as("acked in its own attempt").isEqualTo(1);
        assertThat(fixture.current().state()).isEqualTo(UnitState.RUNNING);
        assertThat(fixture.current().host()).isEqualTo(some(fixture.survivor));
        assertThat(fixture.current().attempt()).isEqualTo(2);

        survivor.send(LostHostFixture.exited(fixture.job, 2));
        survivor.send(LostHostFixture.report(fixture.job, 2, "org.x.FromSurvivor"));
        assertThat(survivor.expect(Message.UnitReportAck.class, 10).attempt()).isEqualTo(2);
        assertThat(fixture.harness.waitForState(fixture.job, "PASSED")).contains("\"verdict\":\"PASSED\"");
        assertThat(fixture.harness.get("/jobs/" + fixture.job + "/testcases").body()).as("the lost attempt's report stored nothing")
                                                                                     .contains("org.x.FromSurvivor")
                                                                                     .doesNotContain("org.x.FromLost");
    }
}
