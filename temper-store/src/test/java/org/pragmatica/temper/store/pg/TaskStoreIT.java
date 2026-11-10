package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.NewTask;
import org.pragmatica.temper.domain.task.ReleaseReason;
import org.pragmatica.temper.domain.task.RulingDecision;
import org.pragmatica.temper.domain.task.RulingKind;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.Status;
import org.pragmatica.temper.domain.task.TaskBrief;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.TaskEvent;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.Waiting;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.TaskDetail;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.TaskStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The task store against Postgres (m3-design §5, §6, §8): `after` must name tasks; T1 waits for `after` to reach
/// VERIFY; `ready` is the dispatcher order; the sweeper's two clocks (a running linked job holds the TTL, never the step
/// deadline) and its second-expiry park; WAITING resumes once every linked job it waits for is terminal; a park stores
/// its request, a ruling must name an offered option and is written once; the write-once tables refuse changes.
@Tag("pg")
class TaskStoreIT {
    private static final String LEASE_COLUMNS = "lease_hash = repeat('a', 64), lease_until = now(), step_deadline = now()";

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

    @Test
    void create_withUnknownAfter_isAfterUnknown_andCreatesNothing() {
        var before = harness.count("SELECT count(*) AS n FROM tasks");

        tasks.create(TaskFixture.newTask(List.of(TaskId.taskId(999_999).unwrap()), false), TaskFixture.T0)
             .await(PgFixture.WAIT)
             .onSuccess(row -> fail("created with an unknown after: " + row))
             .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.AfterUnknown.FACTORY.apply(TaskId.taskId(999_999).unwrap())));
        assertThat(harness.count("SELECT count(*) AS n FROM tasks")).isEqualTo(before);
    }

    @Test
    void create_isTriagedReady_withCreatedAndReadyEvents() {
        var row = TaskFixture.created(tasks, TaskFixture.T0);
        var events = tasks.events(row.id()).await(PgFixture.WAIT).unwrap();

        assertThat(row.state().stage()).isEqualTo(Stage.TRIAGED);
        assertThat(row.state().status()).isEqualTo(Status.READY);
        assertThat(events.stream().map(event -> event.event().kind().wire()).toList()).containsExactly("created", "ready");
        assertThat(events.getLast().event().facts()).containsEntry("reason", "created");
    }

    @Test
    void sweep_advancesAFollower_onlyOnceItsAfterReachedVerify() {
        var first = TaskFixture.created(tasks, TaskFixture.T0);
        var follower = tasks.create(TaskFixture.newTask(List.of(first.id()), true), TaskFixture.T0).await(PgFixture.WAIT).unwrap();

        tasks.sweep(TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        assertThat(stageOf(first.id())).isEqualTo(Stage.CODE);
        assertThat(stageOf(follower.id())).isEqualTo(Stage.TRIAGED);

        var lease = TaskFixture.claim(tasks, first.id(), "h", TaskFixture.T0).unwrap();
        tasks.evidence(lease, TaskFixture.evidence(), TaskFixture.next(Stage.VERIFY), TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        tasks.sweep(TaskFixture.T0).await(PgFixture.WAIT).unwrap();

        assertThat(stageOf(follower.id())).isEqualTo(Stage.DESIGN);
    }

    @Test
    void ready_isTheDispatcherOrder() {
        var at = TaskFixture.T0.plus(Duration.ofDays(400));
        var plain = advanced(task(0, false), at);
        var high = advanced(task(5, false), at);
        var critical = advanced(task(-5, true), at);
        var verify = advanced(task(0, false), at);
        var lease = TaskFixture.claim(tasks, verify, "h", at).unwrap();

        tasks.evidence(lease, TaskFixture.evidence(), TaskFixture.next(Stage.VERIFY), at).await(PgFixture.WAIT).unwrap();
        var order = tasks.ready(none(), 1000)
                         .await(PgFixture.WAIT)
                         .unwrap()
                         .stream()
                         .map(TaskRow::id)
                         .filter(id -> List.of(plain, high, critical, verify).contains(id))
                         .toList();

        assertThat(order).containsExactly(critical, verify, high, plain);
    }

    /// Verifier G4: the dispatcher's last two keys. Earlier `ready_since` first, whatever the ids; equal `ready_since`,
    /// lower id first.
    @Test
    void ready_tiesBreakByReadySinceThenId() {
        var earlier = TaskFixture.T0.plus(Duration.ofDays(800));
        var later = earlier.plus(Duration.ofMinutes(5));
        var lateFirst = tasks.create(task(0, false), earlier).await(PgFixture.WAIT).unwrap().id();

        tasks.sweep(later).await(PgFixture.WAIT).unwrap();
        var sameA = tasks.create(task(0, false), earlier).await(PgFixture.WAIT).unwrap().id();
        var sameB = tasks.create(task(0, false), earlier).await(PgFixture.WAIT).unwrap().id();
        var earlyLast = tasks.create(task(0, false), earlier).await(PgFixture.WAIT).unwrap().id();

        tasks.sweep(earlier).await(PgFixture.WAIT).unwrap();
        // earlyLast, sameA, sameB entered CODE at `earlier`; lateFirst (the lowest id) at `later`.
        assertThat(readySince(lateFirst)).isEqualTo(later);
        assertThat(readySince(sameA)).isEqualTo(earlier);
        var order = tasks.ready(none(), 1000)
                         .await(PgFixture.WAIT)
                         .unwrap()
                         .stream()
                         .map(TaskRow::id)
                         .filter(id -> List.of(lateFirst, sameA, sameB, earlyLast).contains(id))
                         .toList();

        assertThat(order).containsExactly(sameA, sameB, earlyLast, lateFirst);
    }

    @Test
    void expiry_firstRequeues_secondInTheVisitParksStuckLease_withItsRequest() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var at = TaskFixture.T0;

        TaskFixture.claim(tasks, row.id(), "h1", at).unwrap();
        at = at.plus(Duration.ofMinutes(31));
        tasks.sweep(at).await(PgFixture.WAIT).unwrap();
        assertThat(statusOf(row.id())).isEqualTo(Status.READY);

        TaskFixture.claim(tasks, row.id(), "h2", at).unwrap();
        at = at.plus(Duration.ofMinutes(31));
        tasks.sweep(at).await(PgFixture.WAIT).unwrap();

        var parked = tasks.task(row.id()).await(PgFixture.WAIT).unwrap().unwrap();
        var ruling = tasks.detail(row.id()).await(PgFixture.WAIT).unwrap().rulings().getFirst();

        assertThat(parked.state().stage()).isEqualTo(Stage.NEEDS_RULING);
        assertThat(ruling.kind()).isEqualTo(RulingKind.STUCK_LEASE);
        assertThat(ruling.raisedFrom()).isEqualTo(Stage.CODE);
        assertThat(ruling.raisedBy()).isEqualTo("temper");
        assertThat(ruling.request().recommendation().temperDefault()).isTrue();
        TaskFixture.assertFoldEqualsRow(tasks, row.id(), "after the stuck-lease park");
    }

    @Test
    void runningLinkedJob_holdsTheTtl_butNotTheStepDeadline() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var lease = TaskFixture.claim(tasks, row.id(), "h", TaskFixture.T0).unwrap();

        linkedJob(lease);
        // The instrument first (verifier V3): the linked submit restarted the TTL on the fixture clock, so at T0+31m the
        // TTL has passed and only the running job can hold the lease.
        assertThat(tasks.task(row.id()).await(PgFixture.WAIT).unwrap().unwrap().state().lease().until())
            .isEqualTo(some(TaskFixture.T0.plus(Duration.ofMinutes(30))));
        tasks.sweep(TaskFixture.T0.plus(Duration.ofMinutes(31))).await(PgFixture.WAIT).unwrap();
        assertThat(statusOf(row.id())).isEqualTo(Status.LEASED);

        tasks.sweep(TaskFixture.T0.plus(Duration.ofHours(6)).plusSeconds(1)).await(PgFixture.WAIT).unwrap();
        assertThat(statusOf(row.id())).isEqualTo(Status.READY);
        assertThat(tasks.events(row.id()).await(PgFixture.WAIT).unwrap().getLast().event().kind().wire()).isEqualTo("ready");
    }

    /// Verifier V3: a job linked at an earlier epoch holds nothing of a later lease's TTL.
    @Test
    void earlierEpochsJob_doesNotHoldALaterLeasesTtl() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var first = TaskFixture.claim(tasks, row.id(), "e1", TaskFixture.T0).unwrap();

        linkedJob(first);
        tasks.leased(first, new Command.Release(ReleaseReason.releaseReason("crash").unwrap()), TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        var later = TaskFixture.claim(tasks, row.id(), "e3", TaskFixture.T0).unwrap();

        assertThat(later.epoch()).isGreaterThan(first.epoch());
        tasks.sweep(TaskFixture.T0.plus(Duration.ofMinutes(31))).await(PgFixture.WAIT).unwrap();
        assertThat(statusOf(row.id())).isEqualTo(Status.READY);
    }

    /// Verifier V2 [G]: a lease whose TTL lapsed while the controller was down gets start + its TTL, so the
    /// first sweep does not expire it; it expires one TTL later, as any lease without a heartbeat.
    @Test
    void restarted_givesALapsedLeaseAFullTtlFromTheStart() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0.plus(Duration.ofDays(600)));
        var claimedAt = TaskFixture.T0.plus(Duration.ofDays(600));
        var start = claimedAt.plus(Duration.ofHours(2));

        TaskFixture.claim(tasks, row.id(), "restart", claimedAt).unwrap();
        assertThat(tasks.restarted(start).await(PgFixture.WAIT).unwrap()).contains(row.id());
        tasks.sweep(start.plus(Duration.ofMinutes(1))).await(PgFixture.WAIT).unwrap();
        assertThat(statusOf(row.id())).isEqualTo(Status.LEASED);

        tasks.sweep(start.plus(Duration.ofMinutes(31))).await(PgFixture.WAIT).unwrap();
        assertThat(statusOf(row.id())).isEqualTo(Status.READY);
        TaskFixture.assertFoldEqualsRow(tasks, row.id(), "after the restart extension and the expiry");
    }

    /// Verifier V2 [G], review D1: the step deadline is a wall bound; a lease past it is not extended (no
    /// `lease-extended` event) and expires at the start's sweep as usual.
    @Test
    void restarted_pastTheStepDeadline_extendsNothing() {
        var claimedAt = TaskFixture.T0.plus(Duration.ofDays(700));
        var row = TaskFixture.inCode(tasks, claimedAt);
        var start = claimedAt.plus(Duration.ofHours(7));

        TaskFixture.claim(tasks, row.id(), "deadline", claimedAt).unwrap();
        assertThat(tasks.restarted(start).await(PgFixture.WAIT).unwrap()).doesNotContain(row.id());
        assertThat(extensions(row.id())).isZero();
        tasks.sweep(start).await(PgFixture.WAIT).unwrap();

        assertThat(statusOf(row.id())).isEqualTo(Status.READY);
        assertThat(tasks.events(row.id()).await(PgFixture.WAIT).unwrap().stream().map(event -> event.event().facts().get("clock")))
            .contains("step-deadline");
    }

    /// Review D1: a live lease is untouched; a restart 5 s after the claim extends nothing.
    @Test
    void restarted_leavesALiveLeaseAlone() {
        var claimedAt = TaskFixture.T0.plus(Duration.ofDays(800));
        var row = TaskFixture.inCode(tasks, claimedAt);

        TaskFixture.claim(tasks, row.id(), "live", claimedAt).unwrap();
        assertThat(tasks.restarted(claimedAt.plusSeconds(5)).await(PgFixture.WAIT).unwrap()).doesNotContain(row.id());
        assertThat(extensions(row.id())).isZero();
        assertThat(tasks.task(row.id()).await(PgFixture.WAIT).unwrap().unwrap().state().lease().until())
            .isEqualTo(some(claimedAt.plus(Duration.ofMinutes(30))));
    }

    /// Review D1: at most once per epoch; a second restart within the epoch extends nothing more.
    @Test
    void restarted_twiceInOneEpoch_extendsOnce() {
        var claimedAt = TaskFixture.T0.plus(Duration.ofDays(900));
        var row = TaskFixture.inCode(tasks, claimedAt);

        TaskFixture.claim(tasks, row.id(), "twice", claimedAt).unwrap();
        assertThat(tasks.restarted(claimedAt.plus(Duration.ofHours(1))).await(PgFixture.WAIT).unwrap()).contains(row.id());
        assertThat(tasks.restarted(claimedAt.plus(Duration.ofHours(2))).await(PgFixture.WAIT).unwrap()).doesNotContain(row.id());
        assertThat(extensions(row.id())).isEqualTo(1);
        TaskFixture.assertFoldEqualsRow(tasks, row.id(), "after one extension");
    }

    /// Review D1: restarts every 25 min (each followed by the start's sweep) do not keep a heartbeat-less lease LEASED;
    /// it is READY again well before 150 min.
    @Test
    void restarted_every25Minutes_doesNotKeepAHeartbeatlessLeaseAlive() {
        var claimedAt = TaskFixture.T0.plus(Duration.ofDays(1000));
        var row = TaskFixture.inCode(tasks, claimedAt);

        TaskFixture.claim(tasks, row.id(), "cycle", claimedAt).unwrap();
        for (var minutes = 25; minutes <= 150; minutes += 25) {
            var start = claimedAt.plus(Duration.ofMinutes(minutes));

            tasks.restarted(start).await(PgFixture.WAIT).unwrap();
            tasks.sweep(start).await(PgFixture.WAIT).unwrap();
        }
        assertThat(statusOf(row.id())).isEqualTo(Status.READY);
        assertThat(extensions(row.id())).isEqualTo(1);
    }

    /// Verifier T1: "once per epoch" counts the current epoch's extensions only. Claim, lapse, two restarts (one
    /// extension), expiry, re-claim, lapse, restart: the second epoch's lapse is extended exactly once, and the visit's
    /// second expiry then parks `stuck-lease`.
    @Test
    void restarted_extendsEachEpochOnce_thenTheSecondExpiryParks() {
        var claimedAt = TaskFixture.T0.plus(Duration.ofDays(1100));
        var row = TaskFixture.inCode(tasks, claimedAt);
        var first = TaskFixture.claim(tasks, row.id(), "epoch1", claimedAt).unwrap();

        assertThat(tasks.restarted(claimedAt.plus(Duration.ofHours(1))).await(PgFixture.WAIT).unwrap()).contains(row.id());
        assertThat(tasks.restarted(claimedAt.plus(Duration.ofHours(2))).await(PgFixture.WAIT).unwrap()).doesNotContain(row.id());
        tasks.sweep(claimedAt.plus(Duration.ofHours(2))).await(PgFixture.WAIT).unwrap();
        assertThat(statusOf(row.id())).isEqualTo(Status.READY);

        var second = TaskFixture.claim(tasks, row.id(), "epoch2", claimedAt.plus(Duration.ofHours(2))).unwrap();

        assertThat(second.epoch()).isGreaterThan(first.epoch());
        assertThat(tasks.restarted(claimedAt.plus(Duration.ofHours(3))).await(PgFixture.WAIT).unwrap()).contains(row.id());
        assertThat(tasks.restarted(claimedAt.plus(Duration.ofHours(4))).await(PgFixture.WAIT).unwrap()).doesNotContain(row.id());
        assertThat(extensions(row.id())).isEqualTo(2);

        tasks.sweep(claimedAt.plus(Duration.ofHours(4))).await(PgFixture.WAIT).unwrap();

        assertThat(stageOf(row.id())).isEqualTo(Stage.NEEDS_RULING);
        assertThat(tasks.detail(row.id()).await(PgFixture.WAIT).unwrap().rulings().getFirst().kind()).isEqualTo(RulingKind.STUCK_LEASE);
        TaskFixture.assertFoldEqualsRow(tasks, row.id(), "after an extension in each epoch and the stuck-lease park");
    }

    private long extensions(TaskId id) {
        return tasks.events(id).await(PgFixture.WAIT).unwrap().stream()
                    .filter(event -> event.event().kind() == TaskEvent.Kind.LEASE_EXTENDED)
                    .count();
    }

    @Test
    void waiting_resumes_onceEveryAwaitedJobIsTerminal() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var lease = TaskFixture.claim(tasks, row.id(), "h", TaskFixture.T0).unwrap();
        var job = linkedJob(lease);

        tasks.leased(lease, new Command.Wait(Waiting.waiting(List.of(job), false)), TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        tasks.sweep(TaskFixture.T0.plus(Duration.ofMinutes(1))).await(PgFixture.WAIT).unwrap();
        assertThat(statusOf(row.id())).isEqualTo(Status.WAITING);

        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        tasks.sweep(TaskFixture.T0.plus(Duration.ofMinutes(2))).await(PgFixture.WAIT).unwrap();

        var resumed = tasks.task(row.id()).await(PgFixture.WAIT).unwrap().unwrap();

        assertThat(resumed.state().status()).isEqualTo(Status.READY);
        assertThat(resumed.state().counters().visit()).isEqualTo(row.state().counters().visit());
        TaskFixture.assertFoldEqualsRow(tasks, row.id(), "after the resume");
    }

    @Test
    void wait_onAJobNotLinkedToTheTask_isJobNotLinked() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var lease = TaskFixture.claim(tasks, row.id(), "h", TaskFixture.T0).unwrap();
        var unlinked = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        tasks.leased(lease, new Command.Wait(Waiting.waiting(List.of(unlinked), false)), TaskFixture.T0)
             .await(PgFixture.WAIT)
             .onSuccess(task -> fail("waited on an unlinked job"))
             .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.JobNotLinked.FACTORY.apply(unlinked)));
        assertThat(statusOf(row.id())).isEqualTo(Status.LEASED);
    }

    @Test
    void park_thenRule_storesTheRequest_refusesAnOptionNotOffered_andWritesTheRulingOnce() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);

        tasks.command(row.id(), new Command.Park(TaskFixture.request(), false), "cto", TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        assertThat(stageOf(row.id())).isEqualTo(Stage.NEEDS_RULING);

        rule(row.id(), "z").onSuccess(task -> fail("ruled an option not offered"))
                           .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.OptionNotOffered.FACTORY.apply("z")));

        var ruled = rule(row.id(), "a").unwrap();
        var given = tasks.detail(row.id()).await(PgFixture.WAIT).unwrap().rulings().getFirst().ruling().unwrap();

        assertThat(ruled.state().stage()).isEqualTo(Stage.CODE);
        assertThat(ruled.state().status()).isEqualTo(Status.READY);
        assertThat(given.option()).isEqualTo("a");
        assertThat(given.text()).isEqualTo("go");
        assertThat(given.by()).isEqualTo("cto");
        rule(row.id(), "a").onSuccess(task -> fail("ruled twice"))
                           .onFailure(cause -> assertThat(cause).isEqualTo(TaskError.General.NOT_HELD));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE task_rulings SET ruling_text = 'other' WHERE task_id = %d",
                            "UPDATE task_rulings SET request = '{}'::jsonb WHERE task_id = %d",
                            "DELETE FROM task_rulings WHERE task_id = %d",
                            "TRUNCATE task_rulings",
                            "UPDATE task_events SET actor = 'x' WHERE task_id = %d",
                            "DELETE FROM task_events WHERE task_id = %d",
                            "TRUNCATE task_events CASCADE"})
    void writeOnce_tablesRefuseTheChange(String template) {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);

        tasks.command(row.id(), new Command.Park(TaskFixture.request(), false), "cto", TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        rule(row.id(), "a").unwrap();
        var outcome = harness.raw.executor().completeQuery(template.formatted(row.id().value())).await(PgFixture.WAIT);

        assertThat(outcome.isFailure()).as(template).isTrue();
        assertThat(harness.count("SELECT count(*) AS n FROM task_rulings WHERE task_id = $1 AND ruling_text = 'go'", row.id().value()))
            .isEqualTo(1);
    }

    /// Verifier V1, review D2: an open ruling is never partly written (the text alone, `ruled_at` alone, any three of the
    /// four ruling columns) and its request never changes, even together with the ruling columns; afterwards `rule`
    /// still writes it.
    @ParameterizedTest
    @ValueSource(strings = {"UPDATE task_rulings SET ruling_text = 'x' WHERE task_id = %d",
                            "UPDATE task_rulings SET ruled_at = now() WHERE task_id = %d",
                            "UPDATE task_rulings SET ruled_at = now(), ruled_by = 'cto', option = 'a' WHERE task_id = %d",
                            "UPDATE task_rulings SET ruled_at = now(), ruled_by = 'cto', ruling_text = 't' WHERE task_id = %d",
                            "UPDATE task_rulings SET ruled_at = now(), option = 'a', ruling_text = 't' WHERE task_id = %d",
                            "UPDATE task_rulings SET request = '{}'::jsonb, ruled_at = now(), ruled_by = 'cto', option = 'a', "
                            + "ruling_text = 't' WHERE task_id = %d"})
    void openRuling_isNeverPartlyWritten_andItsRequestNeverChanges(String template) {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);

        tasks.command(row.id(), new Command.Park(TaskFixture.request(), false), "cto", TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        var outcome = harness.raw.executor().completeQuery(template.formatted(row.id().value())).await(PgFixture.WAIT);

        assertThat(outcome.isFailure()).as(template).isTrue();
        assertThat(rule(row.id(), "a").isSuccess()).as("rule after the refused write").isTrue();
    }

    @Test
    void openRuling_isRefusedAnUpdateThatDoesNotRuleIt() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);

        tasks.command(row.id(), new Command.Park(TaskFixture.request(), false), "cto", TaskFixture.T0).await(PgFixture.WAIT).unwrap();

        assertThat(harness.raw.executor()
                              .completeQuery("UPDATE task_rulings SET kind = 'budget' WHERE task_id = $1", row.id().value())
                              .await(PgFixture.WAIT)
                              .isFailure()).isTrue();
    }

    /// Review #3: a job named twice is waited for once, and the wait settles once that job is terminal.
    @Test
    void waiting_onAJobNamedTwice_resumesOnceItIsTerminal() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var lease = TaskFixture.claim(tasks, row.id(), "dup", TaskFixture.T0).unwrap();
        var job = linkedJob(lease);

        tasks.leased(lease, new Command.Wait(Waiting.waiting(List.of(job, job), false)), TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        tasks.sweep(TaskFixture.T0.plus(Duration.ofMinutes(1))).await(PgFixture.WAIT).unwrap();

        assertThat(statusOf(row.id())).isEqualTo(Status.READY);
    }

    /// Review #3: an awaited job that is gone (retention removed it) counts as finished; it never holds the wait open.
    @Test
    void waiting_onAJobThatIsGone_resumes() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var lease = TaskFixture.claim(tasks, row.id(), "gone", TaskFixture.T0).unwrap();
        var job = linkedJob(lease);

        tasks.leased(lease, new Command.Wait(Waiting.waiting(List.of(job), false)), TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        harness.execute("DELETE FROM events WHERE job_id = $1", job.value());
        harness.execute("DELETE FROM units WHERE job_id = $1", job.value());
        harness.execute("DELETE FROM jobs WHERE id = $1", job.value());
        tasks.sweep(TaskFixture.T0.plus(Duration.ofMinutes(1))).await(PgFixture.WAIT).unwrap();

        assertThat(statusOf(row.id())).isEqualTo(Status.READY);
    }

    /// Review #6: a lease-scoped command is never the maintainer's: `command` refuses it and changes nothing.
    @Test
    void command_refusesALeaseScopedCommand() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);

        TaskFixture.claim(tasks, row.id(), "scoped", TaskFixture.T0).unwrap();
        var before = tasks.task(row.id()).await(PgFixture.WAIT).unwrap().unwrap();

        tasks.command(row.id(), new Command.Heartbeat(), "cto", TaskFixture.T0.plus(Duration.ofMinutes(5)))
             .await(PgFixture.WAIT)
             .onSuccess(task -> fail("a heartbeat without the lease"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(TaskError.Refused.class));
        assertThat(tasks.task(row.id()).await(PgFixture.WAIT).unwrap().unwrap()).isEqualTo(before);
    }

    /// Review #9: closing a NEEDS_RULING task writes its open request once, as closed, in the same transaction.
    @Test
    void closeAbandoned_whileNeedsRuling_closesTheOpenRuling() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);

        tasks.command(row.id(), new Command.Park(TaskFixture.request(), false), "cto", TaskFixture.T0).await(PgFixture.WAIT).unwrap();
        tasks.command(row.id(), new Command.CloseAbandoned(), "cto", TaskFixture.T0).await(PgFixture.WAIT).unwrap();

        var ruling = tasks.detail(row.id()).await(PgFixture.WAIT).unwrap().rulings().getFirst().ruling();

        assertThat(stageOf(row.id())).isEqualTo(Stage.ABANDONED);
        assertThat(ruling.map(TaskDetail.Given::option)).isEqualTo(some("closed"));
        assertThat(ruling.map(TaskDetail.Given::text)).isEqualTo(some("task closed"));
        assertThat(harness.count("SELECT count(*) AS n FROM task_rulings WHERE task_id = $1 AND ruled_at IS NULL", row.id().value())).isZero();
    }

    /// Review #1: the read-only lease check answers the lease that holds and refuses any other, writing nothing.
    @Test
    void holds_isTheLeaseAtItsEpochUnderItsToken() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var lease = TaskFixture.claim(tasks, row.id(), "holds", TaskFixture.T0).unwrap();

        assertThat(tasks.holds(lease).await(PgFixture.WAIT).isSuccess()).isTrue();
        assertLeaseLost(tasks.holds(Lease.lease(row.id(), lease.epoch(), TaskFixture.hash("other"))).await(PgFixture.WAIT));
        assertLeaseLost(tasks.holds(Lease.lease(row.id(), lease.epoch() - 1, lease.tokenHash())).await(PgFixture.WAIT));
    }

    /// Review #8, D4: the table refuses what no command writes: a status outside its stages, a lease hash that is not a
    /// sha256, a column its status does not imply (or missing one it does), a job linked to a task without an epoch.
    /// The task is CODE/READY; a LEASED row is written with all three lease columns so only the named rule is broken.
    @ParameterizedTest
    @ValueSource(strings = {"UPDATE tasks SET status = 'HELD' WHERE id = %d",
                            "UPDATE tasks SET stage = 'TRIAGED', status = 'LEASED', " + LEASE_COLUMNS + " WHERE id = %d",
                            "UPDATE tasks SET stage = 'CI', status = 'LEASED', " + LEASE_COLUMNS + " WHERE id = %d",
                            "UPDATE tasks SET stage = 'CI', status = 'READY' WHERE id = %d",
                            "UPDATE tasks SET raised_from = 'CODE' WHERE id = %d",
                            "UPDATE tasks SET stage = 'NEEDS_RULING', status = 'HELD' WHERE id = %d",
                            "UPDATE tasks SET status = 'LEASED', lease_hash = repeat('a', 64), lease_until = now() WHERE id = %d",
                            "UPDATE tasks SET status = 'LEASED', lease_hash = repeat('a', 64), step_deadline = now() WHERE id = %d",
                            "UPDATE tasks SET status = 'LEASED', lease_until = now(), step_deadline = now() WHERE id = %d",
                            "UPDATE tasks SET lease_until = now() WHERE id = %d",
                            "UPDATE tasks SET closed_at = now() WHERE id = %d",
                            "UPDATE tasks SET stage = 'ABANDONED', status = 'CLOSED' WHERE id = %d",
                            "UPDATE tasks SET stage = 'NEEDS_RULING', status = 'READY' WHERE id = %d",
                            "UPDATE tasks SET stage = 'MERGED', status = 'READY' WHERE id = %d",
                            "UPDATE tasks SET status = 'CLOSED' WHERE id = %d",
                            "UPDATE tasks SET status = 'LEASED', lease_hash = 'not-a-sha256', lease_until = now(), step_deadline = now() WHERE id = %d",
                            "UPDATE jobs SET task_id = %d, task_epoch = NULL WHERE id = (SELECT max(id) FROM jobs)"})
    void checks_refuseInconsistentRows(String template) {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);

        harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var outcome = harness.raw.executor().completeQuery(template.formatted(row.id().value())).await(PgFixture.WAIT);

        assertThat(outcome.isFailure()).as(template).isTrue();
    }

    /// The control of [#checks_refuseInconsistentRows]: a consistent LEASED row written the same raw way is accepted.
    @Test
    void checks_acceptAConsistentLeasedRow() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);

        assertThat(harness.raw.executor()
                              .completeQuery(("UPDATE tasks SET status = 'LEASED', " + LEASE_COLUMNS + " WHERE id = %d").formatted(row.id().value()))
                              .await(PgFixture.WAIT)
                              .isSuccess()).isTrue();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------
    private static void assertLeaseLost(Result<?> outcome) {
        assertThat(outcome.isFailure()).as(outcome.toString()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(TaskError.General.LEASE_LOST));
    }

    private static NewTask task(int priority, boolean critical) {
        return NewTask.newTask("o-" + priority + "-" + System.nanoTime() % 100000,
                               "Ordered",
                               List.of(),
                               TaskBrief.taskBrief("briefs/o.md", none(), false, Map.of()).unwrap(),
                               "pragmaticalabs/pragmatica",
                               "main",
                               "agent/o",
                               none(),
                               critical,
                               priority,
                               List.of(),
                               3,
                               "cto")
                      .unwrap();
    }

    private static TaskId advanced(NewTask task, Instant at) {
        var row = tasks.create(task, at).await(PgFixture.WAIT).unwrap();

        tasks.sweep(at).await(PgFixture.WAIT).unwrap();
        return row.id();
    }

    /// A job submitted under `lease` at the fixture's T0 (its time restarts the lease's TTL).
    private static JobId linkedJob(Lease lease) {
        return linkedJob(lease, TaskFixture.T0);
    }

    private static JobId linkedJob(Lease lease, Instant at) {
        return harness.store.jobs().submit(TestJobs.bySha(TestJobs.key(), false).underLease(lease, at)).await(PgFixture.WAIT).unwrap().job();
    }

    private static Result<TaskRow> rule(TaskId id, String option) {
        return tasks.command(id,
                             new Command.Rule(RulingDecision.rulingDecision(option, "go", none(), none()).unwrap()),
                             "cto",
                             TaskFixture.T0)
                    .await(PgFixture.WAIT);
    }

    private static Instant readySince(TaskId id) {
        return tasks.task(id).await(PgFixture.WAIT).unwrap().unwrap().state().readySince().unwrap();
    }

    private static Stage stageOf(TaskId id) {
        return tasks.task(id).await(PgFixture.WAIT).unwrap().unwrap().state().stage();
    }

    private static Status statusOf(TaskId id) {
        return tasks.task(id).await(PgFixture.WAIT).unwrap().unwrap().state().status();
    }
}
