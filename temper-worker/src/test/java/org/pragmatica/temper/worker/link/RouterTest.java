package org.pragmatica.temper.worker.link;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.LogSource;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.RecordingOutbox;
import org.pragmatica.temper.worker.WorkerAssembly;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.unit.UnitFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.failure;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The request handlers wired by [WorkerAssembly] against a scripted docker: re-attach (A4 worker side), collect,
/// report ack cleanup, cancel and log tail.
class RouterTest {
    private static final String CID = "f".repeat(64);
    private static final List<String> STATUS = List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", CID);

    @TempDir
    Path home;

    private FakeRunner runner;
    private RecordingOutbox outbox;
    private WorkerConfig config;
    private UnitFiles files;
    private Router router;
    private Fixtures.ManualTimers timers;
    private final CountDownLatch exit = new CountDownLatch(1);
    private final UnitRef ref = Fixtures.ref(8, "main");

    @BeforeEach
    void setUp() throws Exception {
        var proc = Files.createDirectories(home.resolve("proc"));

        Files.writeString(proc.resolve("loadavg"), "0.1 0.2 0.3 1/1 1\n");
        Files.writeString(proc.resolve("meminfo"), "MemAvailable: 5 kB\n");
        runner = new FakeRunner();
        outbox = new RecordingOutbox();
        config = Fixtures.config(home.resolve(".temper"), proc, home);
        files = UnitFiles.unitFiles(config.paths());
        runner.on(List.of("docker", "wait", CID), _ -> waited())
              .on(List.of("docker", "logs", CID), 0, "out\n", "")
              .on(List.of("docker", "ps"), 0, "", "");
        timers = new Fixtures.ManualTimers();
        router = ok(WorkerAssembly.assemble(config, runner, TemperJson.mapper(), outbox, Instant::now, timers)
                                  .await()).router();
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.START, "1760356800000000000"));
        ok(files.store(ref, UnitFile.CONTAINER, CID));
    }

    private Result<org.pragmatica.temper.protocol.exec.Completed> waited() {
        try {
            exit.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
        return Result.success(FakeRunner.completed(0, "0\n", ""));
    }

    private Reply route(Message message) {
        return ok(router.route(message)
                        .await());
    }

    @Test
    void reattach_rearmsWithTheAuthoritativeTimeout_andWatchesUntilExit() throws Exception {
        runner.on(STATUS, 0, "running", "");

        var reply = route(new Message.Reattach(8, "main", 1, Duration.ofMinutes(90)));

        assertThat(reply).isEqualTo(Reply.respond(new Message.Ack(Option.none())));
        assertThat(ok(files.text(ref, UnitFile.TIMEOUT)).or("")).isEqualTo("PT1H30M");
        assertThat(runner.callsStartingWith(List.of("docker", "wait", CID))).hasSize(1);

        exit.countDown();

        var exited = outbox.awaitPosted(Message.UnitExited.class, 1);

        assertThat(exited).singleElement()
                          .satisfies(message -> assertThat(message.rc()).isZero());
        assertThat(outbox.awaitPosted(Message.UnitReport.class, 1)).singleElement()
                                                                   .satisfies(chunk -> assertThat(chunk.last()).isTrue());
        assertThat(outbox.posted()
                         .indexOf(exited.getFirst())).isLessThan(outbox.posted()
                                                                       .indexOf(outbox.awaitPosted(Message.UnitReport.class, 1)
                                                                                      .getFirst()));
    }

    @Test
    void reattach_isLost_whenTheContainerIsGoneWithoutAnRc() {
        runner.on(STATUS, 1, "", "Error: No such object: " + CID);

        var cause = failure(router.route(new Message.Reattach(8, "main", 1, Duration.ofMinutes(90)))
                                  .await());

        assertThat(WorkerError.code(cause)).isEqualTo("lost");
    }

    @Test
    void collect_acksFirst_thenSendsTheReport_andResendsOnASecondCollect() throws Exception {
        runner.on(STATUS, 0, "exited", "")
              .on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.ExitCode}}", CID), 0, "1\n", "");

        var reply = route(new Message.Collect(8, "main", 1));

        assertThat(reply).isInstanceOfSatisfying(Reply.Respond.class, respond -> {
            assertThat(respond.message()).isEqualTo(new Message.Ack(Option.none()));
            assertThat(respond.followUp()
                              .isPresent()).isTrue();
        });
        assertThat(outbox.posted()).isEmpty();

        ((Reply.Respond) reply).followUp()
                               .onPresent(followUp -> ok(followUp.run()
                                                                 .await()));

        var first = outbox.awaitPosted(Message.UnitExited.class, 1);

        assertThat(first.getFirst()
                        .rc()).isEqualTo(1);

        ((Reply.Respond) route(new Message.Collect(8, "main", 1))).followUp()
                                                               .onPresent(followUp -> ok(followUp.run()
                                                                                                 .await()));

        assertThat(outbox.awaitPosted(Message.UnitExited.class, 2)).hasSize(2)
                                                                   .allSatisfy(message -> assertThat(message).isEqualTo(first.getFirst()));
    }

    @Test
    void unitReportAck_removesTheContainerThenTheNetwork() {
        assertThat(route(new Message.UnitReportAck(8, "main", 1))).isEqualTo(Reply.silent());

        var rm = runner.indexOf(List.of("docker", "rm", "-f", CID));
        var networkRm = runner.indexOf(List.of("docker", "network", "rm", "temper-8-main"));

        assertThat(rm).isNotNegative();
        assertThat(networkRm).isGreaterThan(rm);
    }

    @Test
    void cancelUnit_repliesWithTheCancelState() {
        runner.on(STATUS, 0, "running", "");

        assertThat(route(new Message.CancelUnit(8, "main", 1))).isEqualTo(Reply.respond(new Message.Ack(Option.some("CANCELLING"))));
    }

    @Test
    void logTail_readsTheLogFile_orDockerLogsBeforeExit() throws Exception {
        runner.on(List.of("docker", "logs", "--tail", "2", CID), 0, "a\nb\n", "");

        assertThat(route(new Message.LogTail(8, "main", 1, 2))).isEqualTo(Reply.respond(new Message.LogChunk("a\nb\n", false, LogSource.DOCKER)));

        Files.writeString(config.paths()
                                .logFile(ref), "1\n2\n3\n");

        assertThat(route(new Message.LogTail(8, "main", 1, 2))).isEqualTo(Reply.respond(new Message.LogChunk("2\n3\n", true, LogSource.FILE)));
    }

    @Test
    void route_refusesWorkerToControllerMessages_andMalformedRequests() {
        assertThat(WorkerError.code(failure(router.route(new Message.Heartbeat(List.of(), null))
                                                  .await()))).isEqualTo("unexpected");
        assertThat(WorkerError.code(failure(router.route(new Message.PrepareJob(8, "not-a-sha", "origin"))
                                                  .await()))).isEqualTo("bad-request");
        assertThat(WorkerError.code(failure(router.route(new Message.Collect(8, "../etc", 1))
                                                  .await()))).isEqualTo("bad-request");
    }

    @Test
    void factsRequest_repliesWithHostFacts() {
        runner.on(List.of("docker", "ps", "-q"), 0, "", "");

        assertThat(route(new Message.FactsRequest())).isInstanceOfSatisfying(Reply.Respond.class,
                                                                             respond -> assertThat(respond.message()).isInstanceOf(Message.Facts.class));
    }

    /// The unit is forgotten (its timer cancelled) before the ack resolves, not some time after it: `forget` is a
    /// chain step. The removal answers late, on the runner's thread, so the ack's chain is unresolved when its last
    /// step is attached, and the cancel is slow, so an `on*` callback would leave the timer live when `route` returns.
    @Test
    void unitReportAck_cancelsTheTimer_andIsHonouredEvenWhenRemovalFails() throws Exception {
        timers = new Fixtures.ManualTimers(Duration.ofMillis(300));
        router = ok(WorkerAssembly.assemble(config, runner, TemperJson.mapper(), outbox, Instant::now, timers)
                                  .await()).router();
        runner.on(STATUS, 0, "running", "")
              .delayed(List.of("docker", "rm", "-f", CID), Duration.ofMillis(100), 1, "", "Error response from daemon: device busy");
        route(new Message.Reattach(8, "main", 1, Duration.ofMinutes(90)));

        assertThat(route(new Message.UnitReportAck(8, "main", 1))).isEqualTo(Reply.silent());
        assertThat(timers.scheduled()).singleElement()
                                      .satisfies(entry -> assertThat(entry.cancelled()[0]).isTrue());
        // Let the background watcher finish before the temporary directory is removed.
        exit.countDown();
        outbox.awaitPosted(Message.UnitReport.class, 1);
    }

    @Test
    void collect_answersLost_beforeAnyAck_whenTheContainerIsGoneWithoutAnRc() {
        runner.on(STATUS, 1, "", "Error: No such object: " + CID);

        assertThat(WorkerError.code(failure(router.route(new Message.Collect(8, "main", 1))
                                                  .await()))).isEqualTo("lost");
    }
}
