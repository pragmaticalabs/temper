package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.EvidenceOutcome;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.ReleaseReason;
import org.pragmatica.temper.domain.task.RulingDecision;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.Waiting;
import org.pragmatica.temper.store.api.SubmitOutcome;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.TaskStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// m3-design §11.5 `TaskProjectionIT`, the projection rule of §5.3: 500 random command sequences over the store's whole
/// command surface (sweeps, claims, heartbeats from the current and a stale lease, evidence, linked submits, waits,
/// releases, parks, rulings, bounces, closes, linked jobs finishing, clock jumps past the TTL), and after EVERY command,
/// accepted or refused, the fold of the task's `task_events` equals its `tasks` row. The seed is fixed and printed;
/// `-Dtemper.projection.seed=N` replays another.
///
/// Probe: update the row without its event in one command (skip the HEARTBEAT event in `TaskCommands.recorded`): red.
@Tag("pg")
class TaskProjectionIT {
    private static final int SEQUENCES = 500;
    private static final int STEPS = 9;
    private static StoreHarness harness;
    private static TaskStore tasks;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
        tasks = harness.store.tasks();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /// One sequence's moving parts: the clock, the lease held now, an earlier one, and the jobs linked so far.
    private static final class Run {
        final TaskId task;
        Instant now;
        Option<Lease> current = none();
        Option<Lease> stale = none();
        final List<JobId> jobs = new ArrayList<>();
        int accepted;

        Run(TaskId task, Instant now) {
            this.task = task;
            this.now = now;
        }
    }

    @Test
    void fold_equalsRow_afterEveryCommand_ofRandomSequences() {
        var seed = Long.getLong("temper.projection.seed", 20261020L);
        var random = new Random(seed);
        var accepted = 0;

        for (var sequence = 0; sequence < SEQUENCES; sequence++) {
            var start = TaskFixture.T0.plus(Duration.ofDays(sequence));
            var run = new Run(TaskFixture.created(tasks, start).id(), start);

            for (var step = 0; step < STEPS; step++) {
                var op = random.nextInt(13);

                apply(run, op, random);
                TaskFixture.assertFoldEqualsRow(tasks, run.task, "seed " + seed + " sequence " + sequence + " step " + step + " op " + op);
                run.now = run.now.plus(Duration.ofMinutes(1 + random.nextInt(40)));
            }
            accepted += run.accepted;
        }
        // Enough commands were accepted for the check to have exercised the writes, not only refusals.
        assertThat(accepted).as("accepted commands, seed " + seed).isGreaterThan(SEQUENCES * 2);
    }

    private static void apply(Run run, int op, Random random) {
        switch (op) {
            case 0 -> counted(run, tasks.sweep(run.now).await(PgFixture.WAIT).map(_ -> run.task));
            case 1 -> claim(run, random);
            case 2 -> counted(run, leased(run, random.nextBoolean() ? run.stale : run.current, new Command.Heartbeat()));
            case 3 -> evidence(run, random);
            case 4 -> linkedSubmit(run);
            case 5 -> counted(run, leased(run, run.current, new Command.Wait(waited(run, random))));
            case 6 -> counted(run,
                              leased(run,
                                     run.current,
                                     new Command.Release(ReleaseReason.releaseReason(random.nextBoolean() ? "crash" : "budget").unwrap())));
            case 7 -> park(run, random);
            case 8 -> rule(run, random);
            case 9 -> counted(run, cto(run, new Command.Bounce("rename it")));
            case 10 -> counted(run, cto(run, random.nextInt(4) == 0 ? new Command.CloseAbandoned() : new Command.CloseMerged(random.nextBoolean())));
            case 11 -> finishLinkedJob(run, random);
            default -> jumpAndSweep(run, random);
        }
    }

    private static void claim(Run run, Random random) {
        var hash = "h" + random.nextLong();
        var claimed = TaskFixture.claim(tasks, run.task, hash, run.now);

        claimed.onSuccess(lease -> {
            run.stale = run.current;
            run.current = some(lease);
        });
        counted(run, claimed.map(_ -> run.task));
    }

