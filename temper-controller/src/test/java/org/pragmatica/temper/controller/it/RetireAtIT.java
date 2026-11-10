package org.pragmatica.temper.controller.it;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.protocol.message.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.it.ControllerHarness.exited;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;
import static org.pragmatica.temper.controller.it.ControllerHarness.report;


/// m4-design §10 (R-M4-10, probe P12): from a host's `retireAt` on, the scheduler grants nothing there; a unit already
/// running finishes and is collected over the same link; the digest names the skip `retired`. The controller reads a
/// clock that runs with real time plus a shift, so the instant passes by moving the shift, with no wait.
///
/// Probe P12: drop the `retiredSince` filter from `Scheduler.tick` → the second job is granted after the instant (red).
@Tag("pg")
class RetireAtIT {
    private static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";
    /// Ten scheduler intervals (200 ms) with no grant after the instant.
    private static final Duration NO_GRANT_WINDOW = Duration.ofSeconds(2);

    private final AtomicReference<Duration> shift = new AtomicReference<>(Duration.ZERO);
    private ControllerHarness harness;

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void retiredHost_grantsNothingAfterTheInstant_runningUnitFinishes_digestSaysRetired() throws Exception {
        var retireAt = Instant.now().plus(Duration.ofHours(1));
        harness = new ControllerHarness(TimeSpan.timeSpan(500).millis(),
                                        () -> Instant.now().plus(shift.get()),
                                        host -> retiring(host, retireAt));

        var running = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));
        harness.worker.expect(Message.StartUnit.class, 20);
        harness.waitForState(running, "RUNNING");
        assertThat(harness.get("/hosts").body()).as("§11: not yet retired at the request's clock")
                                                .contains("\"sharedCpu\":false,\"retireAt\":\"" + retireAt + "\",\"retired\":false");

        shift.set(Duration.ofHours(2));
        var queued = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "core", "verify")));
        Thread.sleep(NO_GRANT_WINDOW.toMillis());

        assertThat(harness.worker.count(Message.StartUnit.class)).as("starts after the instant").isEqualTo(1);
        assertThat(harness.get("/jobs/" + queued).body()).startsWith("{\"job\":" + queued + ",\"state\":\"QUEUED\"");
        assertThat(harness.get("/digest").body()).contains("\"host\":\"bb\",\"reason\":\"retired\",\"detail\":\"since=" + retireAt + "\"")
                                                 .as("§11: the digest's host carries the link's marks")
                                                 .contains("\"sharedCpu\":false,\"retireAt\":\"" + retireAt + "\",\"retired\":true");
        assertThat(harness.get("/hosts").body()).as("§11: retired at the request's clock, still running a unit")
                                                .contains("\"active\":1,")
                                                .contains("\"sharedCpu\":false,\"retireAt\":\"" + retireAt + "\",\"retired\":true");

        harness.worker.send(exited(running));
        harness.worker.send(report(running));

        assertThat(harness.waitForState(running, "PASSED")).contains("\"verdict\":\"PASSED\"");
        Thread.sleep(500);
        assertThat(harness.worker.count(Message.StartUnit.class)).as("a finished unit frees nothing on a retired host")
                                                                 .isEqualTo(1);
        assertThat(harness.get("/jobs/" + queued).body()).startsWith("{\"job\":" + queued + ",\"state\":\"QUEUED\"");
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
