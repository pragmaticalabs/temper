package org.pragmatica.temper.worker.unit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.ProcFixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The holder identity (m1-design §1.4): `/proc/<pid>` exists, its start ticks equal the recorded ones, and its
/// cmdline names bb-hold and this unit's container. Over a fake `/proc` tree.
class HolderProbeTest {
    @TempDir
    Path home;

    private UnitFiles files;
    private Path proc;
    private HolderProbe probe;
    private final UnitRef ref = Fixtures.ref(42, "gate");

    @BeforeEach
    void setUp() {
        files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home.resolve(".temper")));
        proc = home.resolve("proc");
        probe = HolderProbe.procHolderProbe(proc, files);
    }

    private void process(long pid, long ticks, byte[] cmdline) throws Exception {
        ProcFixtures.process(proc, pid, ticks, cmdline);
    }

    private static byte[] cmdline(String container) {
        return ProcFixtures.cmdline(container);
    }

    @Test
    void aUnitWithoutBbHoldFiles_isNotAHoldUnit() {
        assertThat(ok(probe.status(ref))).isEqualTo(none());
    }

    @Test
    void aLiveHolder_isWaiting_thenHeldOnceGranted() throws Exception {
        process(4242, 77, cmdline("temper-42-gate"));
        ok(files.store(ref, UnitFile.BBHOLD_PID, "4242 77"));
        ok(files.store(ref, UnitFile.BBHOLD_ERR, "bb-hold: queued ticket 1760-4242\n"));

        assertThat(ok(probe.status(ref))).isEqualTo(some(HolderStatus.WAITING));

        ok(files.store(ref, UnitFile.BBHOLD_ERR, "bb-hold: queued ticket 1760-4242\ngranted 1760-4242\n"));

        assertThat(ok(probe.status(ref))).isEqualTo(some(HolderStatus.HELD));
    }

    @Test
    void aRecycledPid_isDead() throws Exception {
        process(4242, 99, cmdline("temper-42-gate"));
        ok(files.store(ref, UnitFile.BBHOLD_PID, "4242 77"));

        assertThat(ok(probe.status(ref))).isEqualTo(some(HolderStatus.DEAD));
    }

    @Test
    void anotherUnitsHolder_orNotBbHold_isDead() throws Exception {
        ok(files.store(ref, UnitFile.BBHOLD_PID, "4242 77"));
        process(4242, 77, cmdline("temper-43-gate"));

        assertThat(ok(probe.status(ref))).isEqualTo(some(HolderStatus.DEAD));

        process(4242, 77, "sleep\0infinity\0temper-42-gate".getBytes(StandardCharsets.UTF_8));

        assertThat(ok(probe.status(ref))).isEqualTo(some(HolderStatus.DEAD));
    }

    /// A zombie keeps its stat but has an empty cmdline.
    @Test
    void aGoneProcess_orAZombie_isDead() throws Exception {
        ok(files.store(ref, UnitFile.BBHOLD_PID, "4242 77"));

        assertThat(ok(probe.status(ref))).isEqualTo(some(HolderStatus.DEAD));

        process(4242, 77, new byte[0]);

        assertThat(ok(probe.status(ref))).isEqualTo(some(HolderStatus.DEAD));
    }

    /// A worker that died between the spawn and the pid write leaves `bbhold.err` only: a hold unit that cannot
    /// be identified, so dead. An unknown start time (the stat was unreadable at the spawn) never matches, not even a
    /// stat that reads the same value.
    @Test
    void anUnidentifiableHolder_isDead() throws Exception {
        ok(files.store(ref, UnitFile.BBHOLD_ERR, ""));

        assertThat(ok(probe.status(ref))).isEqualTo(some(HolderStatus.DEAD));

        process(4242, HolderRecord.UNKNOWN, cmdline("temper-42-gate"));
        ok(files.store(ref, UnitFile.BBHOLD_PID, "4242 " + HolderRecord.UNKNOWN));

        assertThat(ok(probe.status(ref))).isEqualTo(some(HolderStatus.DEAD));
    }

    @Test
    void aCorruptPidFile_fails() {
        ok(files.store(ref, UnitFile.BBHOLD_PID, "not a pid"));

        Fixtures.failure(probe.status(ref));
    }

    /// Nothing with the recorded identity runs, so nothing is signalled (a recycled pid is never hit).
    @Test
    void terminate_signalsNothing_withoutALiveIdentifiedHolder() throws Exception {
        process(4242, 99, cmdline("temper-42-gate"));
        ok(files.store(ref, UnitFile.BBHOLD_PID, "4242 77"));

        assertThat(ok(probe.terminate(ref))).isFalse();
    }

    /// A live process whose pid the record names but whose start time differs (a recycled pid) is never signalled;
    /// the identified holder is, by its recorded pid.
    @Test
    void terminate_signalsTheIdentifiedHolder_only() throws Exception {
        var signalled = new CopyOnWriteArrayList<Long>();
        var recording = HolderProbe.procHolderProbe(proc, files, pid -> signalled.add(pid));

        ok(files.store(ref, UnitFile.BBHOLD_PID, "4242 77"));
        process(4242, 99, cmdline("temper-42-gate"));

        assertThat(ok(recording.terminate(ref))).isFalse();
        assertThat(signalled).isEmpty();

        process(4242, 77, cmdline("temper-42-gate"));

        assertThat(ok(recording.terminate(ref))).isTrue();
        assertThat(signalled).containsExactly(4242L);
    }
}
