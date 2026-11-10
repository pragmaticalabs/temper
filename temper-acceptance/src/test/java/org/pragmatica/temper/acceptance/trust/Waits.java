package org.pragmatica.temper.acceptance.trust;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.api.UnitView;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;

/// Fakes for the two wait phases: a clock that only the pacer moves, the controller's job reads, and the `temper wait`
/// process.
final class Waits {
    static final Instant START = Instant.parse("2026-10-16T00:00:00Z");

    private Waits() {}

    /// A clock at [#START] that moves only when the pacer pauses; every pause is recorded.
    static final class Clock implements InstantSource {
        final AtomicReference<Instant> now = new AtomicReference<>(START);
        final List<Duration> pauses = new CopyOnWriteArrayList<>();

        @Override
        public Instant instant() {
            return now.get();
        }

        Promise<Unit> pause(Duration duration) {
            pauses.add(duration);
            now.updateAndGet(instant -> instant.plus(duration));
            return Promise.success(unit());
        }

        Duration elapsed() {
            return Duration.between(START, now.get());
        }
    }

    /// `GET /jobs/{id}`: read `n` (from 0) answers `answer.apply(n)`.
    static final class Jobs {
        final AtomicInteger reads = new AtomicInteger();
        private final IntFunction<Promise<JobView>> answer;

        Jobs(IntFunction<Promise<JobView>> answer) {
            this.answer = answer;
        }

        Promise<JobView> read(long job) {
            return answer.apply(reads.getAndIncrement());
        }
    }

    /// QUEUED for the first `queued` reads, then `after`.
    static Jobs queuedThen(int queued, JobState after) {
        return new Jobs(n -> Promise.success(view(n < queued ? JobState.QUEUED : after)));
    }

    /// Read `n` (from 0) answers `views[n]`, the last one from then on.
    static Jobs reading(JobView... views) {
        return new Jobs(n -> Promise.success(views[Math.min(n, views.length - 1)]));
    }

    static JobView view(JobState state) {
        return view(state, List.of());
    }

    static JobView view(JobState state, List<UnitView> units) {
        return JobView.jobView(39, state, none(), none(), "t1_harness", START, 1, false, true, false,
                               new SourceView(none(), some("a".repeat(40))), new RecipeDto.Gate(), units, List.of());
    }

    /// QUEUED with its unit granted and bb-hold waiting for the port group since [#START] (`PgUnitStore`).
    static JobView queuedForPortGroup() {
        return view(JobState.QUEUED, List.of(gateUnit(UnitState.GRANTED, some(UnitView.WAITING))));
    }

    /// RUNNING with its unit started and the port group held.
    static JobView runningHeld() {
        return view(JobState.RUNNING, List.of(gateUnit(UnitState.RUNNING, some(UnitView.HELD))));
    }

    static UnitView gateUnit(UnitState state, Option<String> portGroup) {
        return UnitView.unitView("gate", state, none(), some("bigboy"), none(), some(START), none(), none(), none(), none(),
                                 false, portGroup, portGroup.map(_ -> START), none());
    }

    static Promise<JobView> io() {
        return TrustError.Io.FACTORY.apply("call /jobs/39", Causes.cause("connection refused")).promise();
    }

    static QueueWait queue(Jobs jobs, Clock clock, Duration bound) {
        return QueueWait.queueWait(jobs::read, clock, clock::pause, bound);
    }

    /// The `temper wait` process: every run exits `rc` printing `stdout`; each argv is recorded.
    static final class Cli implements ProcessRunner {
        final List<List<String>> argvs = new CopyOnWriteArrayList<>();
        private final int rc;
        private final String stdout;

        Cli(int rc, String stdout) {
            this.rc = rc;
            this.stdout = stdout;
        }

        @Override
        public Promise<Completed> run(Command command) {
            argvs.add(command.argv());
            return Promise.success(Completed.completed("temper", rc, stdout.getBytes(StandardCharsets.UTF_8), new byte[0], false));
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            return Causes.cause("not used").result();
        }

        TemperCli temperCli() {
            return TemperCli.temperCli(Processes.processes(this), List.of("temper"), Map.of());
        }
    }
}
