package org.pragmatica.temper.worker;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.plan.Planner;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.config.WorkerTimings;
import org.pragmatica.temper.worker.unit.BbQueueReap;
import org.pragmatica.temper.worker.unit.HolderProbe;
import org.pragmatica.temper.worker.unit.StartRequest;
import org.pragmatica.temper.worker.unit.UnitFiles;
import org.pragmatica.temper.worker.unit.Timers;
import org.pragmatica.temper.worker.unit.UnitRefs;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.junit.jupiter.api.Assertions.fail;


/// Shared test values and helpers.
public final class Fixtures {
    private Fixtures() {}

    public static <T> T ok(Result<T> result) {
        return result.fold(cause -> fail("expected success, got: " + describe(cause)),
                           value -> value);
    }

    /// The cause's message plus, for a failed docker call, the origin's message and docker's stderr tail (which the
    /// production message deliberately omits); an IT failure on CI must carry docker's own words.
    private static String describe(Cause cause) {
        return switch (cause) {
            case WorkerError.Docker docker -> docker.message() + " <- " + describe(docker.origin());
            case ExecError.NonZero nonZero -> nonZero.message() + "; stderr tail: " + nonZero.stderrTail();
            default -> cause.message();
        };
    }

    public static <T> Cause failure(Result<T> result) {
        return result.fold(cause -> cause,
                           value -> fail("expected failure, got: " + value));
    }

    public static <T> Option<T> none() {
        return Option.none();
    }

    public static UnitRef ref(long job, String unit) {
        return ok(UnitRefs.unitRef(job, unit));
    }

    public static Message.StartUnit startUnit(long job, String unit, List<List<String>> steps, Duration timeout) {
        return startUnit(job, unit, 1, steps, timeout);
    }

    /// A `StartUnit` of the given attempt (protocol v3).
    public static Message.StartUnit startUnit(long job,
                                              String unit,
                                              int attempt,
                                              List<List<String>> steps,
                                              Duration timeout) {
        return new Message.StartUnit(job,
                                     unit,
                                     attempt,
                                     "MODULE_TEST",
                                     steps,
                                     timeout,
                                     6144,
                                     1024,
                                     "0.1.0",
                                     some("-Xmx2g"),
                                     none(),
                                     none(),
                                     none(),
                                     "temper");
    }

    public static StartRequest request(long job, String unit) {
        return request(job, unit, 1);
    }

    public static StartRequest request(long job, String unit, int attempt) {
        return ok(StartRequest.startRequest(startUnit(job,
                                                      unit,
                                                      attempt,
                                                      List.of(List.of("mvn",
                                                                      "-o",
                                                                      "verify",
                                                                      "-pl",
                                                                      "core",
                                                                      Planner.FORCED_REPO_LOCAL)),
                                                      Duration.ofMinutes(60))));
    }

    /// Short reap and call bounds so tests never wait on production timeouts.
    public static WorkerTimings fastTimings() {
        return new WorkerTimings(timeSpan(10).seconds(),
                                 timeSpan(2).seconds(),
                                 timeSpan(300).millis(),
                                 timeSpan(100).millis(),
                                 timeSpan(50).millis(),
                                 timeSpan(10).seconds(),
                                 timeSpan(10).seconds(),
                                 timeSpan(30).seconds(),
                                 timeSpan(10).seconds(),
                                 timeSpan(10).seconds(),
                                 timeSpan(10).seconds(),
                                 fastHold());
    }

    /// bb-hold waits shrunk to milliseconds; the relations between them are kept.
    public static WorkerTimings.HoldTimings fastHold() {
        return new WorkerTimings.HoldTimings(timeSpan(20).millis(),
                                             timeSpan(300).millis(),
                                             timeSpan(20).millis(),
                                             timeSpan(20).millis(),
                                             timeSpan(400).millis(),
                                             timeSpan(20).millis());
    }

    public static WorkerConfig config(Path temperHome, Path procRoot, Path dockerRoot) {
        return config(temperHome, procRoot, dockerRoot, WorkerConfig.DEFAULT_DISK_FLOOR_BYTES);
    }

    /// A config whose R-DISK floor is `diskFloorBytes`. An assembled worker reads the free space of the real
    /// filesystem under `dockerRoot`: a test that routes a `StartUnit` through it sets the floor to 0, or its start is
    /// refused on any host with less free space than the 50 GiB default (the GitHub runner of run 40470595321 had 12.9 GB).
    public static WorkerConfig config(Path temperHome, Path procRoot, Path dockerRoot, long diskFloorBytes) {
        return new WorkerConfig(WorkerPaths.workerPaths(temperHome),
                                "0.1.0-test",
                                "testhost",
                                1001,
                                1001,
                                999,
                                WorkerConfig.DOCKER_SOCKET,
                                diskFloorBytes,
                                Map.of("PATH", System.getenv("PATH"), "HOME", System.getProperty("user.home")),
                                procRoot,
                                temperHome.resolve("no-cgroup"),
                                some(dockerRoot),
                                ok(WorkerConfig.bbQueue(Map.of(), temperHome)),
                                temperHome.resolve("tools/bb-hold"),
                                WorkerConfig.DEFAULT_RUN_FLOOR_BYTES,
                                fastTimings());
    }

    /// A holder probe over an empty `/proc`: units without bb-hold files are not hold units; a hold unit's holder is
    /// always dead.
    public static HolderProbe noHolders(WorkerPaths paths, UnitFiles files) {
        return HolderProbe.procHolderProbe(paths.temperHome().resolve("no-proc"),
                                           files);
    }

    /// `bb-queue reap` through `runner`, derived from the `/home/test/tools/bb-queue 0` listing.
    public static BbQueueReap reap(ProcessRunner runner) {
        return BbQueueReap.bbQueueReap(runner,
                                       ok(WorkerConfig.bbQueue(Map.of(), Path.of("/home/test"))));
    }

    public static HolderProbe noHolders(UnitFiles files) {
        return noHolders(files.paths(), files);
    }

    /// Blocks the calling thread for `delay`: a slow answer or a slow side effect, as a late asynchronous one would be.
    public static Unit pause(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return unit();
    }

    /// A clock the test moves by hand.
    public static final class ManualClock implements InstantSource {
        private final AtomicReference<Instant> now;

        public ManualClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        public void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }
    }

    /// Timers the test fires by hand; records every scheduled delay.
    public static final class ManualTimers implements Timers {
        public record Entry(Runnable task, TimeSpan delay, boolean[] cancelled) {}

        private final List<Entry> scheduled = new ArrayList<>();
        private final Duration cancelDelay;

        public ManualTimers() {
            this(Duration.ZERO);
        }

        /// Timers whose cancel takes `cancelDelay`: a step that cancels a timer must complete only after the cancel,
        /// so an `on*` callback (run after the step's dependents) shows as a timer still live when the step resolved.
        public ManualTimers(Duration cancelDelay) {
            this.cancelDelay = cancelDelay;
        }

        @Override
        public synchronized Scheduled schedule(Runnable task, TimeSpan delay) {
            var entry = new Entry(task, delay, new boolean[1]);

            scheduled.add(entry);

            return () -> cancel(entry);
        }

        private Unit cancel(Entry entry) {
            pause(cancelDelay);
            entry.cancelled() [0] = true;

            return unit();
        }

        public synchronized List<Entry> scheduled() {
            return List.copyOf(scheduled);
        }

        public synchronized Option<Entry> last() {
            return scheduled.isEmpty()
                   ? none()
                   : some(scheduled.getLast());
        }
    }
}
