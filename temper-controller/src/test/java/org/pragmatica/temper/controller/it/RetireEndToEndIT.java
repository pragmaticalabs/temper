package org.pragmatica.temper.controller.it;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.t3.TicketError;
import org.pragmatica.temper.controller.t3.TicketTransport;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #107 (#103) end to end: one running controller, one host driven past its `retireAt` at RUNTIME (the controller's
/// clock moves; no restart). From that instant: the link is stopped and not redialed, the ticket poll skips the host,
/// `fleet/alarms` lists no mark for it and calls it `RETIRED`, and `GET /hosts` carries what the CLI's `worker-lost`
/// condition reads as clear (`retired`, `active=0`, link not UP), which is what closes an open `worker-lost` issue
/// (`AlarmCheckCommandTest#githubSink_openWorkerLost_closes_onceTheHostIsRetired` runs that step from this shape).
///
/// Probes: drop the link's retired check (`HostLink.tick`) → the link stays UP (red); drop the collector's retired guard
/// or the `retireAt` argument of `Controller.ticketTargets` → a second read (red); list the marks of a retired host →
/// the mark remains (red).
@Tag("pg")
class RetireEndToEndIT {
    private static final String HOST = "\"name\":\"bb\"";

    private final AtomicReference<Duration> shift = new AtomicReference<>(Duration.ZERO);
    private ControllerHarness harness;

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void hostDrivenPastRetireAtAtRuntime_linkStops_pollSkips_alarmsClear_workerLostClears() throws Exception {
        var retireAt = Instant.now().plus(Duration.ofHours(1));
        var reads = new AtomicInteger();

        harness = new ControllerHarness(TimeSpan.timeSpan(500).millis(),
                                        () -> Instant.now().plus(shift.get()),
                                        host -> retiring(host, retireAt),
                                        _ -> Option.some(counting(reads)));
        waitUntil(() -> reads.get() >= 1, "the first ticket round");
        waitUntil(() -> hostsBody().contains(HOST + ",\"state\":\"UP\""), "the link UP");
        seedSkewMark();
        var before = harness.get("/hosts").body();

        assertThat(before).as("UP and not retired before the instant")
                  .contains(HOST + ",\"state\":\"UP\"")
                  .contains("\"retired\":false");
        assertThat(harness.get("/fleet/alarms").body()).as("the host's mark is listed before").contains("clock-skew");
        var spawns = harness.ssh.spawns.size();

        shift.set(Duration.ofHours(2));
        waitUntil(() -> hostsBody().contains(HOST + ",\"state\":\"DOWN\""),
                  "the link stopped");
        var after = harness.get("/hosts").body();

        assertThat(after).as("what worker-lost reads as clear").contains("\"retired\":true").contains("\"active\":0");
        Thread.sleep(2000);
        assertThat(harness.ssh.spawns.size()).as("a stopped link is not redialed").isEqualTo(spawns);
        var alarms = harness.get("/fleet/alarms").body();

        assertThat(alarms).as("alarms cleared, host RETIRED")
                  .contains("\"status\":\"RETIRED\"")
                  .doesNotContain("clock-skew");
        Thread.sleep(32_000);
        assertThat(reads).as("no ticket read after the instant, one full 30 s period later").hasValue(1);
        assertThat(harness.get("/fleet/tickets").body()).contains("\"status\":\"RETIRED\"");
    }

    private String hostsBody() {
        try {
            return harness.get("/hosts").body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void seedSkewMark() {
        harness.store.bb()
                     .collected("bb",
                                Instant.now(),
                                BbStore.Listing.listing("present",
                                                        List.of(),
                                                        Instant.now(),
                                                        3),
                                (_, _) -> BbStore.RoundWrite.roundWrite(List.of(),
                                                                        List.of(BbAlarm.bbAlarm("bb",
                                                                                                BbAlarm.CLOCK_SKEW,
                                                                                                BbAlarm.NO_TICKET,
                                                                                                "30",
                                                                                                Instant.now())),
                                                                        List.of()))
                     .await(Pg.WAIT)
                     .unwrap();
    }

    private static TicketTransport counting(AtomicInteger reads) {
        return _ -> {
            reads.incrementAndGet();

            return TicketError.SshExit.sshExit(255, "Connection refused").promise();
        };
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out: " + what);
            }

            Thread.sleep(100);
        }
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
