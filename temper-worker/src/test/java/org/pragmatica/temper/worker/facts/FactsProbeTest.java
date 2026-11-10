package org.pragmatica.temper.worker.facts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.docker.DockerCli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// Host facts from a fixture `/proc` (m0-design §7.7), including A7's reactor count: a launcher inside a temper
/// container's cgroup is not foreign; a bare-host launcher is.
class FactsProbeTest {
    private static final String TEMPER_ID = "ab".repeat(32);
    private static final String LAUNCHER = "java\0-cp\0boot\0org.codehaus.plexus.classworlds.launcher.Launcher\0verify\0";

    private static void process(Path proc, long pid, String cmdline, String cgroup) throws IOException {
        var dir = Files.createDirectories(proc.resolve(Long.toString(pid)));

        Files.write(dir.resolve("cmdline"), cmdline.getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("cgroup"), cgroup);
    }

    @Test
    void probe_readsLoadMemoryDiskAndCountsOnlyForeignReactors(@TempDir Path root) throws IOException {
        var proc = Files.createDirectories(root.resolve("proc"));

        Files.writeString(proc.resolve("loadavg"), "1.50 2.25 3.00 2/900 4242\n");
        Files.writeString(proc.resolve("meminfo"), "MemTotal: 100 kB\nMemAvailable:   777 kB\n");
        process(proc, 100, LAUNCHER, "0::/system.slice/docker-" + TEMPER_ID + ".scope\n");
        process(proc, 200, LAUNCHER, "0::/user.slice/user-1000.slice/session-3.scope\n");
        process(proc, 300, "bash\0", "0::/user.slice\n");
        Files.createDirectories(proc.resolve("self"));
        Files.createDirectories(proc.resolve("999"));

        var runner = new FakeRunner().on(List.of("docker", "ps", "-q", "--no-trunc"), 0, TEMPER_ID + "\n", "");
        var at = Instant.parse("2026-10-13T00:00:00Z");
        var facts = ok(FactsProbe.hostProbe(proc,
                                            root.resolve("jobs"),
                                            root,
                                            DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()),
                                            runner,
                                            ok(WorkerConfig.bbQueue(Map.of(), root)),
                                            () -> at)
                                 .probe()
                                 .await());

        assertThat(facts.load1()).isEqualTo(1.5);
        assertThat(facts.load5()).isEqualTo(2.25);
        assertThat(facts.memAvailableKb()).isEqualTo(777);
        assertThat(facts.foreignReactors()).as("A7: the temper unit's launcher is not foreign")
                                           .isEqualTo(1);
        assertThat(facts.temperUnitsRunning()).isEqualTo(1);
        assertThat(runner.callsStartingWith(List.of("docker", "ps", "-q", "--no-trunc"))).as("F13: units and their auxiliary containers")
                                                                                         .singleElement()
                                                                                         .satisfies(argv -> assertThat(argv).endsWith("--filter",
                                                                                                                                      "label=temper.job"));
        assertThat(facts.freeWorkspaceBytes()).isPositive();
        assertThat(facts.freeDockerRootBytes()).isPositive();
        assertThat(facts.bbRunning()).as("the fake runner answers bb-queue with empty output")
                                     .isEqualTo(Option.none());
        assertThat(facts.bbWaiting()).isEqualTo(Option.none());
        assertThat(facts.notes()).singleElement()
                                 .asString()
                                 .startsWith("bb-queue: ");
        assertThat(facts.at()).isEqualTo(at);
        assertThat(root.resolve("jobs")).isDirectory();
    }

    @Test
    void probe_carriesTheBbQueueCounts_whenTheListingParses(@TempDir Path root) throws IOException {
        var proc = Files.createDirectories(root.resolve("proc"));
        var bbQueue = ok(WorkerConfig.bbQueue(Map.of(), root));

        Files.writeString(proc.resolve("loadavg"), "0.5 0.5 0.5 1/1 1\n");
        Files.writeString(proc.resolve("meminfo"), "MemAvailable: 1 kB\n");
        String listing;
        try (var in = FactsProbeTest.class.getResourceAsStream("/bbqueue/busy.txt")) {
            listing = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var runner = new FakeRunner().on(bbQueue.argv(), 0, listing, "");

        var facts = ok(FactsProbe.hostProbe(proc,
                                            root.resolve("jobs"),
                                            root,
                                            DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()),
                                            runner,
                                            bbQueue,
                                            Instant::now)
                                 .probe()
                                 .await());

        assertThat(facts.bbRunning()).isEqualTo(Option.some(2));
        assertThat(facts.bbWaiting()).isEqualTo(Option.some(3));
        assertThat(facts.notes()).isEmpty();
    }

    @Test
    void cgroupSampler_readsBothDriverLayouts_andKeepsTheLastSample(@TempDir Path root) throws IOException {
        var systemd = Files.createDirectories(root.resolve("system.slice/docker-aaa.scope"));
        var cgroupfs = Files.createDirectories(root.resolve("docker/bbb"));

        Files.writeString(systemd.resolve("memory.peak"), "100\n");
        Files.writeString(systemd.resolve("cpu.stat"), "usage_usec 9\n");
        Files.writeString(cgroupfs.resolve("cpu.stat"), "usage_usec 1\n");

        var sampler = CgroupSampler.cgroupSampler(root, () -> Instant.EPOCH);

        sampler.sampleAll(List.of("aaa", "bbb", "ccc"));

        assertThat(sampler.lastSample("aaa")
                          .flatMap(CgroupSampler.CgroupSample::memoryPeakBytes)).isEqualTo(Option.some(100L));
        assertThat(sampler.lastSample("bbb")
                          .map(CgroupSampler.CgroupSample::cpuStat)).isEqualTo(Option.some(Map.of("usage_usec", 1L)));
        assertThat(sampler.lastSample("bbb")
                          .flatMap(CgroupSampler.CgroupSample::memoryPeakBytes)).isEqualTo(Option.none());
        assertThat(sampler.lastSample("ccc")).isEqualTo(Option.none());

        sampler.forget("aaa");
        assertThat(sampler.lastSample("aaa")).isEqualTo(Option.none());
    }
}
