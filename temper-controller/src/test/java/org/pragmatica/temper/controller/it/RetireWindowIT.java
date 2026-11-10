package org.pragmatica.temper.controller.it;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fake.FakeWorker;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.HostName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// #107: no NEW grant goes to a host from `retireAt` minus the retire window (`[scheduler] retireWindowSeconds`, default
/// 10 min) on, so a grant committed just before `retireAt` cannot land after the link's "no active units" read. A
/// refused grant leaves the unit QUEUED, placeable on another host. The controller's clock is fixed at `T`, so the
/// boundary is exact: `retireAt = T + window + delta`. `T` leads the wall clock by [#CLOCK_LEAD]: the database fences
/// the grant's COMMIT at `retireAt - window` by its own clock (#111), and a `T` at the wall clock's present would let
/// harness start-up alone carry the `delta = 1` grant's COMMIT past that fence.
///
/// Probe P107: drop the `refusesGrantsSince` filter from `Scheduler.tick` (keep nothing) → the `delta <= 0` tests get a
/// `StartUnit` (red).
@Tag("pg")
class RetireWindowIT {
    private static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";
    private static final Duration WINDOW = ControllerConfig.Scheduling.DEFAULT_RETIRE_WINDOW;
    private static final Duration NO_GRANT_WINDOW = Duration.ofSeconds(2);
    private static final HostName CB = HostName.hostName("cb").unwrap();
    private static final Duration CLOCK_LEAD = Duration.ofMinutes(1);

    private ControllerHarness harness;
    private Instant retireAt;

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    /// The window is `retireAt - N`: one second before it, the host still takes the grant.
    @Test
    void grantAtWindowStartMinusOneSecond_isAllowed() throws Exception {
        harness = retiringAt(1, List.of());

        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));

        harness.worker.expect(Message.StartUnit.class, 20);
        assertThat(harness.waitForState(job, "RUNNING")).contains("\"state\":\"RUNNING\"");
    }

    /// At `retireAt - N` and one second after, the grant is refused: the unit stays QUEUED, not failed.
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void grantAtWindowStartAndAfter_isRefused_unitStaysQueued(long delta) throws Exception {
        harness = retiringAt(delta, List.of());

        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));
        Thread.sleep(NO_GRANT_WINDOW.toMillis());

        assertThat(harness.worker.count(Message.StartUnit.class)).as("starts at delta=" + delta).isZero();
        assertThat(harness.get("/jobs/" + job).body()).startsWith("{\"job\":" + job + ",\"state\":\"QUEUED\"");
    }

    /// The digest's explain path judges the window as the tick does: a host inside it (here exactly `retireAt - N`, so
    /// not yet retired by the instant alone) is named `retired`, with `since` the host's `retireAt`. Probe: guard
    /// `Scheduler.explainInput` by `retiredSince` instead of `refusesGrantsSince` → the digest names no `retired` (red).
    @Test
    void hostInsideTheWindow_isNamedRetiredInTheDigest() throws Exception {
        harness = retiringAt(0, List.of());

        jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));
        Thread.sleep(NO_GRANT_WINDOW.toMillis());

        assertThat(harness.get("/digest").body()).contains("\"host\":\"bb\",\"reason\":\"retired\",\"detail\":\"since=" + retireAt + "\"");
    }

    /// The refused unit is placed on the other eligible host, never failed; the retiring host gets nothing.
    @Test
    void refusedGrant_isPlacedOnAnotherEligibleHost() throws Exception {
        var other = ControllerConfig.Host.host(CB, "cb.example", "builder", Path.of("/k"), Path.of("/kh"), 5, 20480, false);
        harness = retiringAt(0, List.of(other));
        FakeWorker cb = harness.ssh.nextWorker("cb.example");
        cb.respondWith(harness::respond);
        cb.send(new Message.Hello(Handshake.PROTOCOL_VERSION, ControllerHarness.VERSION, "cb", 1000, 1000, 999, 32, ControllerHarness.facts(), List.of()));

        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));

        cb.expect(Message.StartUnit.class, 20);
        assertThat(harness.waitForState(job, "RUNNING")).contains("\"state\":\"RUNNING\"");
        assertThat(harness.worker.count(Message.StartUnit.class)).as("the retiring host takes no new grant").isZero();
    }

    private ControllerHarness retiringAt(long delta, List<ControllerConfig.Host> more) throws Exception {
        var now = Instant.now().plus(CLOCK_LEAD);
        retireAt = now.plus(WINDOW).plusSeconds(delta);
        var at = retireAt;

        return new ControllerHarness(TimeSpan.timeSpan(500).millis(), () -> now, host -> retiring(host, at), more);
    }

    private static ControllerConfig.Host retiring(ControllerConfig.Host host, Instant retireAt) {
        return ControllerConfig.Host.host(host.name(),
                                          host.address(),
                                          host.user(),
                                          host.key(),
                                          host.knownHosts(),
                                          host.cap(),
                                          host.memoryBudgetMb(),
                                          host.portIsolated(),
                                          host.sharedCpu(),
                                          Option.some(retireAt));
    }
}
