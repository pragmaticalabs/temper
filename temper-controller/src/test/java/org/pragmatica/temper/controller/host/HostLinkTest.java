package org.pragmatica.temper.controller.host;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.controller.api.TailLog;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.HolderStatusDto;
import org.pragmatica.temper.protocol.message.KilledBy;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.StartFailureKind;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.AttemptRef;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.Granted;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

class HostLinkTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(60);
    private LinkHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new LinkHarness();
    }

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    private static UnitRef ref(long job) {
        return UnitRef.unitRef(JobId.jobId(job).unwrap(), UnitName.MAIN);
    }

    /// Active in attempt 1, the attempt a scanned directory without one reads as (pre-M4) and the fake frames carry.
    private void active(long job, UnitState state, boolean started) {
        activeIn(job, state, started, 1);
    }

    /// Active in attempt `job`, so a test can tell the store's attempt from others.
    private void activeIn(long job, UnitState state, boolean started) {
        activeIn(job, state, started, (int) job);
    }

    private void activeIn(long job, UnitState state, boolean started, int attempt) {
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(ref(job), job, state, 6144, TIMEOUT, none(),
                                                started ? some(Instant.now()) : none(), false, none(), none(), UnitKind.MVN, some(Instant.now()),
                                                attempt));
    }

    private void connectUp(List<DiskUnitDto> disk) throws Exception {
        harness.link.start();
        var worker = harness.nextWorker();
        worker.send(hello(LinkHarness.VERSION, disk));
        worker.expect(Message.Welcome.class, 5);
        waitFor(harness.link::isUp, "link UP");
    }

    @Nested
    class Handshake {
        @Test
        void start_spawnsSshRunWithHostKeyChecking() throws Exception {
            connectUp(List.of());

            assertThat(harness.spawns.getFirst()).containsSequence("-o", "BatchMode=yes")
                                                 .containsSequence("-o", "ServerAliveInterval=15")
                                                 .containsSequence("-o", "StrictHostKeyChecking=yes")
                                                 .containsSequence("builder@localhost", "run", LinkHarness.VERSION);
            assertThat(harness.spawns.getFirst().getFirst()).isEqualTo("ssh");
            assertThat(harness.spawns.getFirst()).contains("-T");
        }

        @Test
        void hello_compatible_welcomesReconcilesAndGoesUp() throws Exception {
            connectUp(List.of());
            waitFor(() -> harness.hosts.states.contains("UP"), "host state UP recorded");

            assertThat(harness.hosts.states).contains("CONNECTING", "UP");
            assertThat(harness.link.facts().isPresent()).isTrue();
        }

        @Test
        void hello_incompatible_answersIncompatibleDeploysJarAndReconnects() throws Exception {
            harness.link.start();
            var old = harness.nextWorker();
            old.send(hello("0.0.9", List.of()));
            var incompatible = old.expect(Message.Incompatible.class, 5);

            assertThat(incompatible.expectedWorkerVersion()).isEqualTo(LinkHarness.VERSION);

            var fresh = harness.nextWorker();
            fresh.send(hello(LinkHarness.VERSION, List.of()));
            fresh.expect(Message.Welcome.class, 5);
            waitFor(harness.link::isUp, "link UP after deploy");

            var install = harness.spawns.stream().filter(argv -> argv.contains("install")).findFirst().orElseThrow();
            assertThat(install).containsSequence("install", LinkHarness.VERSION, sha256Hex(LinkHarness.JAR));
            assertThat(harness.installedJars).hasSize(1);
            assertThat(harness.installedJars.getFirst()).isEqualTo(LinkHarness.JAR);
        }

        @Test
        void sessionEndsBeforeHello_deploysBeforeNextConnect() throws Exception {
            harness.link.start();
            var first = harness.nextWorker();
            first.close();

            var second = harness.nextWorker();
            second.send(hello(LinkHarness.VERSION, List.of()));
            second.expect(Message.Welcome.class, 5);

            assertThat(harness.spawns.stream().filter(argv -> argv.contains("install")).count()).isEqualTo(1);
        }

        /// D-6: a worker of another build of the same `project.version` (another judge) is stale: `Incompatible`, then
        /// this build's jar is installed under this build's version, and the next session is welcomed.
        @Test
        void hello_staleJudge_isIncompatible_andTheWorkerIsRedeployed() throws Exception {
            harness.link.start();
            var stale = harness.nextWorker();
            stale.send(hello("0.1.0+fedcba987654", List.of()));
            var incompatible = stale.expect(Message.Incompatible.class, 5);

            assertThat(incompatible.expectedWorkerVersion()).isEqualTo(LinkHarness.VERSION);

            var fresh = harness.nextWorker();
            fresh.send(hello(LinkHarness.VERSION, List.of()));
            fresh.expect(Message.Welcome.class, 5);
            waitFor(harness.link::isUp, "link UP after the redeploy");

            assertThat(harness.spawns.stream().filter(argv -> argv.contains("install")).toList())
                .singleElement()
                .satisfies(install -> assertThat(install).containsSequence("install", LinkHarness.VERSION));
        }

        /// D-6: a new build's `worker-<ver>.jar` is absent on the host, so `run <ver>` ends before `Hello`; the
        /// controller deploys the jar under the new version and connects to it.
        @Test
        void newBuildVersion_missingJar_isDeployedUnderThatVersion() throws Exception {
            harness.link.start();
            var missing = harness.nextWorker();
            missing.close();

            var deployed = harness.nextWorker();
            deployed.send(hello(LinkHarness.VERSION, List.of()));
            deployed.expect(Message.Welcome.class, 5);

            assertThat(harness.spawns.getFirst()).containsSequence("run", LinkHarness.VERSION);
            assertThat(harness.spawns.stream().filter(argv -> argv.contains("install")).toList())
                .singleElement()
                .satisfies(install -> assertThat(install).containsSequence("install", LinkHarness.VERSION));
            assertThat(harness.installedJars.getFirst()).isEqualTo(LinkHarness.JAR);
        }

        /// L4: a configured worker jar of another build (another judge) is never installed, since its worker would be
        /// Incompatible and redeployed forever; the refusal is logged at ERROR naming both builds.
        @Test
        void foreignWorkerJar_isNotInstalled_andTheRefusalIsLoggedAsAnError() throws Exception {
            var foreign = "fedcba987654" + "0".repeat(28);
            Files.write(harness.jar, LinkHarness.workerJar("0.1.0", foreign));
            var logged = new ListAppender<ILoggingEvent>();
            var logger = (Logger) LoggerFactory.getLogger(HostLink.class);
            logged.start();
            logger.addAppender(logged);
            try {
                harness.link.start();
                harness.nextWorker()
                       .close();
                harness.nextWorker();
                waitFor(() -> logged.list.stream()
                                         .anyMatch(event -> event.getLevel() == Level.ERROR),
                        "the refusal logged");
            } finally {
                logger.detachAppender(logged);
            }

            assertThat(harness.spawns.stream().filter(argv -> argv.contains("install")).toList()).isEmpty();
            assertThat(harness.installedJars).isEmpty();
            assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                                   .extracting(ILoggingEvent::getFormattedMessage)
                                   .singleElement()
                                   .satisfies(line -> assertThat(line).contains("0.1.0+fedcba987654", LinkHarness.VERSION));
        }

        /// A failed `install` (non-zero exit) is a deploy failure, logged as a warning naming the host; nothing claims a
        /// deployed worker.
        @Test
        void failedInstall_isLoggedAsADeployFailure() throws Exception {
            harness.installExit = 1;
            var logged = new ListAppender<ILoggingEvent>();
            var logger = (Logger) LoggerFactory.getLogger(HostLink.class);
            logged.start();
            logger.addAppender(logged);
            try {
                harness.link.start();
                harness.nextWorker()
                       .close();
                harness.nextWorker();
                waitFor(() -> logged.list.stream()
                                         .anyMatch(event -> event.getFormattedMessage()
                                                                 .contains("Deploying the worker to bb failed")),
                        "the deploy failure logged");
            } finally {
                logger.detachAppender(logged);
            }

            assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                                   .noneMatch(line -> line.contains("deployed worker"));
        }

        /// L4: a jar without the worker's version resource names no build: not installed either.
        @Test
        void workerJarWithoutAVersion_isNotInstalled() throws Exception {
            Files.writeString(harness.jar, "not a worker jar");
            harness.link.start();
            harness.nextWorker()
                   .close();
            harness.nextWorker();

            assertThat(harness.spawns.stream().filter(argv -> argv.contains("install")).toList()).isEmpty();
        }

        @Test
        void jarVersion_isTheBuildVersionTheJarsResourceDerives() {
            assertThat(WorkerDeploy.jarVersion(LinkHarness.JAR)
                                   .unwrap()).isEqualTo(LinkHarness.VERSION);
            assertThat(WorkerDeploy.jarVersion(LinkHarness.workerJar("0.1.0", LinkHarness.JUDGE + "-dirty"))
                                   .unwrap()).isEqualTo(LinkHarness.VERSION + "-dirty");
            assertThat(WorkerDeploy.jarVersion("not a jar".getBytes())
                                   .isFailure()).isTrue();
            assertThat(WorkerDeploy.jarVersion(LinkHarness.workerJar("0.1.0", "${temper.judge}"))
                                   .isFailure()).as("an unfiltered judge").isTrue();
        }

        @Test
        void request_onClosedSession_failsAtOnce() throws Exception {
            harness.link.start();
            var process = new org.pragmatica.temper.controller.fake.PipeProcess();
            var session = Connection.connection(LinkHarness.HOST, process, org.pragmatica.temper.protocol.json.TemperJson.mapper(),
                                                (connection, message) -> {});
            session.close();
            var started = System.nanoTime();

            var result = session.request(new Message.FactsRequest(), LinkHarness.seconds(30)).await();

            assertThat(result.isFailure()).isTrue();
            assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
        }

        /// A closed session is not UP from the moment it closes, before the DOWN transition runs: no grant is
        /// launched on it, so nothing is left GRANTED waiting for the next reconcile. Checked right after each close.
        @Test
        void closedSession_isNotUp_atOnce_andLaunchFailsDownWithoutSending() throws Exception {
            harness.link.start();
            for (var cycle = 0; cycle < 20; cycle++) {
                var worker = harness.nextWorker();
                worker.send(hello(LinkHarness.VERSION, List.of()));
                waitFor(harness.link::isUp, "link UP");
                var session = ((LinkState.Up) harness.link.state()).session();

                session.close();

                assertThat(harness.link.isUp()).as("cycle " + cycle).isFalse();
                assertThat(harness.link.request(new Message.FactsRequest()).await().isFailure()).isTrue();
            }
        }

        @Test
        void request_failsDown_whenNotUp() {
            var result = harness.link.request(new Message.FactsRequest()).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(LinkError.Down.class));
        }
    }

    @Nested
    class Down {
        @Test
        void workerExit_marksDownAndReconnects() throws Exception {
            connectUp(List.of());
            harness.lastWorker.close();

            var second = harness.nextWorker();
            second.send(hello(LinkHarness.VERSION, List.of()));
            second.expect(Message.Welcome.class, 5);
            waitFor(harness.link::isUp, "link UP again");

            assertThat(harness.hosts.states).contains("DOWN");
            assertThat(harness.spawns.stream().filter(argv -> argv.contains("run")).count()).isEqualTo(2);
        }

        @Test
        void silence_closesTheLinkAndReconnects() throws Exception {
            connectUp(List.of());

            var second = harness.nextWorker();

            assertThat(second).isNotNull();
            assertThat(harness.hosts.states).contains("DOWN");
        }

        @Test
        void stop_sendsShutdownAndDoesNotReconnect() throws Exception {
            connectUp(List.of());
            var worker = harness.lastWorker;

            harness.link.stop();

            worker.expect(Message.Shutdown.class, 5);
            Thread.sleep(500);
            assertThat(harness.spawns).hasSize(1);
        }
    }

    @Nested
    class Backoff {
        private final List<Integer> attempts = new java.util.concurrent.CopyOnWriteArrayList<>();

        private LinkTiming timing(Duration stableUp) {
            var fast = LinkHarness.FAST;
            return LinkTiming.linkTiming(fast.hello(), fast.silence(), fast.livenessCheck(), fast.request(), fast.prepare(),
                                         fast.start(), fast.deploy(), attempt -> recorded(attempt), stableUp,
                                         fast.startRetry(), fast.startAttemptTimeout(), fast.unrecordedAfter());
        }

        private org.pragmatica.lang.io.TimeSpan recorded(int attempt) {
            attempts.add(attempt);
            return org.pragmatica.lang.io.TimeSpan.timeSpan(20).millis();
        }

        private void upThenDrop(LinkHarness link) throws Exception {
            var worker = link.nextWorker();
            worker.send(hello(LinkHarness.VERSION, List.of()));
            waitFor(link.link::isUp, "link UP");
            worker.close();
        }

        @Test
        void shortUp_keepsBackingOff() throws Exception {
            harness.link.stop();
            harness = new LinkHarness(timing(Duration.ofSeconds(60)));
            harness.link.start();

            upThenDrop(harness);
            upThenDrop(harness);
            waitFor(() -> attempts.size() == 2, "two reconnect delays");

            assertThat(attempts).containsExactly(1, 2);
        }

        @Test
        void stableUp_resetsTheBackoff() throws Exception {
            harness.link.stop();
            harness = new LinkHarness(timing(Duration.ZERO));
            harness.link.start();

            upThenDrop(harness);
            upThenDrop(harness);
            waitFor(() -> attempts.size() == 2, "two reconnect delays");

            assertThat(attempts).containsExactly(1, 1);
        }
    }

    @Nested
    class Reconcile {
        @Test
        void hello_reattachesRunningContainer_withAuthoritativeTimeout() throws Exception {
            active(1, UnitState.RUNNING, true);
            connectUp(List.of(DiskUnitDto.diskUnitDto(1, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), none())));

            var reattach = harness.lastWorker.expect(Message.Reattach.class, 5);

            assertThat(reattach.job()).isEqualTo(1);
            assertThat(reattach.timeout()).isEqualTo(TIMEOUT);
            assertThat(harness.units.completions).isEmpty();
            assertThat(harness.units.requeued).isEmpty();
        }

        /// Protocol v3 (m4-design §4.2, §7): `Reattach`, whose answer can complete the unit, names the store's current
        /// attempt. Rule 0 discards any other attempt first, so it is also the one the scan reported.
        @Test
        void hello_reattach_namesTheStoresAttempt() throws Exception {
            active(1, UnitState.RUNNING, true);
            connectUp(List.of(DiskUnitDto.diskUnitDto(1, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(1))));

            assertThat(harness.lastWorker.expect(Message.Reattach.class, 5)
                                         .attempt()).isEqualTo(1);
            assertThat(harness.lastWorker.count(Message.DiscardUnit.class)).isZero();
        }

        /// The disk holds attempt 2, above the store's 1 (a grant the store lost): rule 0 discards it with a WARN, and
        /// the store's attempt, started and not on this disk, ends `ERROR(lost)`.
        @Test
        void hello_discardsAnAttemptAboveTheStores() throws Exception {
            active(1, UnitState.RUNNING, true);
            connectUp(List.of(DiskUnitDto.diskUnitDto(1, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(2))));

            assertThat(harness.lastWorker.expect(Message.DiscardUnit.class, 5)).isEqualTo(new Message.DiscardUnit(1, "main", 2));
            assertThat(harness.lastWorker.count(Message.Reattach.class)).isZero();
            waitFor(() -> !harness.units.completions.isEmpty(), "the current attempt settled");
            assertThat(harness.units.completions.getFirst().finish().errorReason()).isEqualTo(some(UnitFinishes.LOST));
        }

        /// `KillOrphan` acts on what the worker reported: it names the disk attempt.
        @Test
        void hello_killOrphan_namesTheDiskAttempt() throws Exception {
            connectUp(List.of(DiskUnitDto.diskUnitDto(5, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(4))));

            assertThat(harness.lastWorker.expect(Message.CancelUnit.class, 5)
                                         .attempt()).isEqualTo(4);
        }

        /// `Cancel` (a CANCELLING unit whose container runs) names the disk attempt, the `Reattach` beside it the store's;
        /// after rule 0 they are the same attempt (7).
        @Test
        void hello_cancel_andReattach_nameTheCurrentAttempt() throws Exception {
            activeIn(7, UnitState.CANCELLING, true);
            connectUp(List.of(DiskUnitDto.diskUnitDto(7, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(7))));

            assertThat(harness.lastWorker.expect(Message.CancelUnit.class, 5)
                                         .attempt()).isEqualTo(7);
            waitFor(() -> harness.lastWorker.count(Message.Reattach.class) == 1, "the Reattach beside the Cancel");
            assertThat(harness.lastWorker.all.stream()
                                             .map(Envelope::msg)
                                             .flatMap(message -> message instanceof Message.Reattach reattach
                                                                 ? Stream.of(reattach.attempt())
                                                                 : Stream.empty())
                                             .toList()).containsExactly(7);
        }

        /// `KillOrphan` names the disk attempt even when this link still knows another attempt of the unit (one the
        /// previous reconcile had active here, no longer active now).
        @Test
        void hello_killOrphan_namesTheDiskAttempt_notAnAttemptThisLinkStillKnows() throws Exception {
            activeIn(9, UnitState.RUNNING, true);
            connectUp(List.of(DiskUnitDto.diskUnitDto(9, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(9))));
            harness.lastWorker.expect(Message.Reattach.class, 5);
            harness.units.activeOn(LinkHarness.HOST)
                         .clear();
            harness.lastWorker.close();

            var next = harness.nextWorker();
            next.send(hello(LinkHarness.VERSION,
                            List.of(DiskUnitDto.diskUnitDto(9, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(8)))));

            assertThat(next.expect(Message.CancelUnit.class, 5)
                           .attempt()).isEqualTo(8);
        }

        /// M1 (the WP-3 review), with rule 0 (m4-design §4.5): the store granted attempt 3 (not started); the host still
        /// holds attempt 2 with an rc. Attempt 2 is discarded before anything else, nothing collects it, and attempt 3,
        /// which never reached this disk, is requeued after the discard was acked.
        @Test
        void hello_discardsAnOlderAttempt_neverCollectsIt_andRequeuesTheCurrentOne() throws Exception {
            activeIn(3, UnitState.GRANTED, false);
            var requeuedAtDiscard = new AtomicInteger(-1);
            harness.responder = request -> switch (request) {
                case Message.DiscardUnit _ -> {
                    requeuedAtDiscard.set(harness.units.requeued.size());
                    yield List.of(new Message.Ack(none()));
                }
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of(DiskUnitDto.diskUnitDto(3, "main", true, some(0), ContainerStatus.EXITED, none(), none(), none(), some(2))));

            var discard = harness.lastWorker.expect(Message.DiscardUnit.class, 5);
            assertThat(discard).isEqualTo(new Message.DiscardUnit(3, "main", 2));
            assertThat(requeuedAtDiscard.get()).as("nothing requeued before the discard").isZero();
            assertThat(harness.units.requeued).containsExactly(ref(3));
            assertThat(harness.units.requeuedAttempts).as("scoped to the snapshot's attempt").containsExactly(3);
            assertThat(harness.lastWorker.count(Message.Collect.class)).isZero();
            assertThat(harness.units.completions).isEmpty();
        }

        @Test
        void hello_reattach_namesAttemptOne_forAPreM4Directory() throws Exception {
            active(1, UnitState.RUNNING, true);
            connectUp(List.of(DiskUnitDto.diskUnitDto(1, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), none())));

            assertThat(harness.lastWorker.expect(Message.Reattach.class, 5)
                                         .attempt()).isEqualTo(1);
        }

        /// The report ack echoes the attempt of the `UnitExited` it acknowledges.
        @Test
        void hello_collect_namesTheStoresAttempt_andTheAckEchoesTheReportAttempt() throws Exception {
            activeIn(2, UnitState.RUNNING, true);
            harness.responder = request -> switch (request) {
                case Message.Collect collect -> List.of(new Message.Ack(none()),
                                                        exitedIn(collect.job(), collect.attempt()),
                                                        new Message.UnitReport(collect.job(), "main", collect.attempt(), 0, true, List.of()));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of(DiskUnitDto.diskUnitDto(2, "main", true, some(0), ContainerStatus.EXITED, none(), none(), none(), some(2))));

            assertThat(harness.lastWorker.expect(Message.Collect.class, 5)
                                         .attempt()).isEqualTo(2);
            assertThat(harness.lastWorker.expect(Message.UnitReportAck.class, 5)
                                         .attempt()).isEqualTo(2);
        }

        /// The two requests sent outside reconcile, `CancelUnit` ([HostLinks]) and `LogTail` ([TailLog]), name the
        /// store's current attempt of the unit (protocol v3), not a fixed one.
        @Test
        void cancelAndLogTail_nameTheStoresCurrentAttempt() throws Exception {
            connectUp(List.of());
            harness.units.activeOn(LinkHarness.HOST)
                         .add(ActiveUnit.activeUnit(ref(31), 31, UnitState.RUNNING, 6144, TIMEOUT, none(), none(), false, none(), none(),
                                                    UnitKind.MVN, none(), 3));
            harness.jobs.rows.put(JobId.jobId(31).unwrap(),
                                  Rows.job(31, JobState.RUNNING, List.of(Rows.unit(UnitState.RUNNING, some(LinkHarness.HOST), none()))));
            var links = HostLinks.hostLinks(List.of(harness.link), harness.jobs);

            links.cancel(List.of(ref(31)));

            assertThat(harness.lastWorker.expect(Message.CancelUnit.class, 5)
                                         .attempt()).isEqualTo(3);

            TailLog.tailLog(harness.jobs, links)
                   .execute(TailLog.Request.request(JobId.jobId(31).unwrap(), none(), 10));

            var tail = harness.lastWorker.expect(Message.LogTail.class, 5);

            assertThat(tail.attempt()).isEqualTo(3);
            assertThat(tail.lines()).isEqualTo(10);
        }

        /// `CancelUnit` and `LogTail` outside reconcile name the store's current attempt of the unit (protocol v3).
        @Test
        void currentAttempt_isTheStoresAttempt_andFailsForAnUnknownUnit() {
            harness.units.activeOn(LinkHarness.HOST)
                         .add(ActiveUnit.activeUnit(ref(9), 9, UnitState.RUNNING, 6144, TIMEOUT, none(), none(), false, none(), none(),
                                                    UnitKind.MVN, none(), 4));

            assertThat(harness.link.currentAttempt(ref(9))
                                   .await()).isEqualTo(Result.success(4));
            assertThat(harness.link.currentAttempt(ref(10))
                                   .await()
                                   .isFailure()).isTrue();
        }

        @Test
        void hello_collectsExitedUnit_completesWithReportAndAcks() throws Exception {
            active(2, UnitState.RUNNING, true);
            harness.responder = request -> switch (request) {
                case Message.Collect collect -> List.of(new Message.Ack(none()),
                                                        exited(collect.job(), 1),
                                                        new Message.UnitReport(collect.job(), "main", 1, 0, false,
                                                                               List.of(row("A", "passed"))),
                                                        new Message.UnitReport(collect.job(), "main", 1, 1, true,
                                                                               List.of(row("A", "failed"))));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of(DiskUnitDto.diskUnitDto(2, "main", true, some(1), ContainerStatus.EXITED, none(), none(), none(), none())));

            var ack = harness.lastWorker.expect(Message.UnitReportAck.class, 5);

            assertThat(ack.job()).isEqualTo(2);
            assertThat(harness.units.completions).hasSize(1);
            var completion = harness.units.completions.getFirst();
            assertThat(completion.ref()).isEqualTo(ref(2));
            assertThat(completion.finish().rc()).isEqualTo(some(1));
            assertThat(completion.finish().killedBy()).isEqualTo(some(KillCause.TIMEOUT));
            assertThat(completion.rows()).hasSize(2);
            assertThat(completion.decided().isPresent()).isTrue();
        }

        @Test
        /// The reconnect must come from the reset, well inside the 4 s silence bound that would also end the session.
        void collect_storeFailsOnce_sessionResetAndUnitSettlesOnReconnect() throws Exception {
            active(6, UnitState.RUNNING, true);
            harness.units.failCompletes.set(1);
            harness.responder = request -> switch (request) {
                case Message.Collect collect -> List.of(new Message.Ack(none()),
                                                        exited(collect.job(), 0),
                                                        new Message.UnitReport(collect.job(), "main", 1, 0, true, List.of()));
                default -> LinkHarness.ackEverything(request);
            };
            var disk = List.of(DiskUnitDto.diskUnitDto(6, "main", true, some(0), ContainerStatus.EXITED, none(), none(), none(), none()));
            harness.link.start();
            var first = harness.nextWorker();
            first.send(hello(LinkHarness.VERSION, disk));
            first.expect(Message.Collect.class, 5);

            var second = harness.nextWorker(2);
            assertThat(first.count(Message.UnitReportAck.class)).as("the failed completion was not acked").isZero();
            second.send(hello(LinkHarness.VERSION, disk));
            second.expect(Message.UnitReportAck.class, 5);

            assertThat(harness.units.completions).hasSize(1);
            assertThat(harness.units.completions.getFirst().ref()).isEqualTo(ref(6));
        }

        @Test
        void hello_settlesUnitErrorInfra_collectAnsweredWithFailure_keepingItsDetail() throws Exception {
            active(7, UnitState.RUNNING, true);
            harness.responder = request -> switch (request) {
                case Message.Collect _ -> List.of(Message.Failure.failure("collect", "no such unit dir"));
                default -> LinkHarness.ackEverything(request);
            };

            connectUp(List.of(DiskUnitDto.diskUnitDto(7, "main", true, some(0), ContainerStatus.EXITED, none(), none(), none(), none())));

            assertThat(harness.units.completions).hasSize(1);
            assertThat(harness.units.completions.getFirst().finish().errorReason()).isEqualTo(some("infra: collect: no such unit dir"));
        }

        @Test
        void hello_staysDown_collectNeverAnswered() throws Exception {
            active(9, UnitState.RUNNING, true);
            harness.responder = request -> switch (request) {
                case Message.Collect _ -> List.of();
                default -> LinkHarness.ackEverything(request);
            };
            harness.link.start();
            var first = harness.nextWorker();
            first.send(hello(LinkHarness.VERSION, List.of(DiskUnitDto.diskUnitDto(9, "main", true, some(0), ContainerStatus.EXITED, none(), none(), none(), none()))));

            assertThat(harness.nextWorker(4)).as("reconnect after the timed-out reconcile").isNotNull();
            assertThat(harness.hosts.states).doesNotContain("UP");
            assertThat(harness.units.completions).isEmpty();
        }

        @Test
        void hello_goesUp_evenWhenOrphanKillFails() throws Exception {
            harness.responder = request -> switch (request) {
                case Message.CancelUnit _ -> List.of(Message.Failure.failure("cancel", "docker kill failed"));
                default -> LinkHarness.ackEverything(request);
            };

            connectUp(List.of(DiskUnitDto.diskUnitDto(8, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), none())));

            assertThat(harness.link.isUp()).isTrue();
        }

        @Test
        void hello_marksLost_markerWithoutContainerOrRc() throws Exception {
            activeIn(3, UnitState.RUNNING, true);
            connectUp(List.of(DiskUnitDto.diskUnitDto(3, "main", true, none(), ContainerStatus.ABSENT, none(), none(), none(), some(3))));

            waitFor(() -> harness.units.completions.size() == 1, "lost completion");
            assertThat(harness.units.completions.getFirst().finish().errorReason()).isEqualTo(some("lost"));
            assertThat(harness.units.completions.getFirst().attempt()).as("the reconcile snapshot's attempt").isEqualTo(3);
            assertThat(harness.units.attemptReads).as("one read, rule 0's, of the scanned units").containsExactly(List.of(ref(3)));
        }

        /// A CANCELLING unit that never started completes CANCELLED in the attempt of the reconcile snapshot.
        @Test
        void hello_completesCancelled_cancellingNeverStarted() throws Exception {
            activeIn(6, UnitState.CANCELLING, false);
            connectUp(List.of());

            waitFor(() -> harness.units.completions.size() == 1, "cancelled completion");
            assertThat(harness.units.completions.getFirst().finish().killedBy()).isEqualTo(some(KillCause.CANCEL));
            assertThat(harness.units.completions.getFirst().attempt()).as("the reconcile snapshot's attempt").isEqualTo(6);
            assertThat(harness.units.attemptReads).as("one read, rule 0's, of no scanned unit").containsExactly(List.of());
        }

        @Test
        void hello_requeues_grantedNeverStarted() throws Exception {
            active(4, UnitState.GRANTED, false);
            connectUp(List.of());

            assertThat(harness.units.requeued).containsExactly(ref(4));
        }

        /// A11 (m1-design §1.5 rule 3): a unit waiting in bb-hold over a reconnect is re-attached, not lost or re-run.
        @Test
        void hello_reattachesWaitingHold_neitherLostNorRequeued() throws Exception {
            active(12, UnitState.GRANTED, false);
            connectUp(List.of(DiskUnitDto.diskUnitDto(12, "main", true, none(), ContainerStatus.ABSENT,
                                                      some(HolderStatusDto.WAITING), none(), none(), none())));

            var reattach = harness.lastWorker.expect(Message.Reattach.class, 5);
            assertThat(reattach.job()).isEqualTo(12);
            assertThat(harness.units.requeued).isEmpty();
            assertThat(harness.units.completions).isEmpty();
        }

        /// Rule 4: the holder died before its grant; nothing ran, so the unit runs again.
        @Test
        void hello_requeues_holderDeadBeforeTheGrant() throws Exception {
            active(13, UnitState.GRANTED, false);
            connectUp(List.of(DiskUnitDto.diskUnitDto(13, "main", true, none(), ContainerStatus.ABSENT,
                                                      some(HolderStatusDto.DEAD), none(), none(), none())));

            assertThat(harness.units.requeued).containsExactly(ref(13));
        }

        @Test
        void hello_killsOrphan_runningContainerNotActive() throws Exception {
            connectUp(List.of(DiskUnitDto.diskUnitDto(5, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), none())));

            var cancel = harness.lastWorker.expect(Message.CancelUnit.class, 5);

            assertThat(cancel.job()).isEqualTo(5);
        }

        @Test
        void hello_staysDown_whenStoreCannotListActiveUnits() throws Exception {
            harness.units.failActive = true;
            harness.link.start();
            var worker = harness.nextWorker();
            worker.send(hello(LinkHarness.VERSION, List.of()));
            worker.expect(Message.Welcome.class, 5);

            var retry = harness.nextWorker();

            assertThat(retry).isNotNull();
            assertThat(harness.link.isUp()).isFalse();
        }
    }

    @Nested
    class Launch {
        private final Granted granted = Granted.granted(ref(7), 7, UnitKind.MVN,
                                                        List.of(List.of("mvn", "-B", "verify")), TIMEOUT, 6144,
                                                        Sha.sha("a".repeat(40)).unwrap(), false, 0, none(), Instant.now(), 1);

        @Test
        void launch_preparesStartsAndRecordsStart() throws Exception {
            harness.responder = request -> switch (request) {
                case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
                case Message.StartUnit start -> List.of(new Message.UnitStarted(start.job(), start.unit(), 1, "c1", "sha256:i",
                                                                                 1_000_000_000L, Instant.now(),
                                                                                 LinkHarness.facts(0), none()));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of());
            active(7, UnitState.GRANTED, false);

            harness.link.launch(granted).await();

            var prepare = harness.lastWorker.expect(Message.PrepareJob.class, 5);
            var start = harness.lastWorker.expect(Message.StartUnit.class, 5);
            assertThat(prepare.sha()).isEqualTo("a".repeat(40));
            assertThat(prepare.originUrl()).isEqualTo("git@example:repo.git");
            assertThat(start.steps()).isEqualTo(granted.steps());
            assertThat(start.timeout()).isEqualTo(TIMEOUT);
            assertThat(start.runnerVersion()).as("the project.version part: a docker tag allows no +")
                                         .isEqualTo(LinkHarness.RUNNER);
            assertThat(start.cpuShares()).isEqualTo(1024);
            assertThat(harness.units.started).containsExactly(ref(7));
            assertThat(harness.units.startedRows.getFirst().containerId()).isEqualTo("c1");
        }

        @Test
        void launch_voidsUnit_startFailedDisk() throws Exception {
            harness.responder = request -> switch (request) {
                case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
                case Message.StartUnit start -> List.of(new Message.UnitStartFailed(start.job(), start.unit(),
                                                                                     1, StartFailureKind.VOID, "disk",
                                                                                     none()));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of());

            harness.link.launch(granted).await();

            assertThat(harness.units.completions).hasSize(1);
            assertThat(harness.units.completions.getFirst().finish().voidReason()).isEqualTo(some("disk"));
            assertThat(harness.units.started).isEmpty();
        }

        @Test
        void launch_errorsUnit_infraFailureKeepsTheDetail() throws Exception {
            harness.responder = request -> switch (request) {
                case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
                case Message.StartUnit start -> List.of(new Message.UnitStartFailed(start.job(), start.unit(),
                                                                                     1, StartFailureKind.ERROR,
                                                                                     "infra: docker build failed",
                                                                                     none()));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of());

            harness.link.launch(granted).await();

            assertThat(harness.units.completions.getFirst().finish().errorReason()).isEqualTo(some("infra: docker build failed"));
        }

        @Test
        void launch_cancelsUnit_startFailedCancelled() throws Exception {
            harness.responder = request -> switch (request) {
                case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
                case Message.StartUnit start -> List.of(new Message.UnitStartFailed(start.job(), start.unit(),
                                                                                     1, StartFailureKind.CANCELLED,
                                                                                     "cancelled", none()));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of());

            harness.link.launch(granted).await();

            assertThat(harness.units.completions.getFirst().finish().killedBy()).isEqualTo(some(KillCause.CANCEL));
        }

        @Test
        void launch_errorsSeedIsolation_prepareFailure() throws Exception {
            harness.responder = request -> switch (request) {
                case Message.PrepareJob _ -> List.of(Message.Failure.failure("seed-isolation", "3 hardlinked files"));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of());

            harness.link.launch(granted).await();

            assertThat(harness.units.completions.getFirst().finish().errorReason()).isEqualTo(some("seed-isolation: 3 hardlinked files"));
            assertThat(harness.lastWorker.count(Message.StartUnit.class)).isZero();
        }

        /// The session is reset before the launch settles, not some time after it: the reset is a chain step. The
        /// failing completion write answers late, on a timer thread, so the chain is unresolved when the reset is
        /// attached; the launch's own continuation reads the link, which an `on*` callback would leave UP.
        @Test
        void launch_resetsTheSessionBeforeItSettles_whenAWorkerFailureCannotBeRecorded() throws Exception {
            harness.responder = request -> switch (request) {
                case Message.PrepareJob _ -> List.of(Message.Failure.failure("seed-isolation", "3 hardlinked files"));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of());
            harness.units.failCompletes.set(1);
            harness.units.completeDelayMs = 100;

            var upWhenSettled = harness.link.launch(granted)
                                            .fold(_ -> Promise.success(harness.link.isUp()));

            assertThat(upWhenSettled.await()
                                    .or(true)).isFalse();
            assertThat(harness.units.completions).isEmpty();
        }

        @Test
        void launch_leavesGrantedAndResetsLink_startTimesOut() throws Exception {
            harness.responder = request -> switch (request) {
                case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
                case Message.StartUnit _ -> List.of();
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of());

            harness.link.launch(granted).await();

            assertThat(harness.units.completions).isEmpty();
            assertThat(harness.units.started).isEmpty();
            assertThat(harness.nextWorker()).isNotNull();
        }
    }

    @Nested
    class Heartbeats {
        @Test
        void heartbeat_refreshesLeasesInTheAttemptsItNames_andFacts() throws Exception {
            harness.units.activeOn(LinkHarness.HOST)
                         .add(ActiveUnit.activeUnit(ref(9), 9, UnitState.RUNNING, 6144, TIMEOUT, none(), some(Instant.now()), false,
                                                    none(), none(), UnitKind.MVN, some(Instant.now()), 2));
            connectUp(List.of(DiskUnitDto.diskUnitDto(9, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), none())));

            harness.lastWorker.send(new Message.Heartbeat(List.of(new UnitRefDto(9, "main", 1), new UnitRefDto(10, "main", 1)),
                                                          LinkHarness.facts(3)));

            waitFor(() -> !harness.units.leaseRefreshes.isEmpty(), "lease refresh");
            assertThat(harness.units.leaseRefreshes.getFirst()).as("protocol v3: the frame's attempts; the store matches no row for a stale one")
                                                              .containsExactly(AttemptRef.attemptRef(ref(9), 1),
                                                                               AttemptRef.attemptRef(ref(10), 1));
            waitFor(() -> harness.link.facts().map(facts -> facts.foreignReactors() == 3).or(false), "facts");
        }

        /// m4-design §4.3: the store read named attempt 1 current, then the unit was requeued and granted to this same
        /// host again (attempt 2, known here). Attempt 1's report is superseded, and attempt 2 stays known: its lease is
        /// still refreshed.
        @Test
        void supersededReport_ofAnOlderAttempt_leavesTheNewerAttemptsLease() throws Exception {
            harness.units.activeOn(LinkHarness.HOST)
                         .add(ActiveUnit.activeUnit(ref(9), 9, UnitState.RUNNING, 6144, TIMEOUT, none(), some(Instant.now()), false,
                                                    none(), none(), UnitKind.MVN, some(Instant.now()), 2));
            connectUp(List.of(DiskUnitDto.diskUnitDto(9, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(2))));
            harness.units.activeOn(LinkHarness.HOST)
                         .set(0, ActiveUnit.activeUnit(ref(9), 9, UnitState.RUNNING, 6144, TIMEOUT, none(), some(Instant.now()), false,
                                                       none(), none(), UnitKind.MVN, some(Instant.now()), 1));
            harness.units.completeAnswer = new CompleteOutcome.Superseded(ref(9), UnitState.RUNNING, 1, 2);

            harness.lastWorker.send(exited(9, 0));
            harness.lastWorker.send(new Message.UnitReport(9, "main", 1, 0, true, List.of()));
            harness.lastWorker.expect(Message.UnitReportAck.class, 5);
            harness.lastWorker.send(new Message.Heartbeat(List.of(new UnitRefDto(9, "main", 2)), LinkHarness.facts(0)));

            waitFor(() -> !harness.units.leaseRefreshes.isEmpty(), "lease refresh");
            assertThat(harness.units.leaseRefreshes.getFirst()).containsExactly(AttemptRef.attemptRef(ref(9), 2));
        }
    }

    /// m4-design §4.5 (rule 0 and entry criterion (c)) and §4.6 (frames of a superseded attempt), at the link.
    @Nested
    class Fencing {
        private static DiskUnitDto running(long job, int attempt) {
            return DiskUnitDto.diskUnitDto(job, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(attempt));
        }

        private static Message staleAnswer(Message request) {
            return Message.Failure.failure(ReconcileRun.STALE_ATTEMPT, "Unit temper-x-main holds attempt 1, not attempt 2");
        }

        /// A failed discard fails the reconcile: no other action runs and the host never goes UP on that session.
        @Test
        void failedDiscard_failsTheReconcile_beforeAnyOtherAction() throws Exception {
            activeIn(4, UnitState.GRANTED, false);
            harness.responder = request -> switch (request) {
                case Message.DiscardUnit _ -> List.of(Message.Failure.failure("discard", "docker rm failed"));
                default -> LinkHarness.ackEverything(request);
            };
            harness.link.start();
            harness.nextWorker()
                   .send(hello(LinkHarness.VERSION,
                               List.of(DiskUnitDto.diskUnitDto(4, "main", true, some(0), ContainerStatus.EXITED, none(), none(), none(), some(3)))));

            assertThat(harness.nextWorker(4)).as("reconnect after the failed discard").isNotNull();
            assertThat(harness.hosts.states).doesNotContain("UP");
            assertThat(harness.units.requeued).as("the current attempt is not settled behind a failed discard").isEmpty();
            assertThat(harness.units.completions).isEmpty();
        }

        /// Criterion (c), `Collect`: the scan showed the current attempt, the worker now holds another. The current
        /// attempt never ran here, so it is requeued (never `ERROR(infra)`), and the host goes UP.
        @Test
        void staleAttemptAnswer_toCollect_requeuesTheUnstartedCurrentAttempt_andGoesUp() throws Exception {
            activeIn(2, UnitState.GRANTED, false);
            harness.responder = request -> switch (request) {
                case Message.Collect collect -> List.of(staleAnswer(collect));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of(DiskUnitDto.diskUnitDto(2, "main", true, some(0), ContainerStatus.EXITED, none(), none(), none(), some(2))));

            assertThat(harness.units.requeued).containsExactly(ref(2));
            assertThat(harness.units.requeuedAttempts).containsExactly(2);
            assertThat(harness.units.completions).isEmpty();
        }

        /// Criterion (c), `Collect` of a started attempt: its outcome is gone from this host, `ERROR(lost)`.
        @Test
        void staleAttemptAnswer_toCollect_ofAStartedAttempt_endsItLost() throws Exception {
            activeIn(2, UnitState.RUNNING, true);
            harness.responder = request -> switch (request) {
                case Message.Collect collect -> List.of(staleAnswer(collect));
                default -> LinkHarness.ackEverything(request);
            };
            connectUp(List.of(DiskUnitDto.diskUnitDto(2, "main", true, some(0), ContainerStatus.EXITED, none(), none(), none(), some(2))));

            assertThat(harness.units.completions).hasSize(1);
            assertThat(harness.units.completions.getFirst().finish().errorReason()).isEqualTo(some(UnitFinishes.LOST));
            assertThat(harness.units.completions.getFirst().attempt()).isEqualTo(2);
        }

        /// Criterion (c), `Reattach`: the attempt the host holds keeps running with no timer. The current attempt is
        /// settled, then the reconcile fails, so the reconnect's scan discards the running one before the host is UP.
        @Test
        void staleAttemptAnswer_toReattach_settlesTheCurrentAttempt_thenReconnectsWithoutGoingUp() throws Exception {
            activeIn(5, UnitState.RUNNING, true);
            harness.responder = request -> switch (request) {
                case Message.Reattach reattach -> List.of(staleAnswer(reattach));
                default -> LinkHarness.ackEverything(request);
            };
            harness.link.start();
            harness.nextWorker()
                   .send(hello(LinkHarness.VERSION,
                               List.of(DiskUnitDto.diskUnitDto(5, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(5)))));

            assertThat(harness.nextWorker(4)).as("reconnect after the stale re-attach").isNotNull();
            assertThat(harness.hosts.states).doesNotContain("UP");
            assertThat(harness.units.completions).hasSize(1);
            assertThat(harness.units.completions.getFirst().finish().errorReason()).isEqualTo(some(UnitFinishes.LOST));
        }

        /// A CANCELLING unit whose container runs gets `Reattach` and `Cancel`; both answered `stale-attempt` settle the
        /// unit once, `ERROR(lost)`, never `ERROR(infra)` (the `Cancel`'s answer adds nothing), and the session reconnects.
        /// Probe: settle a stale `Cancel` as any worker failure → an `ERROR(infra)` completion (red).
        @Test
        void staleAttemptAnswers_toReattachAndCancel_neverSettleErrorInfra() throws Exception {
            active(8, UnitState.CANCELLING, true);
            harness.responder = request -> switch (request) {
                case Message.Reattach reattach -> List.of(staleAnswer(reattach));
                case Message.CancelUnit cancel -> List.of(staleAnswer(cancel));
                default -> LinkHarness.ackEverything(request);
            };
            harness.link.start();
            harness.nextWorker()
                   .send(hello(LinkHarness.VERSION,
                               List.of(DiskUnitDto.diskUnitDto(8, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), some(1)))));

            assertThat(harness.nextWorker(4)).as("reconnect after the stale re-attach").isNotNull();
            assertThat(harness.units.completions).isNotEmpty()
                                                 .allSatisfy(completion -> assertThat(completion.finish().errorReason()).isEqualTo(some(UnitFinishes.LOST)));
        }

        /// MEDIUM 2 (WP-4b review): a late `UnitStarted` of attempt 1, while attempt 2 is the one known here and the store
        /// refuses start writes, touches nothing kept per unit: attempt 2's unrecorded entry, start in flight and UNKNOWN
        /// mark stay attempt 2's. Probe: drop the attempt check in `recordStart` → `unrecordedStarts` names attempt 1 (red).
        @Test
        void lateStartOfASupersededAttempt_leavesTheCurrentAttemptsStartState() throws Exception {
            harness.units.activeOn(LinkHarness.HOST)
                         .add(ActiveUnit.activeUnit(ref(6), 6, UnitState.GRANTED, 6144, TIMEOUT, none(), none(), false,
                                                    none(), none(), UnitKind.MVN, some(Instant.now()), 2));
            connectUp(List.of(running(6, 2)));
            harness.units.failStarts.set(Integer.MAX_VALUE);
            harness.units.failUnknowns.set(Integer.MAX_VALUE);
            waitFor(() -> harness.link.unrecordedStarts().contains(UnitAttempt.unitAttempt(ref(6), 2)), "attempt 2 known unrecorded");

            harness.lastWorker.send(new Message.UnitStarted(6, "main", 1, "c6", "sha256:img", 1_700_000_000_000_000_000L,
                                                            Instant.now(), LinkHarness.facts(0), none()));
            Thread.sleep(1500);

            assertThat(harness.units.started).as("never written").isEmpty();
            assertThat(harness.link.unrecordedStarts()).containsExactly(UnitAttempt.unitAttempt(ref(6), 2));
            assertThat(harness.units.unknownGrants).as("every UNKNOWN mark in attempt 2").containsOnly(2);
        }

        /// An unsolicited `UnitStarted` of a unit not active here, one active on another host (job 42) and one queued
        /// again (job 43), is only logged: no start written. Probe: write the start in `recordedStart`'s
        /// `Elsewhere`/`Other` branch → a recorded start (red).
        @Test
        void unsolicitedStart_ofAUnitActiveElsewhereOrQueued_writesNothing() throws Throwable {
            var logged = ignoredFrames(() -> {
                harness.lastWorker.send(new Message.UnitStarted(42, "main", 1, "c42", "sha256:img", 1_700_000_000_000_000_000L,
                                                                Instant.now(), LinkHarness.facts(0), none()));
                harness.lastWorker.send(new Message.UnitStarted(43, "main", 1, "c43", "sha256:img", 1_700_000_000_000_000_000L,
                                                                Instant.now(), LinkHarness.facts(0), none()));
            });

            assertThat(logged).containsExactlyInAnyOrder("UnitStarted of temper-42-main attempt 1 ignored: the unit is not active on this host (Elsewhere[attempt=1])",
                                                         "UnitStarted of temper-43-main attempt 1 ignored: the unit is not active on this host (QUEUED)");
            assertThat(harness.units.started).as("never written").isEmpty();
        }

        /// An unsolicited `UnitStartFailed` in the same two cases is only logged: nothing completed or requeued, the
        /// session kept. Probe: complete the unit in `endedAtStart`'s `Elsewhere`/`Other` branch → a completion (red).
        @Test
        void unsolicitedStartFailed_ofAUnitActiveElsewhereOrQueued_writesNothing() throws Throwable {
            var logged = ignoredFrames(() -> {
                harness.lastWorker.send(new Message.UnitStartFailed(42, "main", 1, StartFailureKind.VOID, "disk", none()));
                harness.lastWorker.send(new Message.UnitStartFailed(43, "main", 1, StartFailureKind.VOID, "disk", none()));
            });

            assertThat(logged).containsExactlyInAnyOrder("UnitStartFailed of temper-42-main attempt 1 ignored: the unit is not active on this host (Elsewhere[attempt=1])",
                                                         "UnitStartFailed of temper-43-main attempt 1 ignored: the unit is not active on this host (QUEUED)");
            assertThat(harness.units.completions).as("never written").isEmpty();
            assertThat(harness.units.requeued).isEmpty();
            assertThat(harness.workers).as("no reconnect").isEmpty();
        }

        /// Job 42's unit runs on host `other` in attempt 1, job 43's is queued again; neither is active here. Sends the
        /// frames over an UP link and returns the "not active" lines `WorkerEvents` logs for them.
        private List<String> ignoredFrames(Executable send) throws Throwable {
            harness.jobs.rows.put(JobId.jobId(42).unwrap(),
                                  Rows.job(42, JobState.RUNNING, List.of(Rows.named("main", some(HostName.hostName("other").unwrap())))));
            harness.jobs.rows.put(JobId.jobId(43).unwrap(),
                                  Rows.job(43, JobState.QUEUED, List.of(Rows.unit(1, UnitState.QUEUED))));
            var logged = new ListAppender<ILoggingEvent>();
            var logger = (Logger) LoggerFactory.getLogger(WorkerEvents.class);
            logged.start();
            logger.addAppender(logged);
            try {
                connectUp(List.of());
                send.execute();
                waitFor(() -> notActiveLines(logged).size() == 2, "both frames ignored");
                Thread.sleep(300);
                return notActiveLines(logged);
            } finally {
                logger.detachAppender(logged);
            }
        }

        private static List<String> notActiveLines(ListAppender<ILoggingEvent> logged) {
            return List.copyOf(logged.list)
                       .stream()
                       .map(ILoggingEvent::getFormattedMessage)
                       .filter(line -> line.contains("not active on this host"))
                       .map(line -> line.substring(line.indexOf("] ") + 2))
                       .toList();
        }

        /// A report whose attempt equals the current attempt of the unit on another host (no grant gives two hosts one
        /// attempt; the guard is the host): acked in its attempt as superseded, nothing pulled or written.
        @Test
        void reportInTheAttemptCurrentOnAnotherHost_isSuperseded() throws Exception {
            harness.jobs.rows.put(JobId.jobId(41).unwrap(),
                                  Rows.job(41, JobState.RUNNING, List.of(Rows.named("main", some(HostName.hostName("other").unwrap())))));
            connectUp(List.of());

            harness.lastWorker.send(exitedIn(41, 1));
            harness.lastWorker.send(new Message.UnitReport(41, "main", 1, 0, true, List.of()));

            assertThat(harness.lastWorker.expect(Message.UnitReportAck.class, 5)
                                         .attempt()).isEqualTo(1);
            assertThat(harness.units.completions).isEmpty();
            assertThat(harness.pulls).isEmpty();
        }

        /// Entry criterion (b): a report of attempt 1 while the store has attempt 2 active here is acked in attempt 1 and
        /// discarded: no evidence pulled, nothing completed.
        @Test
        void reportOfASupersededAttempt_isAckedInItsOwnAttempt_andNothingIsPulledOrWritten() throws Exception {
            harness.units.activeOn(LinkHarness.HOST)
                         .add(ActiveUnit.activeUnit(ref(6), 6, UnitState.RUNNING, 6144, TIMEOUT, none(), some(Instant.now()), false,
                                                    none(), none(), UnitKind.MVN, some(Instant.now()), 2));
            connectUp(List.of(running(6, 2)));

            harness.lastWorker.send(exitedIn(6, 1));
            harness.lastWorker.send(new Message.UnitReport(6, "main", 1, 0, true, List.of()));

            assertThat(harness.lastWorker.expect(Message.UnitReportAck.class, 5)
                                         .attempt()).isEqualTo(1);
            assertThat(harness.units.completions).isEmpty();
            assertThat(harness.pulls).isEmpty();
        }

        /// Entry criterion (a): the write takes the attempt the frame carries. A report of attempt 2 completes attempt 2.
        @Test
        void reportOfTheCurrentAttempt_completesInTheFramesAttempt() throws Exception {
            harness.units.activeOn(LinkHarness.HOST)
                         .add(ActiveUnit.activeUnit(ref(6), 6, UnitState.RUNNING, 6144, TIMEOUT, none(), some(Instant.now()), false,
                                                    none(), none(), UnitKind.MVN, some(Instant.now()), 2));
            connectUp(List.of(running(6, 2)));

            harness.lastWorker.send(exitedIn(6, 2));
            harness.lastWorker.send(new Message.UnitReport(6, "main", 2, 0, true, List.of()));

            assertThat(harness.lastWorker.expect(Message.UnitReportAck.class, 5)
                                         .attempt()).isEqualTo(2);
            assertThat(harness.units.completions).hasSize(1);
            assertThat(harness.units.completions.getFirst().attempt()).isEqualTo(2);
        }

        /// §4.6: a late `UnitStarted` of attempt 1 is dropped at the link (attempt 2 is the one known here), and a late
        /// `Failure{lost}` of attempt 1 is written in attempt 1, which the store's guard drops (`StaleAttemptStoreIT`);
        /// entry criterion (a): neither is ever written in attempt 2.
        @Test
        void lateStartAndLostFailure_ofASupersededAttempt_areScopedToTheirOwnAttempt() throws Exception {
            harness.units.activeOn(LinkHarness.HOST)
                         .add(ActiveUnit.activeUnit(ref(6), 6, UnitState.RUNNING, 6144, TIMEOUT, none(), some(Instant.now()), false,
                                                    none(), none(), UnitKind.MVN, some(Instant.now()), 2));
            connectUp(List.of(running(6, 2)));

            harness.lastWorker.send(new Message.UnitStarted(6, "main", 1, "c6", "sha256:img", 1_700_000_000_000_000_000L,
                                                            Instant.now(), LinkHarness.facts(0), none()));
            harness.lastWorker.send(new Message.UnitStarted(6, "main", 2, "c6", "sha256:img", 1_700_000_000_000_000_000L,
                                                            Instant.now(), LinkHarness.facts(0), none()));

            waitFor(() -> !harness.units.started.isEmpty(), "attempt 2's start written");
            // The fake store does not guard attempts: the lost failure goes last, so its completion cannot hide the unit
            // from attempt 2's start.
            harness.lastWorker.send(new Message.Failure(UnitFinishes.LOST, "vanished", some(new UnitRefDto(6, "main", 1))));
            waitFor(() -> !harness.units.completions.isEmpty(), "the lost failure written");
            Thread.sleep(300);
            assertThat(harness.units.startedAttempts).as("attempt 1's start dropped at the link, attempt 2's in its own")
                                                    .containsExactly(2);
            assertThat(harness.units.completions).singleElement()
                                                 .satisfies(completion -> assertThat(completion.attempt()).isEqualTo(1));
        }
    }

    private static Message.UnitExited exited(long job, int rc) {
        return new Message.UnitExited(job, "main", 1, rc, some(KilledBy.TIMEOUT), Instant.now(),
                                      new UnitEndFactsDto(LinkHarness.facts(0), none(), Instant.now(), Map.of(), TcLeftoversDto.tcLeftoversDto(TcLeftovers.tcLeftovers(List.of(), none())), none()),
                                      1, 2, 3, none(), none());
    }

    private static Message.UnitExited exitedIn(long job, int attempt) {
        return new Message.UnitExited(job, "main", attempt, 0, none(), Instant.now(),
                                      new UnitEndFactsDto(LinkHarness.facts(0), none(), Instant.now(), Map.of(), TcLeftoversDto.tcLeftoversDto(TcLeftovers.tcLeftovers(List.of(), none())), none()),
                                      1, 2, 3, none(), none());
    }

    private static TestcaseDto row(String className, String outcome) {
        return new TestcaseDto(className, "t", outcome, 0.1, "main", false);
    }

    /// The jar's sha256 computed here, independently of [WorkerDeploy#sha256].
    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                                .digest(bytes));
    }
}
