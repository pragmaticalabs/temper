package org.pragmatica.temper.worker.unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.ProcFixtures;
import org.pragmatica.temper.worker.config.UnitFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// `BbHoldLauncher` (m1-design §1.2): argv only (`setsid`, no shell), its own session, stdout and stderr to the
/// unit's files, and `bbhold.pid` = `<pid> <startTicks>` for the identity checks. The fake runner's spawned pid is 1.
class BbHoldLauncherTest {
    @TempDir
    Path home;

    private HoldFixture fixture;
    private Path proc;
    private final UnitRef ref = Fixtures.ref(42, "gate");
    private final List<Long> signalled = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        fixture = new HoldFixture(home);
        proc = home.resolve("proc");
        Files.createDirectories(fixture.config.paths()
                                              .stateDir(ref));
    }

    private UnitLauncher.Launch.Holding enqueue() {
        return ok(enqueued());
    }

    private Result<UnitLauncher.Launch.Holding> enqueued() {
        return BbHoldLauncher.bbHoldLauncher(fixture.runner,
                                                fixture.files,
                                                Path.of("/home/builder/tools/bb-hold"),
                                                fixture.config.toolEnv(),
                                                proc,
                                                signalled::add)
                                .enqueue(fixture.launch(ref), new HoldRequest("temper", 5))
                                .await();
    }

    private Command spawned() {
        return fixture.runner.commands()
                             .getLast();
    }

    @Test
    void argv_isSetsidBbHold_thenTheUnitsDockerRun_withNoShell() {
        enqueue();

        var argv = spawned().argv();

        assertThat(argv.subList(0, 10)).containsExactly("setsid",
                                                        "/home/builder/tools/bb-hold",
                                                        "temper",
                                                        "5",
                                                        "--container",
                                                        "temper-42-gate",
                                                        "--",
                                                        "docker",
                                                        "run",
                                                        "-d");
        assertThat(argv.subList(8, argv.size())).isEqualTo(DockerRunArgs.build(fixture.launch(ref)));
        assertThat(argv).doesNotContain("sh", "bash", "-c");
    }

    @Test
    void streamsGoToTheUnitFiles_andOnlyTheToolEnvironmentIsPassed() {
        enqueue();

        assertThat(spawned().stdoutFile()).isEqualTo(some(fixture.files.path(ref, UnitFile.BBHOLD_OUT)));
        assertThat(spawned().stderrFile()).isEqualTo(some(fixture.files.path(ref, UnitFile.BBHOLD_ERR)));
        assertThat(spawned().inheritEnv()).isFalse();
        assertThat(spawned().env()).isEqualTo(fixture.config.toolEnv());
    }

    @Test
    void pidFile_holdsThePidAndItsStartTicks() throws Exception {
        Files.createDirectories(proc.resolve("1"));
        Files.writeString(proc.resolve("1/stat"), ProcFixtures.stat(1, 555));

        var holding = enqueue();

        assertThat(ok(fixture.files.text(ref, UnitFile.BBHOLD_PID))).isEqualTo(some("1 555"));
        assertThat(holding.holder()).isEqualTo(HolderRecord.holderRecord(1, 555));
        assertThat(ok(holding.exit()
                             .await())).isZero();
    }

    /// A holder gone before its stat was read (a refusal takes milliseconds) gets an identity that never matches.
    @Test
    void anUnreadableStat_recordsAnUnknownStart() {
        enqueue();

        assertThat(ok(fixture.files.text(ref, UnitFile.BBHOLD_PID))).isEqualTo(some("1 -1"));
    }

    /// The grant files of an earlier attempt would read as this attempt's grant.
    @Test
    void anEarlierAttemptsGrantFiles_areRemovedBeforeTheSpawn() {
        ok(fixture.files.store(ref, UnitFile.BBHOLD_TICKET, "old"));
        ok(fixture.files.store(ref, UnitFile.START, "1"));
        ok(fixture.files.store(ref, UnitFile.CONTAINER, "old-cid"));

        enqueue();

        assertThat(ok(fixture.files.text(ref, UnitFile.BBHOLD_TICKET))).isEqualTo(none());
        assertThat(fixture.files.exists(ref, UnitFile.START)).isFalse();
        assertThat(fixture.files.exists(ref, UnitFile.CONTAINER)).isFalse();
    }

    /// A holder whose `bbhold.pid` cannot be written could never be identified or cancelled: it is signalled at once
    /// and the enqueue fails.
    @Test
    void aPidFileThatCannotBeWritten_signalsTheSpawnedHolder_andFails() throws Exception {
        Files.createDirectories(fixture.files.path(ref, UnitFile.BBHOLD_PID)
                                       .resolve("occupied"));

        Fixtures.failure(enqueued());

        assertThat(signalled).containsExactly(1L);
    }

    @Test
    void aRecordedHolder_isNotSignalled() {
        enqueue();

        assertThat(signalled).isEmpty();
    }

    private Promise<UnitLauncher.Launch> directLaunch() {
        fixture.events.add("direct");
        return Promise.success(UnitLauncher.Launch.running("cid"));
    }

    @Test
    void routed_sendsOnlyAPortGroupUnitThroughTheHold() {
        UnitLauncher direct = _ -> directLaunch();
        var routed = UnitLauncher.routed(direct, fixture.launcher);

        ok(routed.launch(fixture.launch(ref))
                 .await());
        ok(routed.launch(UnitLaunch.unitLaunch(Fixtures.request(42, "main"), home, home, 1, 1, 1, home, UnitNetwork.unitNetwork("temper-42-main", "gw")))
                 .await());

        assertThat(fixture.events).containsExactly("enqueue", "direct");
        assertThat(List.copyOf(fixture.launcher.exits)).hasSize(1);
    }
}