    private static void evidence(Run run, Random random) {
        var row = tasks.task(run.task).await(PgFixture.WAIT).unwrap().unwrap();
        var outcome = switch (row.state().stage()) {
            case DESIGN -> TaskFixture.next(Stage.CODE);
            case VERIFY -> TaskFixture.verdict(random.nextBoolean() ? EvidenceOutcome.Verdict.PASS : EvidenceOutcome.Verdict.FAIL);
            case GATE -> TaskFixture.next(Stage.MERGE_READY);
            default -> TaskFixture.next(Stage.VERIFY);
        };

        counted(run,
                run.current.toResult(TaskError.General.LEASE_LOST)
                           .flatMap(lease -> tasks.evidence(lease, TaskFixture.evidence(), outcome, run.now).await(PgFixture.WAIT))
                           .map(TaskRow::id));
    }

    private static void linkedSubmit(Run run) {
        run.current.onPresent(lease -> harness.store.jobs()
                                                  .submit(TestJobs.bySha(TestJobs.key(), false).underLease(lease, run.now))
                                                  .await(PgFixture.WAIT)
                                                  .map(SubmitOutcome::job)
                                                  .onSuccess(run.jobs::add)
                                                  .onSuccess(_ -> run.accepted++));
    }

    private static Waiting waited(Run run, Random random) {
        return random.nextInt(3) == 0 || run.jobs.isEmpty()
               ? Waiting.waiting(List.of(), true)
               : Waiting.waiting(List.of(run.jobs.getLast()), false);
    }

    private static void park(Run run, Random random) {
        counted(run,
                random.nextBoolean()
                ? leased(run, run.current, new Command.Park(TaskFixture.request(), true))
                : cto(run, new Command.Park(TaskFixture.request(), false)));
    }

    /// The first option the open request offers (`retry` for temper's, `a` for the fixture's).
    private static void rule(Run run, Random random) {
        var open = tasks.detail(run.task)
                        .await(PgFixture.WAIT)
                        .unwrap()
                        .rulings()
                        .stream()
                        .filter(ruling -> ruling.ruling().isEmpty())
                        .map(ruling -> ruling.request().options().getFirst().id())
                        .findFirst()
                        .orElse("a");
        var decision = RulingDecision.rulingDecision(open, "go on", none(), random.nextBoolean() ? some(1) : none()).unwrap();

        counted(run, cto(run, new Command.Rule(decision)));
    }

    private static void finishLinkedJob(Run run, Random random) {
        if (!run.jobs.isEmpty()) {
            harness.store.jobs().requestCancel(run.jobs.get(random.nextInt(run.jobs.size()))).await(PgFixture.WAIT);
        }
        counted(run, tasks.sweep(run.now).await(PgFixture.WAIT).map(_ -> run.task));
    }

    /// Past the TTL (and sometimes the step deadline), then a sweep: the expiry path.
    private static void jumpAndSweep(Run run, Random random) {
        run.now = run.now.plus(random.nextBoolean() ? Duration.ofMinutes(31) : Duration.ofHours(9));
        counted(run, tasks.sweep(run.now).await(PgFixture.WAIT).map(_ -> run.task));
    }

    private static Result<TaskId> leased(Run run, Option<Lease> lease, Command command) {
        return lease.toResult(TaskError.General.LEASE_LOST)
                    .flatMap(held -> tasks.leased(held, command, run.now).await(PgFixture.WAIT))
                    .map(TaskRow::id);
    }

    private static Result<TaskId> cto(Run run, Command command) {
        return tasks.command(run.task, command, "cto", run.now)
                    .await(PgFixture.WAIT)
                    .map(TaskRow::id);
    }

    private static void counted(Run run, Result<TaskId> outcome) {
        outcome.onSuccess(_ -> run.accepted++);
    }
}
