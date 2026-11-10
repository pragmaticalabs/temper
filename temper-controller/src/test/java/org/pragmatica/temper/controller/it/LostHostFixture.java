package org.pragmatica.temper.controller.it;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fake.FakeWorker;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.StoredAttempt;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// A controller on a real store whose links go LOST after [#HOST_LOST] down (WP-4a's two-host harness, m4-design §2.1).
/// A `held` unit starts as attempt 1 on one host; that host vanishes (its worker exits, every later ssh fails), the
/// controller declares it LOST and settles the unit (requeued, attempt 1 kept). The vanished host is then let back: its
/// next worker is returned without its `Hello`, so the test decides what its disk holds. Every worker answers a
/// `StartUnit` in the request's own attempt; a `DiscardUnit` is answered after recording `/hosts` at that moment.
final class LostHostFixture {
    static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";
    static final Duration HOST_LOST = Duration.ofSeconds(3);
    static final HostName CB = HostName.hostName("cb").unwrap();
    static final String CB_ADDRESS = "cb.example";
    static final long MARKER = 1_700_000_000_000_000_000L;

    final ControllerHarness harness;
    final long job;
    final UnitRef ref;
    /// The first worker of each host, by host.
    final Map<HostName, FakeWorker> workers;
    final HostName lost;
    final HostName survivor;
    /// The `/hosts` body read while a `DiscardUnit` was being answered; empty until one arrives.
    final AtomicReference<String> hostsAtDiscard = new AtomicReference<>("");

    /// `withCb`: a second host, `cb`, besides `bb`; without it the unit's host is `bb` alone.
    LostHostFixture(boolean withCb) throws Exception {
        harness = new ControllerHarness(TimeSpan.timeSpan(500).millis(),
                                        withCb
                                        ? List.of(ControllerConfig.Host.host(CB, CB_ADDRESS, "builder", Path.of("/k"), Path.of("/kh"), 5, 20480, false))
                                        : List.of(),
                                        HOST_LOST,
                                        Duration.ofSeconds(1));
        harness.worker.respondWith(this::answer);
        workers = withCb
                  ? Map.of(ControllerHarness.HOST, harness.worker, CB, greeted(harness.ssh.nextWorker(CB_ADDRESS), CB))
                  : Map.of(ControllerHarness.HOST, harness.worker);
        waitUntil(() -> workers.keySet().stream().allMatch(this::isUp), "every host UP");
        Thread.sleep(1500);
        job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));
        ref = UnitRef.unitRef(JobId.jobId(job).unwrap(), UnitName.MAIN);
        waitUntil(() -> current().attempt() == 1 && current().host().isPresent(), "attempt 1 granted");
        harness.waitForState(job, "RUNNING");
        lost = current().host().unwrap();
        survivor = workers.keySet().stream().filter(host -> !host.equals(lost)).findFirst().orElse(lost);
    }

    private FakeWorker greeted(FakeWorker worker, HostName host) {
        worker.respondWith(this::answer);
        worker.send(hello(host, List.of()));
        return worker;
    }

    /// `StartUnit` → `UnitStarted` in the request's attempt; `DiscardUnit` → `Ack`, recording `/hosts` first.
    List<Message> answer(Message request) {
        return switch (request) {
            case Message.StartUnit start -> List.of(new Message.UnitStarted(start.job(), start.unit(), start.attempt(), "c" + start.attempt(),
                                                                            "sha256:img", MARKER, Instant.now(), ControllerHarness.facts(), none()));
            case Message.DiscardUnit _ -> discardAnswer();
            default -> harness.respond(request);
        };
    }

    private List<Message> discardAnswer() {
        try {
            hostsAtDiscard.set(harness.get("/hosts").body());
        } catch (Exception e) {
            hostsAtDiscard.set("unreadable: " + e);
        }
        return List.of(new Message.Ack(none()));
    }

    /// The unit's host vanishes; returns once the controller marked it LOST and settled the unit off it.
    void lose() throws Exception {
        harness.ssh.blackholed.add(address(lost));
        workers.get(lost).close();
        waitUntil(() -> loss(lost).isPresent(), lost + " LOST");
        waitUntil(() -> !current().host().equals(some(lost)) || current().attempt() == 2, "the unit settled off " + lost);
    }

    /// The lost host answers again: its next worker, answering through `answers`, not yet greeted.
    FakeWorker back(Function<Message, List<Message>> answers) throws Exception {
        harness.ssh.blackholed.remove(address(lost));
        var back = harness.ssh.nextWorker(address(lost));
        back.respondWith(answers);
        return back;
    }

    private static String address(HostName host) {
        return host.equals(CB)
               ? CB_ADDRESS
               : "localhost";
    }

    static Message.Hello hello(HostName host, List<DiskUnitDto> disk) {
        return new Message.Hello(Handshake.PROTOCOL_VERSION, ControllerHarness.VERSION, host.value(), 1000, 1000, 999, 32,
                                 ControllerHarness.facts(), disk);
    }

    /// The returning worker's disk: attempt `attempt` of the unit, its container in `container`.
    DiskUnitDto onDisk(int attempt, ContainerStatus container) {
        var rc = container == ContainerStatus.EXITED
                 ? some(0)
                 : Option.<Integer> none();
        return DiskUnitDto.diskUnitDto(job, "main", true, rc, container, none(), none(), none(), some(attempt));
    }

    boolean isUp(HostName host) {
        try {
            return harness.get("/hosts").body().contains("\"name\":\"" + host.value() + "\",\"state\":\"UP\"");
        } catch (Exception e) {
            return false;
        }
    }

    Option<HostLoss> loss(HostName host) {
        return harness.store.hosts().loss(host).await(Pg.WAIT).unwrap();
    }

    StoredAttempt current() {
        return harness.store.units().attempts(List.of(ref)).await(Pg.WAIT).unwrap().getFirst();
    }

    static Message.UnitExited exited(long job, int attempt) {
        return new Message.UnitExited(job, "main", attempt, 0, none(), Instant.now(),
                                      new UnitEndFactsDto(ControllerHarness.facts(), none(), Instant.now(), Map.of(), ControllerHarness.LEFTOVERS, none()),
                                      1, 1, 0, none(), none());
    }

    static Message.UnitReport report(long job, int attempt, String className) {
        return new Message.UnitReport(job, "main", attempt, 0, true, List.of(new TestcaseDto(className, "works", "passed", 0.2, "main", false)));
    }

    interface Check {
        boolean holds() throws Exception;
    }

    static void waitUntil(Check check, String what) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.holds()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    void close() {
        harness.close();
    }
}
