package org.pragmatica.temper.worker.gc;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.unit.UnitFiles;
import org.pragmatica.temper.worker.unit.UnitRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// Host checkout GC (m1-design §4.3): a job directory goes only when no unit is known here, no container carries
/// the job's label, and every unit has an `rc` or never ran; deletion never follows a symlink, and a tree the
/// worker cannot fully delete is kept whole.
class JobGcTest {
    private static final List<String> PS_JOB_7 = List.of("docker", "ps", "-a", "-q", "--no-trunc", "--filter", "label=temper.job=7");

    @TempDir
    Path home;

    private WorkerPaths paths;
    private UnitFiles files;
    private final FakeRunner runner = new FakeRunner();
    private final UnitRegistry registry = UnitRegistry.unitRegistry();
    private final UnitRef unit = Fixtures.ref(7, "main");

    @BeforeEach
    void setUp() throws Exception {
        paths = WorkerPaths.workerPaths(home.resolve(".temper"));
        files = UnitFiles.unitFiles(paths);
        ok(files.store(unit, UnitFile.MARKER, "1"));
        ok(files.store(unit, UnitFile.RC, "0"));
        Files.createDirectories(paths.workspace(unit.job())
                                     .resolve("core/target"));
        Files.writeString(paths.workspace(unit.job())
                               .resolve("core/target/a.jar"), "jar");
    }

    private Message.GcDone gc(long... jobs) {
        var docker = DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings());
        var sweep = NetworkSweep.networkSweep(docker,
                                              Fixtures.noHolders(paths, files),
                                              InstantSource.system(),
                                              NetworkSweep.MIN_AGE);

