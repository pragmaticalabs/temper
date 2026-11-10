package org.pragmatica.temper.controller.it;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;

/// m4-design §12.2: the only host, `bb`, is LOST (the unit is requeued), returns, and gets the unit again as attempt 2
/// while its disk still holds attempt 1 (its container running). The reconcile discards attempt 1 before the host is
/// UP; the grant then starts attempt 2 (`StartUnit(2)`, answered `UnitHolding`, so it stays GRANTED); a late
/// `UnitStarted` of attempt 1 changes nothing (dropped at the link, which knows attempt 2; the store's guard behind it
/// is `StaleAttemptStoreIT`'s). While attempt 2 RUNS on the same host, a late `UnitExited` and report of attempt 1 are
/// acked in their own attempt and store nothing: the unit stays RUNNING in attempt 2, and attempt 2's report alone
/// completes it (R-M4-3, at most one stored result per unit). That report passes two fences, the link's
/// (`WorkerEvents.settle` supersedes another attempt than the one active here) and the store's (the completion guard
/// `current(unit, host, attempt)`); on one host only the attempt tells them apart, so each fence alone keeps it green.
///
/// Probes: P9 (rule 0 removed) → no `DiscardUnit` (red); P3 (`GRANT` does not increment) → `StartUnit` names attempt 1
/// (red); X2+P1 (both report fences off) → attempt 1's report completes the unit (red: "attempt 1's late report
/// changes nothing").
@Tag("pg")
class SameHostRegrantIT {
    private LostHostFixture fixture;

    @AfterEach
    void stop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void returningHost_discardsAttemptOneBeforeUp_startsAttemptTwo_andIgnoresLateAttemptOneFrames() throws Exception {
        fixture = new LostHostFixture(false);
        fixture.lose();

        var back = fixture.back(request -> switch (request) {
            case Message.StartUnit start -> List.of(new Message.UnitHolding(start.job(), start.unit(), start.attempt(),
                                                                            LostHostFixture.MARKER, ControllerHarness.facts()));
            default -> fixture.answer(request);
        });
        back.send(LostHostFixture.hello(fixture.lost, List.of(fixture.onDisk(1, ContainerStatus.RUNNING))));
        assertThat(back.expect(Message.DiscardUnit.class, 10)).isEqualTo(new Message.DiscardUnit(fixture.job, "main", 1));
        assertThat(fixture.hostsAtDiscard.get()).as("no grant before the superseded attempt is gone")
                                                .doesNotContain("\"state\":\"UP\"");
        assertThat(back.count(Message.CancelUnit.class)).as("discarded, not killed as an orphan").isZero();

        assertThat(back.expect(Message.StartUnit.class, 20).attempt()).isEqualTo(2);
        LostHostFixture.waitUntil(() -> fixture.current().attempt() == 2 && fixture.current().state() == UnitState.GRANTED,
                                  "attempt 2 GRANTED");

        back.send(new Message.UnitStarted(fixture.job, "main", 1, "c-late", "sha256:img", LostHostFixture.MARKER, Instant.now(),
                                          ControllerHarness.facts(), none()));
        Thread.sleep(Duration.ofSeconds(2).toMillis());
        assertThat(fixture.current().state()).as("attempt 1's late start changes nothing").isEqualTo(UnitState.GRANTED);

        back.send(new Message.UnitStarted(fixture.job, "main", 2, "c-two", "sha256:img", LostHostFixture.MARKER, Instant.now(),
                                          ControllerHarness.facts(), none()));
        LostHostFixture.waitUntil(() -> fixture.current().attempt() == 2 && fixture.current().state() == UnitState.RUNNING,
                                  "attempt 2 RUNNING");
        assertThat(fixture.harness.get("/jobs/" + fixture.job).body()).contains("\"containerId\":\"c-two\"");

        back.send(LostHostFixture.exited(fixture.job, 1));
        back.send(LostHostFixture.report(fixture.job, 1, "org.x.FromAttemptOne"));
        assertThat(back.expect(Message.UnitReportAck.class, 10).attempt()).as("acked in its own attempt").isEqualTo(1);
        assertThat(fixture.current().state()).as("attempt 1's late report changes nothing").isEqualTo(UnitState.RUNNING);
        assertThat(fixture.current().attempt()).isEqualTo(2);

        back.send(LostHostFixture.exited(fixture.job, 2));
        back.send(LostHostFixture.report(fixture.job, 2, "org.x.FooTest"));
        assertThat(back.expect(Message.UnitReportAck.class, 10).attempt()).isEqualTo(2);
        assertThat(fixture.harness.waitForState(fixture.job, "PASSED")).contains("\"verdict\":\"PASSED\"");
        assertThat(fixture.harness.get("/jobs/" + fixture.job + "/testcases").body()).as("attempt 2's result alone is stored")
                                                                                     .contains("org.x.FooTest")
                                                                                     .doesNotContain("org.x.FromAttemptOne");
    }
}
