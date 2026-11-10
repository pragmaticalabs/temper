package org.pragmatica.temper.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.worker.config.WorkerTimings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The wiring of the Testcontainers reap windows (verifier F3, 2026-10-14): the two windows are positional
/// `TimeSpan`s, so a swap compiles and every unit would reap for 5 s while a voided one waits 60 s.
class WorkerAssemblyTest {
    @Test
    void workerTimings_reapsSixtySecondsAndFiveForAVoidedUnit() {
        var timings = WorkerTimings.workerTimings();

        assertThat(timings.reapWait()).isEqualTo(TimeSpan.timeSpan(60).seconds());
        assertThat(timings.voidedReapWait()).isEqualTo(TimeSpan.timeSpan(5).seconds());
    }

    @Test
    void assemble_wiresEachReapWindowToItsOwnSetting(@TempDir Path home) {
        var config = Fixtures.config(home.resolve(".temper"), home.resolve("proc"), home);
        var timings = config.timings();
        var reap = ok(WorkerAssembly.assemble(config,
                                              new FakeRunner(),
                                              TemperJson.mapper(),
                                              new RecordingOutbox(),
                                              Instant::now,
                                              new Fixtures.ManualTimers())
                                    .await()).router()
                                             .watcher()
                                             .collector()
                                             .reap();

        assertThat(timings.reapWait()).isNotEqualTo(timings.voidedReapWait());
        assertThat(reap.reapWait()).isEqualTo(timings.reapWait());
        assertThat(reap.voidedReapWait()).isEqualTo(timings.voidedReapWait());
        assertThat(reap.poll()).isEqualTo(timings.reapPoll());
    }

    /// The worker-start sweep: a `Hello` is not built before the orphan network sweep has asked docker for the
    /// labelled networks (a leak from a past run is reclaimed on every restart).
    @Test
    void hello_sweepsOrphanNetworks_atWorkerStart(@TempDir Path home) throws Exception {
        var proc = Files.createDirectories(home.resolve("proc"));

        Files.writeString(proc.resolve("loadavg"), "0.1 0.2 0.3 1/1 1\n");
        Files.writeString(proc.resolve("meminfo"), "MemAvailable: 5 kB\n");
        var config = Fixtures.config(home.resolve(".temper"), proc, home);
        var runner = new FakeRunner();
        var assembly = ok(WorkerAssembly.assemble(config,
                                                  runner,
                                                  TemperJson.mapper(),
                                                  new RecordingOutbox(),
                                                  Instant::now,
                                                  new Fixtures.ManualTimers())
                                        .await());

        ok(assembly.hello().await());

        assertThat(runner.calls()).anyMatch(argv -> argv.size() > 2 && argv.get(1).equals("network") && argv.get(2).equals("ls"));
    }
}