        return ok(JobGc.jobGc(paths, files, docker, registry, sweep)
                       .collect(new Message.GcJobs(Arrays.stream(jobs)
                                                                   .boxed()
                                                                   .toList()))
                       .await());
    }

    private Path jobDir() {
        return paths.jobDir(unit.job());
    }

    @Test
    void aFinishedJob_isDeleted_andAMissingOneCountsAsDeleted() {
        var done = gc(7, 8);

        assertThat(done.deleted()).containsExactlyInAnyOrder(7L, 8L);
        assertThat(done.kept()).isEmpty();
        assertThat(jobDir()).doesNotExist();
    }

    /// An orphan network is reclaimed when its job's checkout goes, and not before: a `GcJobs` that deletes nothing
    /// leaves the networks to the worker-start sweep.
    @Test
    void aDeletedJob_sweepsOrphanNetworks_aKeptJobDoesNot() {
        registry.startWatching(unit, 1, "cid");
        gc(7);

        assertThat(runner.calls()).noneMatch(JobGcTest::isNetworkList);

        registry.stopWatching(unit);
        gc(7);

        assertThat(runner.calls()).anyMatch(JobGcTest::isNetworkList);
    }

    private static boolean isNetworkList(List<String> argv) {
        return argv.size() > 2 && argv.get(1).equals("network") && argv.get(2).equals("ls");
    }

    @Test
    void aUnitStillKnownToTheWorker_keepsTheJob() {
        registry.startWatching(unit, 1, "cid");

        var done = gc(7);

        assertThat(done.deleted()).isEmpty();
        assertThat(done.kept()).singleElement()
                               .satisfies(kept -> assertThat(kept.reason()).contains("still active"));
        assertThat(jobDir()).exists();
    }

    /// An owner tag outliving its unit's entries (a failed collection) neither keeps the job nor survives its GC.
    @Test
    void aLeftoverOwnerTag_keepsNothing_andGoesWithTheJob() {
        registry.owners()
                .put(unit, 1);

        var done = gc(7);

        assertThat(done.deleted()).containsExactly(7L);
        assertThat(registry.owners()).isEmpty();
    }

    @Test
    void aContainerLabelledWithTheJob_keepsIt() {
        runner.on(PS_JOB_7, 0, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n", "");

        assertThat(gc(7).kept()).singleElement()
                                .satisfies(kept -> assertThat(kept.reason()).contains("labelled temper.job=7"));
        assertThat(jobDir()).exists();
    }

    @Test
    void aUnitWithAMarkerButNoRc_keepsIt_whileAUnitThatNeverRanDoesNot() throws Exception {
        var other = Fixtures.ref(7, "other");

        ok(files.store(other, UnitFile.MARKER, "1"));

        assertThat(gc(7).kept()).singleElement()
                                .satisfies(kept -> assertThat(kept.reason()).isEqualTo("unit other has a marker and no rc"));

        Files.delete(files.path(other, UnitFile.MARKER));

        assertThat(gc(7).deleted()).containsExactly(7L);
    }

    /// A symlink is removed as a link: what it points at, outside the job, survives.
    @Test
    void deletion_neverFollowsASymlink() throws Exception {
        var outside = Files.createDirectories(home.resolve("outside"));

        Files.writeString(outside.resolve("keep.txt"), "precious");
        Files.createSymbolicLink(paths.workspace(unit.job())
                                      .resolve("linked-dir"), outside);
        Files.createSymbolicLink(paths.workspace(unit.job())
                                      .resolve("linked-file"), outside.resolve("keep.txt"));

        assertThat(gc(7).deleted()).containsExactly(7L);
        assertThat(jobDir()).doesNotExist();
        assertThat(outside.resolve("keep.txt")).hasContent("precious");
    }

    /// A directory the worker cannot empty (root-owned, left by a sibling container) keeps the whole job:
    /// nothing is half-deleted.
    @Test
    void anUndeletableDirectory_keepsTheWholeJob() throws Exception {
        var locked = paths.workspace(unit.job())
                          .resolve("core/target");

        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            var done = gc(7);

            assertThat(done.deleted()).isEmpty();
            assertThat(done.kept()).singleElement()
                                   .satisfies(kept -> assertThat(kept.reason()).startsWith("cannot delete ")
                                                                               .contains("core/target")
                                                                               .endsWith("not writable"));
            assertThat(files.path(unit, UnitFile.RC)).exists();
            assertThat(locked.resolve("a.jar")).exists();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aFailedCheck_keepsTheJob_withTheReason() {
        runner.on(PS_JOB_7, 1, "", "Cannot connect to the Docker daemon");

        assertThat(gc(7).kept()).singleElement()
                                .satisfies(kept -> assertThat(kept.reason()).startsWith("check failed: "));
        assertThat(jobDir()).exists();
    }

    /// A held unit (its container runs under a granted hold) is a unit the worker still knows.
    @Test
    void aHeldUnit_keepsTheJob() {
        registry.hold(unit, "cid");

        assertThat(gc(7).kept()).singleElement()
                                .satisfies(kept -> assertThat(kept.reason()).contains("still active"));
        assertThat(jobDir()).exists();
    }

    /// A tree the worker cannot walk (a directory it may enter and write but not list) fails the check: the job is
    /// kept with the walk's failure as the reason, and nothing is deleted.
    @Test
    void anUnwalkableTree_keepsTheJob_withTheFailure() throws Exception {
        var unlisted = paths.workspace(unit.job())
                            .resolve("core/target");

        Files.setPosixFilePermissions(unlisted, PosixFilePermissions.fromString("-wx------"));
        try {
            assertThat(gc(7).kept()).singleElement()
                                    .satisfies(kept -> assertThat(kept.reason()).startsWith("check failed: ")
                                                                                .contains("walk"));
        } finally {
            Files.setPosixFilePermissions(unlisted, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertThat(unlisted.resolve("a.jar")).exists();
    }

    /// At most `PARALLELISM` jobs are checked at once, and every job of a long request is answered.
    @Test
    void manyJobs_areCheckedInBoundedBatches() {
        var current = new AtomicInteger();
        var peak = new AtomicInteger();

        runner.on(List.of("docker", "ps", "-a"), _ -> countedPs(current, peak));

        var jobs = LongStream.rangeClosed(100, 111)
                             .toArray();

        assertThat(gc(jobs).deleted()).hasSize(jobs.length);
        assertThat(peak.get()).isBetween(1, JobGc.PARALLELISM);
    }

    private static Result<Completed> countedPs(AtomicInteger current, AtomicInteger peak) {
        peak.accumulateAndGet(current.incrementAndGet(), Math::max);
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        current.decrementAndGet();
        return Result.success(FakeRunner.completed(0, "", ""));
    }

    /// A symlink among the unit directories is no unit: it is neither checked (its target's marker does not keep
    /// the job) nor followed when the job is deleted.
    @Test
    void aSymlinkAmongTheUnits_isNoUnit() throws Exception {
        var outside = Files.createDirectories(home.resolve("outside/.temper"));

        Files.writeString(outside.resolve("marker"), "1");
        Files.createSymbolicLink(paths.unitsDir(unit.job())
                                      .resolve("linked"), home.resolve("outside"));

        assertThat(gc(7).deleted()).containsExactly(7L);
        assertThat(outside.resolve("marker")).exists();
    }

    /// A file that cannot be deleted although its directory is writable (immutable, macOS `uchg`) stops the delete
    /// midway: the job is kept as "partially deleted", never reported deleted.
    @Test
    void aDeleteFailingMidway_keepsTheJob_asPartiallyDeleted() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name")
                                     .startsWith("Mac"));
        var pinned = paths.workspace(unit.job())
                          .resolve("core/target/a.jar");

        assertThat(HermeticProcess.builder("chflags", "uchg", pinned.toString()).start()
                                                                            .waitFor()).isZero();
        try {
            assertThat(gc(7).kept()).singleElement()
                                    .satisfies(kept -> assertThat(kept.reason()).startsWith("partially deleted: "));
        } finally {
            HermeticProcess.builder("chflags", "nouchg", pinned.toString()).start()
                                                                      .waitFor();
        }
    }
}
