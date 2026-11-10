package org.pragmatica.temper.controller.it;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fake.FakeWorker;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.StoredAttempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// m4-design §2.1–§2.5, §12.2 `HostLostLinkIT`: a controller with two fake hosts on a real store. The host running a
/// unit vanishes (its worker exits, every later ssh fails at once); after `hostLost` (3 s; the design says 2 s, widened so both hosts reach UP well inside it), not before, it is LOST
/// (`link-down`), its unit is settled (requeued) and the other host gets it as attempt 2. When the vanished host
/// answers again it recovers: UP, the mark cleared, and the unit stays the other host's.
///
/// Probes: P3 (`GRANT` does not increment `attempt`) → the survivor's `StartUnit` names attempt 1 (red); the LOST
/// declaration removed from `HostLink` → no LOST mark, no requeue (red).
@Tag("pg")
/// `stableUp` is 1 s (design §12.2), so both hosts' clocks clear before the drop.
class HostLostLinkIT {
    private static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";
    private static final Duration HOST_LOST = Duration.ofSeconds(3);
    private static final HostName CB = HostName.hostName("cb").unwrap();
    private static final String CB_ADDRESS = "cb.example";

    private ControllerHarness harness;

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void vanishedHost_lostAfterBound_unitRunsElsewhereAsAttempt2_hostRecovers() throws Exception {
        harness = new ControllerHarness(TimeSpan.timeSpan(500).millis(),
                                        List.of(ControllerConfig.Host.host(CB, CB_ADDRESS, "builder", Path.of("/k"), Path.of("/kh"), 5, 20480, false)),
                                        HOST_LOST,
                                        Duration.ofSeconds(1));
        var bb = harness.worker;
        var cb = harness.ssh.nextWorker(CB_ADDRESS);
        bb.respondWith(this::echoingAttempt);
        cb.respondWith(this::echoingAttempt);
        cb.send(hello("cb"));
        waitUp(CB);
        Thread.sleep(HOST_LOST.toMillis() + 1000);
        assertThat(loss(ControllerHarness.HOST).isEmpty() && loss(CB).isEmpty()).as("both UP past hostLost, neither LOST").isTrue();

        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));
        var ref = UnitRef.unitRef(JobId.jobId(job).unwrap(), UnitName.MAIN);
        var first = waitStart(bb, cb);
        assertThat(first.attempt()).isEqualTo(1);
        harness.waitForState(job, "RUNNING");

        var lostIsBb = bb.count(Message.StartUnit.class) == 1;
        var lostHost = lostIsBb ? ControllerHarness.HOST : CB;
        var lostAddress = lostIsBb ? "localhost" : CB_ADDRESS;
        var survivor = lostIsBb ? cb : bb;
        harness.ssh.blackholed.add(lostAddress);
        (lostIsBb ? bb : cb).close();
        var vanished = Instant.now();

        Thread.sleep(HOST_LOST.toMillis() / 2);
        var seen = loss(lostHost);
        // Binds only while the look itself is inside hostLost of the drop: a late observer (starved CI runner) sees a LOST that
        // is on time, which the `at` check below pins.
        if (Duration.between(vanished, Instant.now()).compareTo(HOST_LOST.minusMillis(50)) < 0) {
            assertThat(seen).as("not LOST before hostLost").isEqualTo(none());
        }

        var mark = waitLoss(lostHost);
        assertThat(mark.reason()).isEqualTo("link-down");
        assertThat(mark.at()).as("LOST at hostLost after the drop").isAfterOrEqualTo(vanished.plus(HOST_LOST).minusMillis(50));

        var second = survivor.expect(Message.StartUnit.class, 20);
        assertThat(second.job()).isEqualTo(job);
        assertThat(second.attempt()).as("the requeued unit's next grant is attempt 2").isEqualTo(2);
        var regranted = current(ref);   // one read: its state moves GRANTED -> RUNNING concurrently
        assertThat(regranted).isEqualTo(StoredAttempt.storedAttempt(ref, 2, some(survivor == bb ? ControllerHarness.HOST : CB),
                                                                    regranted.state()));
        assertThat(harness.get("/jobs/" + job).body()).as("the job stays RUNNING (Q-M4-5)").contains("\"state\":\"RUNNING\"");
        var jobView = harness.get("/jobs/" + job).body();
        assertThat(jobView).as("§11: the unit's current attempt and its lost one")
                           .contains("\"attempt\":2,\"sharedCpu\":false,\"sideEffects\":\"none\",\"lostAttempts\":[{\"attempt\":1,\"host\":\""
                                     + lostHost.value() + "\",\"grantedAt\":\"")
                           .contains("\"dedicatedCpu\":false");
        assertThat(harness.get("/hosts").body()).as("§11: GET /hosts shows the LOST state, its link and its mark")
                                                .contains("\"name\":\"" + lostHost.value() + "\",\"state\":\"LOST\",\"link\":\"")
                                                .containsPattern("\"lost\":\\{\"at\":\"[^\"]+\",\"reason\":\"link-down\"\\}");

        harness.ssh.blackholed.remove(lostAddress);
        var back = harness.ssh.nextWorker(lostAddress);
        back.respondWith(this::echoingAttempt);
        back.send(hello(lostHost.value()));

        waitUntil(() -> loss(lostHost).isEmpty(), "the mark cleared on recovery");
        waitUntil(() -> harness.get("/hosts").body().contains("\"name\":\"" + lostHost.value() + "\",\"state\":\"UP\",\"link\":\"UP\",\"lost\":null,"),
                  "§11: a recovered host is UP with no mark");
        assertThat(current(ref).host()).as("the unit stays on the survivor").isEqualTo(some(survivor == bb ? ControllerHarness.HOST : CB));
        assertThat(current(ref).attempt()).isEqualTo(2);
    }

    private List<Message> echoingAttempt(Message request) {
        return switch (request) {
            case Message.StartUnit start -> List.of(new Message.UnitStarted(start.job(), start.unit(), start.attempt(), "c" + start.job(),
                                                                            "sha256:img", 1_700_000_000_000_000_000L, Instant.now(),
                                                                            ControllerHarness.facts(), none()));
            default -> harness.respond(request);
        };
    }

    private static Message.Hello hello(String host) {
        return new Message.Hello(Handshake.PROTOCOL_VERSION, ControllerHarness.VERSION, host, 1000, 1000, 999, 32,
                                 ControllerHarness.facts(), List.of());
    }

    private static Message.StartUnit waitStart(FakeWorker bb, FakeWorker cb) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (var worker : List.of(bb, cb)) {
                var start = worker.all.stream().map(envelope -> envelope.msg()).filter(Message.StartUnit.class::isInstance).findFirst();
                if (start.isPresent()) {
                    return (Message.StartUnit) start.get();
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no StartUnit on either host");
    }

    private void waitUp(HostName host) throws Exception {
        waitUntil(() -> harness.get("/hosts").body().contains("\"name\":\"" + host.value() + "\",\"state\":\"UP\""), host + " UP");
    }

    private HostLoss waitLoss(HostName host) throws Exception {
        waitUntil(() -> loss(host).isPresent(), host + " LOST");
        return loss(host).unwrap();
    }

    private org.pragmatica.lang.Option<HostLoss> loss(HostName host) {
        return harness.store.hosts().loss(host).await(Pg.WAIT).unwrap();
    }

    private StoredAttempt current(UnitRef ref) {
        return harness.store.units().attempts(List.of(ref)).await(Pg.WAIT).unwrap().getFirst();
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    private static void waitUntil(Check check, String what) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.holds()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
