package org.pragmatica.temper.acceptance.stack;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.frame.FrameReader;
import org.pragmatica.temper.protocol.frame.FrameWriter;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.worker.WorkerAssembly;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.config.WorkerTimings;
import org.pragmatica.temper.worker.link.FrameLoop;
import org.pragmatica.temper.worker.link.Outbox;
import org.pragmatica.temper.worker.unit.Timers;

/// The real worker, as a separate JVM speaking frames on stdin/stdout, the way `worker-launch run <ver>` starts it
/// under ssh (temper-worker `Main`). The only differences are what a developer machine forces: the configuration
/// comes from system properties (`/proc` and the cgroup tree are a fixture directory, the docker root is given,
/// bb-hold and bb-queue are absent), and no host lock is taken (one worker per acceptance stack).
///
/// Properties: `acc.home` (the worker's HOME; `.temper` below it), `acc.version`, `acc.dockerGid`, `acc.host` (the
/// host name the worker reports in its facts; `local` when absent).
public final class LocalWorker {
    private LocalWorker() {}

    public static void main(String[] args) throws Exception {
        var frames = new FileOutputStream(FileDescriptor.out);

        System.setOut(System.err);
        System.exit(run(frames));
    }

    private static int run(FileOutputStream frames) throws Exception {
        var home = Path.of(System.getProperty("acc.home"));
        var config = config(home, System.getProperty("acc.version"), Integer.parseInt(System.getProperty("acc.dockerGid")));
        var mapper = TemperJson.mapper();
        var outbox = Outbox.framed(FrameWriter.frameWriter(frames), mapper);
        var assembly = WorkerAssembly.assemble(config, ProcessRunner.processRunner(), mapper, outbox, InstantSource.system(), Timers.shared())
                                     .await()
                                     .expect("acceptance setup");
        var hello = assembly.hello()
                            .await()
                            .expect("acceptance setup");
        outbox.post(hello)
              .expect("acceptance setup");
        return FrameLoop.frameLoop(FrameReader.frameReader(System.in),
                                   TemperJson.mapper(),
                                   outbox,
                                   assembly.router(),
                                   () -> assembly.heartbeat()
                                                 .start(config.timings().heartbeat()))
                        .run();
    }

    static WorkerConfig config(Path home, String version, int dockerGid) throws Exception {
        var temperHome = home.resolve(".temper");
        var proc = Files.createDirectories(home.resolve("proc"));
        Files.writeString(proc.resolve("loadavg"), "0.1 0.2 0.3 1/1 1\n");
        Files.writeString(proc.resolve("meminfo"), "MemTotal: 16000000 kB\nMemAvailable: 8000000 kB\n");
        // docker and git keep the developer's own HOME (the docker context lives there); only the worker's paths move.
        var environment = Map.of("PATH", System.getenv("PATH"), "HOME", System.getenv("HOME"));
        return new WorkerConfig(WorkerPaths.workerPaths(temperHome),
                                version,
                                System.getProperty("acc.host", "local"),
                                (Integer) Files.getAttribute(home, "unix:uid"),
                                (Integer) Files.getAttribute(home, "unix:gid"),
                                dockerGid,
                                WorkerConfig.DOCKER_SOCKET,
                                1,
                                WorkerConfig.toolEnv(withDockerHost(environment)),
                                proc,
                                home.resolve("no-cgroup"),
                                Option.some(home),
                                WorkerConfig.bbQueue(environment, home).expect("acceptance setup"),
                                home.resolve("no-bb-hold"),
                                1,
                                timings());
    }

    /// The developer machine's docker endpoint (colima sets DOCKER_HOST or the docker context; both pass through).
    private static Map<String, String> withDockerHost(Map<String, String> environment) {
        var dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost == null) {
            return environment;
        }
        var all = new HashMap<>(environment);
        all.put("DOCKER_HOST", dockerHost);
        return all;
    }

    /// Production timings, except a short Testcontainers reap and a quick heartbeat.
    static WorkerTimings timings() {
        return new WorkerTimings(TimeSpan.timeSpan(10).seconds(),
                                 TimeSpan.timeSpan(5).seconds(),
                                 TimeSpan.timeSpan(5).seconds(),
                                 TimeSpan.timeSpan(5).seconds(),
                                 TimeSpan.timeSpan(500).millis(),
                                 TimeSpan.timeSpan(60).seconds(),
                                 TimeSpan.timeSpan(20).minutes(),
                                 TimeSpan.timeSpan(2).minutes(),
                                 TimeSpan.timeSpan(60).seconds(),
                                 TimeSpan.timeSpan(2).minutes(),
                                 TimeSpan.timeSpan(10).minutes(),
                                 WorkerTimings.HoldTimings.holdTimings());
    }
}
