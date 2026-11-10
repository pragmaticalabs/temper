package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.ReleaseReason;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.Waiting;
import org.pragmatica.temper.store.api.ClaimGrant;
import org.pragmatica.temper.store.api.SubmitOutcome;
import org.pragmatica.temper.store.api.TaskStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// m3-design §11.5 `FencingIT` (§6.4): a lease is accepted only at its own epoch under its own token. Claim (lease 1),
/// expire, claim again (lease 2): lease 1's heartbeat, evidence and linked submit (accepted or refused) are all
/// `lease-lost` and write nothing; lease 2's token presented with lease 1's epoch is refused too; lease 2 works. And of
/// 200 concurrent claims of one READY task exactly one wins.
///
/// Probes: drop `lease_epoch` from the fence's UPDATE predicate (`currentToken_atAnEarlierEpoch_isLeaseLost` goes red);
/// claim without `SKIP LOCKED` and without the READY condition (`claimRacingAWinnerHoldingTheRow_neverLeasesTwice` goes
/// red). The 200 concurrent claims alone did not catch that mutation here: on this pool the claims ran without ever
/// overlapping, so the race is also staged deterministically, a raw transaction holding the row as a winner mid-claim.
@Tag("pg")
class FencingIT {
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

    /// Lease 1, expired by a sweep past its TTL, and lease 2.
    private record Leases(Lease first, Lease second) {}

    private static Leases reclaimed() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var first = TaskFixture.claim(tasks, row.id(), "hash-1", TaskFixture.T0).unwrap();
        var expiredAt = TaskFixture.T0.plus(Duration.ofMinutes(31));

        tasks.sweep(expiredAt).await(PgFixture.WAIT).unwrap();
        var second = TaskFixture.claim(tasks, row.id(), "hash-2", expiredAt).unwrap();

