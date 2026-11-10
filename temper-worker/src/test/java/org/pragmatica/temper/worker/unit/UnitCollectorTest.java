package org.pragmatica.temper.worker.unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.worker.manifest.ManifestFixtures;
import org.pragmatica.temper.domain.manifest.Reach;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.protocol.message.ManifestOutcomeDto;
import org.pragmatica.temper.protocol.message.VoidReason;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.KilledBy;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.manifest.ManifestCollector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The exit path (m0-design §7.4 "On container exit"): rc first, log, reports newer than the marker text,
/// end facts with the last cgroup sample, `UnitExited` + chunks; and the idempotent resend after a lost ack.
class UnitCollectorTest {
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final long MARKER = 1_760_356_800_000_000_000L;
    private static final String CONTAINER = "c".repeat(64);
    private static final String GOOD = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="a.B" tests="1"><testcase classname="a.B" name="one" time="0.1"/>\
        <testcase classname="a.B" name="two" time="0.2"><failure message="x"/></testcase></testsuite>
        """;

    @TempDir
    Path home;

    private FakeRunner runner;
    private WorkerPaths paths;
    private UnitFiles files;
    private CgroupSampler cgroups;
    private final UnitRef ref = Fixtures.ref(7, "main");

    @BeforeEach
    void setUp() throws IOException {
        runner = new FakeRunner();
        paths = WorkerPaths.workerPaths(home.resolve(".temper"));
        files = UnitFiles.unitFiles(paths);
        cgroups = CgroupSampler.cgroupSampler(home.resolve("cgroup"), new Fixtures.ManualClock(NOW.minusSeconds(5)));
        ok(files.store(ref, UnitFile.MARKER, Long.toString(MARKER)));
        ok(files.store(ref, UnitFile.CONTAINER, CONTAINER));
        runner.on(List.of("docker", "logs", CONTAINER), 0, "line1\nline2\n", "[temper] step 1/1\n")
              .on(List.of("docker", "ps", "-a"), 0, "", "")
              .on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.ExitCode}}", CONTAINER), 0, "3\n", "");
    }

    private UnitCollector collector() {
        return collector(new Fixtures.ManualClock(NOW));
    }

    private UnitCollector collector(InstantSource reapClock) {
        return collector(reapClock, TimeSpan.timeSpan(200).millis(), TimeSpan.timeSpan(200).millis());
    }

    private UnitCollector collector(InstantSource reapClock, TimeSpan reapWait, TimeSpan voidedReapWait) {
        var clock = new Fixtures.ManualClock(NOW);
        var docker = DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings());

        return UnitCollector.unitCollector(paths,
                                           files,
                                           docker,
                                           ReportCollector.reportCollector(paths, files),
                                           TestcontainersReap.testcontainersReap(docker,
                                                                                 files,
                                                                                 reapWait,
                                                                                 voidedReapWait,
                                                                                 TimeSpan.timeSpan(20).millis(),
                                                                                 reapClock),
                                           () -> Promise.success(HostFacts.hostFacts(1, 1, 4, 0, 1, none(), none(), 10, 20, 30, NOW, List.of())),
                                           cgroups,
                                           ManifestCollector.manifestCollector(paths, files, TemperJson.mapper()),
                                           TemperJson.mapper(),
                                           clock);
    }

    private Path report(String module, String name, String xml, long mtimeNanos) throws IOException {
        var file = paths.workspace(ref.job())
                        .resolve(module)
                        .resolve("target/surefire-reports")
                        .resolve(name);

        Files.createDirectories(file.getParent());
        Files.writeString(file, xml);
        Files.setLastModifiedTime(file, FileTime.from(EpochNanos.instant(mtimeNanos)));
        return file;
    }

    private void cgroupSample() throws IOException {
        var scope = home.resolve("cgroup/system.slice/docker-" + CONTAINER + ".scope");

        Files.createDirectories(scope);
        Files.writeString(scope.resolve("memory.peak"), "1048576\n");
        Files.writeString(scope.resolve("cpu.stat"), "usage_usec 5\n");
        assertThat(cgroups.sample(CONTAINER)
                          .isPresent()).isTrue();
    }

    /// §12.7: the Testcontainers containers still present after the reap wait travel in the end facts (the
    /// `UnitExited` and `facts-end.json`), not only in a worker-local file.
    @Test
    void collect_carriesTestcontainersLeftovers_inTheEndFacts() throws IOException {
        ok(files.store(ref, UnitFile.TC_SESSIONS_START, "s-old"));
        runner.on(List.of("docker", "ps", "-a"), 0, "tc-old s-old\ntc-new s-new\n", "");

        var exited = ok(collector(InstantSource.system()).collect(ref, some(0))
                                                         .await()).exited();

        assertThat(exited.endFacts()
                         .tcLeftovers()).isEqualTo(new TcLeftoversDto(List.of(new TcLeftoversDto.ContainerDto("tc-new", "s-new")),
                                                                      none()));
        assertThat(files.path(ref, UnitFile.FACTS_END)).content()
                                                       .contains("{\"id\":\"tc-new\",\"session\":\"s-new\"}")
                                                       .doesNotContain("tc-old");
    }

    @Test
    void collect_recordsTheExit_andReportsOnlyFilesNewerThanTheMarker() throws IOException {
        report("core", "TEST-a.B.xml", GOOD, MARKER + 5_000_000_000L);
        report("core", "TEST-stale.xml", GOOD, MARKER - 5_000_000_000L);
        cgroupSample();

        var collected = ok(collector().collect(ref, some(0))
                                      .await());
        var exited = collected.exited();

        assertThat(exited.rc()).isZero();
        assertThat(exited.killedBy()).isEqualTo(none());
        assertThat(exited.reportFiles()).isEqualTo(1);
        assertThat(exited.testsAttr()).isEqualTo(1);
        assertThat(exited.threadDumps()).isZero();
        assertThat(exited.errorReason()).isEqualTo(none());
        assertThat(exited.endFacts()
                         .memoryPeakBytes()).isEqualTo(some(1_048_576L));
        assertThat(exited.endFacts()
                         .memoryPeakSampledAt()).isEqualTo(NOW.minusSeconds(5));
        assertThat(collected.chunks()).singleElement()
                                      .satisfies(chunk -> assertThat(chunk.rows()).extracting(row -> row.name())
                                                                                  .containsExactly("one", "two"));
        assertThat(ok(files.text(ref, UnitFile.RC)).or("")).isEqualTo("0");
        assertThat(files.exists(ref, UnitFile.END)).isTrue();
        assertThat(paths.logFile(ref)).content()
                                      .contains("line1", "line2", "[temper] step 1/1");
        assertThat(paths.reportsDir(ref)
                        .resolve("core/target/surefire-reports/TEST-a.B.xml")).exists();
        assertThat(paths.reportsDir(ref)
                        .resolve("core/target/surefire-reports/TEST-stale.xml")).doesNotExist();
        assertThat(files.path(ref, UnitFile.FACTS_END)).content()
                                                       .contains("\"tcLeftovers\":{\"containers\":[],\"unverified\":null}", "1048576");
        assertThat(collected.messages()
                            .getFirst()).isSameAs(exited);
    }

    @Test
    void collect_resendsTheSameReport_afterALostAck() throws IOException {
        report("core", "TEST-a.B.xml", GOOD, MARKER + 5_000_000_000L);

        var first = ok(collector().collect(ref, some(0))
                                  .await());

        // The workspace changes afterwards (a later run, cleanup): the resend must not look at it.
        Files.delete(paths.workspace(ref.job())
                          .resolve("core/target/surefire-reports/TEST-a.B.xml"));

        var again = ok(collector().collect(ref, none())
                                  .await());

        assertThat(again.exited()).isEqualTo(first.exited());
        assertThat(again.chunks()).isEqualTo(first.chunks());
    }

    @Test
    void collect_takesTheRecordedRcOverANewerReading_andInspectsWhenNoneIsKnown() {
        ok(files.store(ref, UnitFile.RC, "137"));
        assertThat(ok(collector().collect(ref, some(0))
                                 .await()).exited()
                                          .rc()).isEqualTo(137);
    }

    @Test
    void collect_inspectsTheExitCode_whenNoWaitSawTheExit() {
        assertThat(ok(collector().collect(ref, none())
                                 .await()).exited()
                                          .rc()).isEqualTo(3);
    }

    @Test
    void collect_reportsKillCauseAndDumps() throws IOException {
        ok(files.store(ref, UnitFile.KILLED, "timeout"));
        Files.createDirectories(paths.threadDumpsDir(ref));
        Files.writeString(paths.threadDumpsDir(ref)
                               .resolve("1.txt"), "Full thread dump");
        Files.writeString(paths.threadDumpsDir(ref)
                               .resolve("2.err"), "failed");

        var exited = ok(collector().collect(ref, some(137))
                                   .await()).exited();

        assertThat(exited.killedBy()).isEqualTo(Option.some(KilledBy.TIMEOUT));
        assertThat(exited.threadDumps()).isEqualTo(1);
    }

    @Test
    void collect_marksReport_butKeepsTheRowsThatParsed() throws IOException {
        report("core", "TEST-a.B.xml", GOOD, MARKER + 5_000_000_000L);
        report("api", "TEST-c.D.xml", "<testsuite><testcase", MARKER + 5_000_000_000L);

        var collected = ok(collector().collect(ref, some(1))
                                      .await());

        assertThat(collected.exited()
                            .errorReason()).isEqualTo(some("report"));
        assertThat(collected.exited()
                            .reportFiles()).isEqualTo(2);
        assertThat(collected.chunks()
                            .getFirst()
                            .rows()).hasSize(2);
    }

    @Test
    void exitedJson_roundTripsAsAMessage() {
        ok(collector().collect(ref, some(0))
                      .await());

        var json = ok(files.text(ref, UnitFile.EXITED)).or("");

        assertThat(ok(TemperJson.mapper()
                                .readString(json, Message.class))).isInstanceOf(Message.UnitExited.class);
    }

    @Test
    void collect_publishesNoLog_whenDockerLogsFails_butStillReports() throws IOException {
        runner.on(List.of("docker", "logs", CONTAINER), 1, "", "Error response from daemon: No such container");

        var collected = ok(collector().collect(ref, some(0))
                                      .await());

        assertThat(collected.exited()
                            .rc()).isZero();
        assertThat(paths.logFile(ref)).doesNotExist();
    }

    /// M1 additions (m1-design §1.4, §2.5, §3.2, §4.5).
    @Nested
    class M1 {
        @Test
        void collect_reportsTheVoidReason_andIgnoresAnUnknownOne() {
            ok(files.store(ref, UnitFile.VOID, "port-group-lost"));

            assertThat(ok(collector().collect(ref, some(137))
                                     .await()).exited()
                                              .voidReason()).isEqualTo(some(VoidReason.PORT_GROUP_LOST));
            assertThat(UnitCollector.voidCause("disk")).isEqualTo(some(VoidReason.DISK));
            assertThat(UnitCollector.voidCause("flood")).isEqualTo(none());
        }

        /// A11 case 2 (m1-design §9.1): a unit `HoldMonitor` voided reports its exit within the voided reap window,
        /// not the full one, even while a Testcontainers container of its session is slow to go; it is still
        /// reported.
        @Test
        void collect_ofAVoidedUnit_reportsTheExitWithinTheVoidedReapWindow_withTheLeftovers() {
            ok(files.store(ref, UnitFile.VOID, "port-group-lost"));
            ok(files.store(ref, UnitFile.TC_SESSIONS_START, ""));
            runner.on(List.of("docker", "ps", "-a"), 0, "tc-slow s-unit\n", "");
            var started = System.nanoTime();

            var exited = ok(collector(InstantSource.system(),
                                      TimeSpan.timeSpan(5).seconds(),
                                      TimeSpan.timeSpan(200).millis()).collect(ref, some(137))
                                                                      .await()).exited();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(exited.voidReason()).isEqualTo(some(VoidReason.PORT_GROUP_LOST));
            assertThat(exited.endFacts()
                             .tcLeftovers()).isEqualTo(new TcLeftoversDto(List.of(new TcLeftoversDto.ContainerDto("tc-slow", "s-unit")),
                                                                          some(TestcontainersReap.CUT_SHORT)));
        }

        @Test
        void collect_carriesTheLowestSampledMemAvailable() {
            ok(files.store(ref, UnitFile.MEM_AVAILABLE_MIN, "1234"));

            assertThat(ok(collector().collect(ref, some(0))
                                     .await()).exited()
                                              .endFacts()
                                              .memAvailableMinKb()).isEqualTo(some(1234L));
        }

        /// `log.tail` is the last 1 MiB of `log`, so the small evidence set carries the end of the build log.
        @Test
        void collect_writesTheLogTail_asTheLastMebibyteOfTheLog() throws IOException {
            var head = "h".repeat(600 * 1024) + "\n";
            var tail = "t".repeat(1024 * 1024 - 1) + "\n";

            runner.on(List.of("docker", "logs", CONTAINER), 0, head + tail, "");
            ok(collector().collect(ref, some(0))
                          .await());

            assertThat(Files.size(paths.logTail(ref))).isEqualTo(LogFiles.TAIL_FILE_BYTES);
            assertThat(Files.readString(paths.logTail(ref))).isEqualTo(tail);
        }

        /// The manifest travels between `UnitExited` and the report chunks, and is resent unchanged after a lost ack.
        @Test
        void collect_sendsTheManifest_betweenTheExitAndTheReports_andResendsIt() throws IOException {
            var workspace = paths.workspace(ref.job());
            var testClasses = Files.createDirectories(workspace.resolve("core/target/test-classes/org/acme"));

            Files.write(testClasses.resolve("FooTest.class"),
                        ManifestFixtures.classBytes("org.acme.FooTest", "java.lang.Object", false, "test"));
            ok(UnitFiles.storeAtomic(paths.effectivePom(ref),
                                     "<projects>" + ManifestFixtures.project(workspace, "core") + "</projects>"));
            ok(files.store(ref,
                           UnitFile.MANIFEST_SPEC,
                           ok(TemperJson.mapper()
                                        .writeAsString(ManifestSpecDto.manifestSpecDto(ManifestSpec.manifestSpec(List.of("mvn"),
                                                                                                                 Reach.TEST,
                                                                                                                 Map.of(),
                                                                                                                 none(),
                                                                                                                 none()))))));
            report("core", "TEST-a.B.xml", GOOD, MARKER + 5_000_000_000L);

            var collected = ok(collector().collect(ref, some(0))
                                          .await());

            assertThat(collected.messages()).extracting(message -> message.getClass()
                                                                         .getSimpleName())
                                            .containsExactly("UnitExited", "UnitManifest", "UnitReport");
            assertThat(collected.manifest()).singleElement()
                                            .satisfies(chunk -> assertThat(chunk.outcome()).isInstanceOfSatisfying(ManifestOutcomeDto.Computed.class,
                                                                                                                   computed -> assertThat(computed.entries()).containsExactly(new ManifestEntryDto("core",
                                                                                                                                                                                                   "org.acme.FooTest",
                                                                                                                                                                                                   "surefire"))));
            assertThat(paths.manifestFile(ref)).exists();

            var again = ok(collector().collect(ref, none())
                                      .await());

            assertThat(again.manifest()).isEqualTo(collected.manifest());
        }

        @Test
        void collect_ofAUnitWithoutAManifestSpec_sendsNoManifest() {
            assertThat(ok(collector().collect(ref, some(0))
                                     .await()).manifest()).isEmpty();
        }
    }
}
