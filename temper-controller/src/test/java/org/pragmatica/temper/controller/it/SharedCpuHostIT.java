package org.pragmatica.temper.controller.it;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.protocol.message.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;


/// m4-design §9.1 (probe m6): a host configured `sharedCpu = true` reaches `hosts.shared_cpu` through the controller's
/// start-time upsert, and the grant reads it there: a `--dedicated-cpu` job submitted first stays QUEUED while a plain
/// job behind it is granted on the same host.
///
/// Probe m6: `Controller.upsertHost` passes `sharedCpu = false` → the dedicated job is granted too (red).
@Tag("pg")
class SharedCpuHostIT {
    private static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";
    /// Ten scheduler intervals (200 ms) with no further grant.
    private static final Duration NO_GRANT_WINDOW = Duration.ofSeconds(2);

    private ControllerHarness harness;

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void sharedCpuHost_grantsThePlainJob_dedicatedJobStaysQueued() throws Exception {
        harness = new ControllerHarness(TimeSpan.timeSpan(500).millis(), Instant::now, SharedCpuHostIT::shared);

        var dedicated = jobOf(harness.post("/jobs", dedicatedCpu(mvnJob(BY_MAIN, "-pl", "held", "verify"))));
        var plain = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "core", "verify")));
        harness.worker.expect(Message.StartUnit.class, 20);
        assertThat(harness.waitForState(plain, "PASSED")).contains("\"verdict\":\"PASSED\"");
        Thread.sleep(NO_GRANT_WINDOW.toMillis());

        assertThat(harness.worker.count(Message.StartUnit.class)).as("starts on the shared-CPU host").isEqualTo(1);
        assertThat(harness.get("/jobs/" + dedicated).body()).startsWith("{\"job\":" + dedicated + ",\"state\":\"QUEUED\"")
                                                            .contains("\"dedicatedCpu\":true");
        assertThat(harness.get("/hosts").body()).as("§11: GET /hosts shows the CPU class; no retireAt, not retired")
                                                .contains("\"sharedCpu\":true,\"retireAt\":null,\"retired\":false");
        assertThat(harness.get("/jobs/" + plain).body()).as("§9.4: the unit's attempt ran on a shared-CPU host")
                                                        .contains("\"attempt\":1,\"sharedCpu\":true");
    }

    private static String dedicatedCpu(String body) {
        return body.substring(0, body.length() - 1) + ",\"dedicatedCpu\":true}";
    }

    private static ControllerConfig.Host shared(ControllerConfig.Host host) {
        return ControllerConfig.Host.host(host.name(),
                                          host.address(),
                                          host.user(),
                                          host.key(),
                                          host.knownHosts(),
                                          host.cap(),
                                          host.memoryBudgetMb(),
                                          host.portIsolated(),
                                          true,
                                          host.retireAt());
    }
}