        assertThat(second.epoch()).isGreaterThan(first.epoch());
        return new Leases(first, second);
    }

    @Test
    void staleLease_heartbeat_isLeaseLost_andWritesNoEvent() {
        var leases = reclaimed();
        var events = events(leases);

        assertLeaseLost(tasks.leased(leases.first(), new Command.Heartbeat(), TaskFixture.T0.plus(Duration.ofMinutes(32))));
        assertThat(events(leases)).isEqualTo(events);
    }

    @Test
    void staleLease_evidence_isLeaseLost_andStoresNoEvidence() {
        var leases = reclaimed();

        assertLeaseLost(tasks.evidence(leases.first(), TaskFixture.evidence(), TaskFixture.next(Stage.VERIFY), TaskFixture.T0.plus(Duration.ofMinutes(32))));
        assertThat(harness.count("SELECT count(*) AS n FROM task_evidence WHERE task_id = $1", leases.first().task().value())).isZero();
    }

    @Test
    void staleLease_linkedSubmit_isLeaseLost_andInsertsNoJob() {
        var leases = reclaimed();

        assertLeaseLost(harness.store.jobs().submit(TestJobs.bySha(TestJobs.key(), false).underLease(leases.first(), TaskFixture.T0.plus(Duration.ofMinutes(32)))));
        assertLeaseLost(harness.store.jobs()
                                     .recordRefused(TestJobs.bySha(TestJobs.key(), false).underLease(leases.first(), TaskFixture.T0.plus(Duration.ofMinutes(32))),
                                                    Refusal.refusal(RefusalCode.SOURCE, "no such ref")));
        assertThat(harness.count("SELECT count(*) AS n FROM jobs WHERE task_id = $1", leases.first().task().value())).isZero();
    }

    /// Verifier G1: a stale lease's wait, release and park are refused like its heartbeat, writing no event; and the same
    /// lease-scoped commands are refused through the maintainer's `command`, which has no lease to fence them with.
    @Test
    void staleLease_waitReleaseAndPark_areLeaseLost_andNeverTheCtosToSend() {
        var leases = reclaimed();
        var stale = leases.first();
        var at = TaskFixture.T0.plus(Duration.ofMinutes(32));
        // A job linked under the current lease, so the stale wait passes every check but the fence.
        var linked = harness.store.jobs()
                                  .submit(TestJobs.bySha(TestJobs.key(), false).underLease(leases.second(), at))
                                  .await(PgFixture.WAIT)
                                  .unwrap()
                                  .job();
        var events = events(leases);

        assertLeaseLost(tasks.leased(stale, new Command.Wait(Waiting.waiting(List.of(linked), false)), at));
        assertLeaseLost(tasks.leased(stale, new Command.Release(ReleaseReason.releaseReason("crash").unwrap()), at));
        assertLeaseLost(tasks.leased(stale, new Command.Park(TaskFixture.request(), true), at));
        assertThat(events(leases)).isEqualTo(events);
        for (var command : List.of(new Command.Release(ReleaseReason.releaseReason("crash").unwrap()),
                                   new Command.Park(TaskFixture.request(), true),
                                   new Command.Heartbeat())) {
            tasks.command(stale.task(), command, "cto", at)
                 .await(PgFixture.WAIT)
                 .onSuccess(row -> fail(command.name() + " applied without a lease"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(TaskError.Refused.class));
        }
        assertThat(events(leases)).isEqualTo(events);
    }

    @Test
    void currentToken_atAnEarlierEpoch_isLeaseLost() {
        var leases = reclaimed();
        var mixed = Lease.lease(leases.second().task(), leases.first().epoch(), leases.second().tokenHash());

        assertLeaseLost(tasks.leased(mixed, new Command.Heartbeat(), TaskFixture.T0.plus(Duration.ofMinutes(32))));
        assertLeaseLost(harness.store.jobs().submit(TestJobs.bySha(TestJobs.key(), false).underLease(mixed, TaskFixture.T0.plus(Duration.ofMinutes(32)))));
    }

    @Test
    void currentLease_heartbeat_andLinkedSubmit_areAccepted_atItsEpoch() {
        var leases = reclaimed();
        var second = leases.second();

        tasks.leased(second, new Command.Heartbeat(), TaskFixture.T0.plus(Duration.ofMinutes(33))).await(PgFixture.WAIT).unwrap();
        var job = harness.store.jobs()
                               .submit(TestJobs.bySha(TestJobs.key(), false).underLease(second, TaskFixture.T0.plus(Duration.ofMinutes(33))))
                               .await(PgFixture.WAIT)
                               .unwrap();

        assertThat(job).isInstanceOf(SubmitOutcome.Accepted.class);
        assertThat(harness.count("SELECT count(*) AS n FROM jobs WHERE id = $1 AND task_id = $2 AND task_epoch = $3",
                                 job.job().value(),
                                 second.task().value(),
                                 second.epoch())).isEqualTo(1);
        TaskFixture.assertFoldEqualsRow(tasks, second.task(), "after the linked submit");
    }

    @Test
    void concurrentClaims_exactlyOneWins() {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var claims = IntStream.range(0, 200)
                              .mapToObj(n -> tasks.claim(row.id(),
                                                         ClaimGrant.claimGrant(TaskFixture.hash("hash-" + n), "w" + n, some(TaskFixture.BASE_TIP), none()),
                                                         TaskFixture.T0))
                              .toList();
        var outcomes = claims.stream().map(claim -> claim.await(PgFixture.WAIT)).toList();
        var won = outcomes.stream().filter(Result::isSuccess).count();

        assertThat(won).as("claims won").isEqualTo(1);
        outcomes.stream()
                .filter(Result::isFailure)
                .forEach(lost -> lost.onFailure(cause -> assertThat(cause).isEqualTo(TaskError.General.NOT_READY)));
        assertThat(tasks.task(row.id()).await(PgFixture.WAIT).unwrap().unwrap().state().epoch()).isEqualTo(1);
        assertThat(harness.count("SELECT count(*) AS n FROM task_events WHERE task_id = $1 AND kind = 'leased'", row.id().value())).isEqualTo(1);
    }

    /// A winner mid-claim holds the row (locked, READY as read); a second claim runs; then the winner commits LEASED. The
    /// second claim must skip the held row (`not-ready`), never read it READY and overwrite the winner's lease.
    @Test
    void claimRacingAWinnerHoldingTheRow_neverLeasesTwice() throws InterruptedException {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);
        var id = row.id().value();
        var locked = Promise.<Unit> promise();
        var commit = Promise.<Unit> promise();
        var winner = harness.raw.inTransaction(tx -> Sql.update(tx, "SELECT id FROM tasks WHERE id = $1 FOR UPDATE", id)
                                                        .withSuccess(_ -> locked.succeed(Unit.unit()))
                                                        .flatMap(_ -> commit)
                                                        .flatMap(_ -> Sql.update(tx,
                                                                                 "UPDATE tasks SET status = 'LEASED', lease_epoch = lease_epoch + 1, "
                                                                                 + "lease_hash = $2, lease_until = now(), step_deadline = now(), "
                                                                                 + "ready_since = NULL WHERE id = $1",
                                                                                 id,
                                                                                 TaskFixture.hash("winner"))));

        locked.await(PgFixture.WAIT).unwrap();
        // The instrument first: the row is held, so another transaction cannot take it without waiting.
        assertThat(harness.raw.executor()
                              .completeQuery("SELECT id FROM tasks WHERE id = $1 FOR UPDATE NOWAIT", id)
                              .await(PgFixture.WAIT)
                              .isFailure()).as("the winner holds the row").isTrue();
        var racing = tasks.claim(row.id(), ClaimGrant.claimGrant(TaskFixture.hash("racer"), "r", some(TaskFixture.BASE_TIP), none()), TaskFixture.T0);

        Thread.sleep(500);
        commit.succeed(Unit.unit());
        assertThat(winner.await(PgFixture.WAIT).unwrap()).as("the winner's update").isEqualTo(1);

        racing.await(PgFixture.WAIT)
              .onSuccess(claimed -> fail("a second lease over the winner's: " + claimed.row().state().lease()))
              .onFailure(cause -> assertThat(cause).isEqualTo(TaskError.General.NOT_READY));
        assertThat(harness.count("SELECT count(*) AS n FROM tasks WHERE id = $1 AND lease_epoch = 1 AND lease_hash = $2", id, TaskFixture.hash("winner")))
            .isEqualTo(1);
    }

    private static List<?> events(Leases leases) {
        return tasks.events(leases.first().task()).await(PgFixture.WAIT).unwrap();
    }

    private static <T> void assertLeaseLost(Promise<T> call) {
        call.await(PgFixture.WAIT)
            .onSuccess(value -> fail("accepted under a stale lease: " + value))
            .onFailure(cause -> assertThat(cause).isEqualTo(TaskError.General.LEASE_LOST));
    }
}
