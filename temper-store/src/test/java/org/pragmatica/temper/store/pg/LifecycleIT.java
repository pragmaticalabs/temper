package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;
import org.pragmatica.temper.domain.refusal.RefusalRule;
import org.pragmatica.temper.domain.refusal.TestRunner;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.protocol.api.EventKind;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.EvidenceDto;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.store.api.CancelOutcome;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.HostConfig;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobQuery;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.SubmitOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The job lifecycle through the repositories against real Postgres: submit → grant → started → complete, the
/// result record, events, cancel and supersede (spec §2.4), refused jobs, reads, hosts and refusals.
@Tag("pg")
class LifecycleIT {
    private StoreHarness harness;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Nested
    class Completion {
        /// maintainer ruling (M1): Testcontainers leftovers are a flag, never a verdict input. A gate cannot attribute a
        /// leftover to the unit with certainty, so a unit whose results pass stays PASSED, leftovers and an
        /// unverified reap alike, and the record carries them for the reader.
        @Test
        void complete_leftoversNeverChangeAPassingVerdict_theyAreOnlyFlagged() {
            var leftovers = TcLeftovers.tcLeftovers(List.of(TcLeftovers.TcLeftover.tcLeftover("tc-a", "s-1"),
                                                            TcLeftovers.TcLeftover.tcLeftover("tc-b", "s-2")),
                                                    some("docker ps failed"));
            var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                                  TestJobs.exited(0, leftovers),
                                  List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED)));
            var row = job(job);

            assertThat(row.units().getFirst().state()).isEqualTo(UnitState.PASSED);
            assertThat(row.state()).isEqualTo(JobState.PASSED);
            assertThat(row.verdict()).isEqualTo(some("PASSED"));
            assertThat(result(job).verdict()).isEqualTo("PASSED");
            assertThat(result(job).units().getFirst().tcLeftovers()).isEqualTo(some(TcLeftoversDto.tcLeftoversDto(leftovers)));
        }

        @Test
        void complete_passedJob_writesUnitResultAndEvents() {
            var tricky = "param [1] a, \"b\" \\ {c} NULL é";
            var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                                  TestJobs.exited(0),
                                  List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED),
                                          TestJobs.row("a.ATest", tricky, Outcome.SKIPPED)));
            var row = job(job);

            assertThat(row.state()).isEqualTo(JobState.PASSED);
            assertThat(row.verdict()).isEqualTo(some("PASSED"));
            assertThat(row.finishedAt().isPresent()).isTrue();
            var unit = row.units().getFirst();

            assertThat(unit.state()).isEqualTo(UnitState.PASSED);
            assertThat(unit.rc()).isEqualTo(some(0));
            assertThat(unit.containerId()).isEqualTo(some("c0ffee"));
            assertThat(unit.markerEpochNs()).isEqualTo(some(1760349600000000000L));
            assertThat(unit.host()).isEqualTo(some(TestJobs.HOST));
            assertThat(unit.testsAttr()).isEqualTo(some(3L));
            assertThat(unit.tcLeftovers()).isEqualTo(some(TestJobs.LEFTOVERS));

            var record = result(job);

            assertThat(record.verdict()).isEqualTo("PASSED");
            assertThat(record.counts().testcases()).isEqualTo(2);
            assertThat(record.counts().skipped()).isEqualTo(1);
            assertThat(record.counts().testsAttr()).isEqualTo(3);
            assertThat(record.units().getFirst().rc()).isEqualTo(some(0));
            assertThat(record.units().getFirst().tcLeftovers()).isEqualTo(some(TcLeftoversDto.tcLeftoversDto(TestJobs.LEFTOVERS)));
            assertThat(count("SELECT count(*) AS n FROM unit_facts f JOIN units u ON u.id = f.unit_id WHERE u.job_id = "
                             + job.value() + " AND f.phase = 'end' AND f.facts -> 'tcLeftovers' -> 'containers' -> 0 ->> 'id' = 'tc-left'"))
                .isEqualTo(1);
            assertThat(record.timings().run()).isEqualTo(some(Duration.ofMinutes(30)));
            assertThat(record.evidence().stored()).isFalse();
            assertThat(record.annotations()).as("a unit with a marker and no small tarball").containsExactly(EvidenceDto.MISSING_ANNOTATION);

            assertThat(harness.store.results().testcases(job, some(Outcome.SKIPPED)).await(PgFixture.WAIT).unwrap())
                .extracting(testcase -> testcase.name())
                .containsExactly(tricky);
            assertThat(kinds(job)).containsExactly(EventKind.QUEUED, EventKind.STARTED, EventKind.UNIT_FINISHED, EventKind.FINISHED);
            assertThat(count("SELECT count(*) AS n FROM unit_facts")).isEqualTo(2);
            assertThat(lastEvent(job).payload().verdict()).isEqualTo(some("PASSED"));
            assertThat(lastEvent(job).payload().counts().map(counts -> counts.testcases())).isEqualTo(some(2));
        }

        @Test
        void complete_failedTestcase_isFailedTests_andRepeatIsIdempotent() {
            var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                                  TestJobs.exited(1),
                                  List.of(TestJobs.row("a.ATest", "one", Outcome.FAILED)));

            assertThat(job(job).state()).isEqualTo(JobState.FAILED);
            assertThat(result(job).reason()).isEqualTo(some("tests"));
            assertThat(result(job).failed()).hasSize(1);

            var again = harness.complete(job, TestJobs.exited(0), List.of(TestJobs.row("a.ATest", "two", Outcome.PASSED)));

            assertThat(again).isEqualTo(new CompleteOutcome.AlreadyComplete(TestJobs.main(job), UnitState.FAILED));
            assertThat(harness.store.results().testcases(job, none()).await(PgFixture.WAIT).unwrap()).hasSize(1);
        }

        @Test
        void complete_rcZeroWithoutTestcases_isEmpty_andTimeoutIsFailedTimeout() {
            var empty = harness.run(TestJobs.bySha(TestJobs.key(), false), TestJobs.exited(0), List.of());
            var timedOut = harness.run(TestJobs.bySha(TestJobs.key(), false), TestJobs.killed(KillCause.TIMEOUT), List.of());

            assertThat(job(empty).state()).isEqualTo(JobState.EMPTY);
            assertThat(job(timedOut).state()).isEqualTo(JobState.FAILED);
            assertThat(result(timedOut).reason()).isEqualTo(some("timeout"));
        }

        @Test
        void complete_lostUnit_isErrorLost_andReasonSurvivesInTheRow() {
            var job = harness.run(TestJobs.bySha(TestJobs.key(), false), TestJobs.lost(), List.of());
            var row = job(job);

            assertThat(row.state()).isEqualTo(JobState.ERROR);
            assertThat(row.reason()).isEqualTo(some("lost"));
            assertThat(row.units().getFirst().reason()).isEqualTo(some("lost"));
            assertThat(row.units().getFirst().state()).isEqualTo(UnitState.ERROR);
            assertThat(row.units().getFirst().tcLeftovers()).isEqualTo(none());
            assertThat(result(job).units().getFirst().tcLeftovers()).isEqualTo(none());
        }

        @Test
        void complete_rowOfAnotherUnit_isRejectedWithoutWriting() {
            var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

            harness.grantHead();
            harness.start(job);
            var foreign = TestcaseRow.testcaseRow(UnitName.unitName("other").unwrap(), "a.ATest", "one", Outcome.PASSED, 1, false);

            harness.store.units()
                         .complete(TestJobs.HOST, TestJobs.main(job), 1, TestJobs.exited(0), List.of(foreign), none(), none(), TestJobs::evaluate)
                         .await(PgFixture.WAIT)
                         .onSuccess(outcome -> fail("expected ForeignTestcase, got " + outcome))
                         .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.ForeignTestcase.class));
            assertThat(job(job).units().getFirst().state()).isEqualTo(UnitState.RUNNING);
        }

        @Test
        void complete_decisionDeclined_leavesJobRunning() {
            var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

            harness.grantHead();
            harness.start(job);
            var outcome = harness.store.units()
                                       .complete(TestJobs.HOST, TestJobs.main(job), 1, TestJobs.exited(0), List.of(), none(), none(), _ -> none())
                                       .await(PgFixture.WAIT)
                                       .unwrap();

            assertThat(outcome).isEqualTo(new CompleteOutcome.Completed(TestJobs.main(job), UnitState.EMPTY, none()));
            assertThat(job(job).state()).isEqualTo(JobState.RUNNING);
            assertThat(harness.store.results().result(job).await(PgFixture.WAIT).unwrap().isEmpty()).isTrue();
        }

        @Test
        void complete_requeuedUnit_isNotActive_andWritesNothing() {
            var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

            harness.grantHead();
            harness.store.units().requeue(TestJobs.HOST, TestJobs.main(job), 1).await(PgFixture.WAIT).unwrap();
            var outcome = harness.complete(job, TestJobs.exited(0), List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED)));

            assertThat(outcome).isEqualTo(new CompleteOutcome.NotActive(TestJobs.main(job), UnitState.QUEUED));
            assertThat(job(job).units().getFirst().state()).isEqualTo(UnitState.QUEUED);
            assertThat(harness.store.results().testcases(job, none()).await(PgFixture.WAIT).unwrap()).isEmpty();
        }

        /// A unit made active again after its job finished (only possible by hand) must not try a second result row.
        @Test
        void complete_onFinishedJob_doesNotWriteASecondResult() {
            var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                                  TestJobs.exited(0),
                                  List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED)));

            exec("UPDATE units SET state = 'RUNNING' WHERE job_id = " + job.value());
            var outcome = harness.complete(job, TestJobs.exited(0), List.of());

            assertThat(outcome).isEqualTo(new CompleteOutcome.Completed(TestJobs.main(job), UnitState.EMPTY, none()));
            assertThat(result(job).verdict()).isEqualTo("PASSED");
        }

        @Test
        void started_isIdempotent_andEmitsStartedOnce() {
            var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

            harness.grantHead();
            harness.start(job);
            harness.start(job);

            assertThat(kinds(job)).containsExactly(EventKind.QUEUED, EventKind.STARTED);
            assertThat(job(job).state()).isEqualTo(JobState.RUNNING);
        }
    }

    @Nested
    class Cancel {
        @Test
        void requestCancel_queuedJob_isCancelledNow_withResult() {
            var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
            var outcome = cancel(job);

            assertThat(outcome).isEqualTo(new CancelOutcome.Cancelled(job, JobState.CANCELLED));
            assertThat(job(job).units().getFirst().state()).isEqualTo(UnitState.CANCELLED);
            assertThat(result(job).verdict()).isEqualTo("CANCELLED");
            assertThat(kinds(job)).containsExactly(EventKind.QUEUED, EventKind.CANCELLED);
            assertThat(cancel(job)).isEqualTo(new CancelOutcome.AlreadyTerminal(job, JobState.CANCELLED));
        }

        @Test
        void requestCancel_runningJob_isCancelling_thenCancelledOnCompletion() {
            var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

            harness.grantHead();
            harness.start(job);

            assertThat(cancel(job)).isEqualTo(new CancelOutcome.Cancelling(job, List.of(TestJobs.main(job))));
            assertThat(cancel(job)).as("a repeat resends the kill list")
                                   .isEqualTo(new CancelOutcome.Cancelling(job, List.of(TestJobs.main(job))));
            assertThat(job(job).state()).isEqualTo(JobState.CANCELLING);

            var outcome = harness.complete(job, TestJobs.killed(KillCause.CANCEL), List.of());

            assertThat(outcome).isEqualTo(new CompleteOutcome.Completed(TestJobs.main(job), UnitState.CANCELLED, some(JobState.CANCELLED)));
            assertThat(result(job).verdict()).isEqualTo("CANCELLED");
            assertThat(kinds(job)).endsWith(EventKind.UNIT_FINISHED, EventKind.CANCELLED);
        }

        @Test
        void requestCancel_grantedUnit_isCancelling() {
            var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

            harness.grantHead();

            assertThat(cancel(job)).isInstanceOf(CancelOutcome.Cancelling.class);
            harness.start(job);
            assertThat(job(job).units().getFirst().state()).as("a start racing the cancel keeps CANCELLING")
                                                           .isEqualTo(UnitState.CANCELLING);
            assertThat(job(job).units().getFirst().startedAt().isPresent()).isTrue();
        }

        @Test
        void requestCancel_unknownJob_fails() {
            harness.store.jobs()
                         .requestCancel(JobId.jobId(424242).unwrap())
                         .await(PgFixture.WAIT)
                         .onSuccess(outcome -> fail("expected JobNotFound, got " + outcome))
                         .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.JobNotFound.class));
        }
    }

    @Nested
    class Supersede {
        @Test
        void submitByRef_cancelsEarlierQueuedJob_atAnotherSha() {
            var old = harness.submit(TestJobs.byRef("feature/x", TestJobs.sha(), false, -20));
            var outcome = harness.submitOutcome(TestJobs.byRef("feature/x", TestJobs.sha(), false, -20));
            var oldRow = job(old);

            assertThat(outcome).isInstanceOf(SubmitOutcome.Accepted.class);
            assertThat(oldRow.state()).isEqualTo(JobState.CANCELLED);
            assertThat(oldRow.superseded()).isTrue();
            assertThat(oldRow.supersededBy()).isEqualTo(some(outcome.job()));
            assertThat(result(old).annotations()).as("never started: nothing to pull").containsExactly("superseded");
            assertThat(kinds(old)).containsExactly(EventKind.QUEUED, EventKind.SUPERSEDED, EventKind.CANCELLED);
            assertThat(kinds(outcome.job())).containsExactly(EventKind.QUEUED);
        }

        @Test
        void submitByRef_runningUnkeptJob_goesCancelling_andIsReturnedForKill() {
            var old = harness.submit(TestJobs.byRef("feature/y", TestJobs.sha(), false, -20));

            harness.grantHead();
            harness.start(old);
            var outcome = harness.submitOutcome(TestJobs.byRef("feature/y", TestJobs.sha(), false, -20));

            assertThat(outcome).isEqualTo(new SubmitOutcome.Accepted(outcome.job(), List.of(TestJobs.main(old))));
            assertThat(job(old).state()).isEqualTo(JobState.CANCELLING);
            assertThat(job(old).superseded()).isTrue();
        }

        @Test
        void submitByRef_keptJob_runsOn_markedSuperseded() {
            var old = harness.submit(TestJobs.byRef("feature/z", TestJobs.sha(), true, -20));

            harness.grantHead();
            harness.start(old);
            var outcome = harness.submitOutcome(TestJobs.byRef("feature/z", TestJobs.sha(), false, -20));

            assertThat(outcome).isEqualTo(new SubmitOutcome.Accepted(outcome.job(), List.of()));
            assertThat(job(old).state()).isEqualTo(JobState.RUNNING);
            assertThat(job(old).superseded()).isTrue();

            harness.complete(old, TestJobs.exited(0), List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED)));
            assertThat(result(old).verdict()).isEqualTo("PASSED");
            assertThat(result(old).annotations()).containsExactly("superseded", EvidenceDto.MISSING_ANNOTATION);
        }

        @Test
        void submitByRef_queuedKeptJob_isCancelledToo() {
            var old = harness.submit(TestJobs.byRef("feature/k", TestJobs.sha(), true, -20));

            harness.submit(TestJobs.byRef("feature/k", TestJobs.sha(), false, -20));

            assertThat(job(old).state()).as("keep exempts only a RUNNING job (spec §2.4)").isEqualTo(JobState.CANCELLED);
        }

        @Test
        void supersededJob_isNotSupersededAgain_andUserCancelIsNotASupersede() {
            var kept = harness.submit(TestJobs.byRef("feature/r", TestJobs.sha(), true, -20));

            harness.grantHead();
            harness.start(kept);
            var first = harness.submit(TestJobs.byRef("feature/r", TestJobs.sha(), false, -20));

            harness.submit(TestJobs.byRef("feature/r", TestJobs.sha(), false, -20));

            assertThat(job(kept).supersededBy()).isEqualTo(some(first));
            assertThat(kinds(kept).stream().filter(kind -> kind == EventKind.SUPERSEDED).count()).isEqualTo(1);

            var cancelled = harness.submit(TestJobs.byRef("feature/c", TestJobs.sha(), false, 10));

            harness.grantHead();
            harness.start(cancelled);
            cancel(cancelled);
            harness.submit(TestJobs.byRef("feature/c", TestJobs.sha(), false, -20));

            assertThat(job(cancelled).superseded()).isFalse();
            assertThat(job(cancelled).state()).isEqualTo(JobState.CANCELLING);
        }

        /// Two pushes to one ref racing, 20 rounds: the earlier job must always end superseded. Without the
        /// `(ref, recipe)` advisory lock neither transaction sees the other's uncommitted job.
        @Test
        void concurrentPushesToOneRef_alwaysSupersedeTheEarlier() {
            var other = PgStore.pgStore(PgFixture.db(harness.config), Judge.UNJUDGED);

            try {
                for (var round = 0; round < 20; round++) {
                    var ref = "race/" + round;
                    var outcomes = Promise.allOf(List.of(harness.store.jobs()
                                                                                          .submit(TestJobs.byRef(ref, TestJobs.sha(), false, -20)),
                                                                             other.jobs()
                                                                                  .submit(TestJobs.byRef(ref, TestJobs.sha(), false, -20))))
                                                              .await(PgFixture.WAIT)
                                                              .unwrap();
                    var ids = outcomes.stream().map(outcome -> outcome.unwrap().job()).sorted(Comparator.comparingLong(JobId::value)).toList();

                    assertThat(job(ids.getFirst()).state()).as(ref).isEqualTo(JobState.CANCELLED);
                    assertThat(job(ids.getLast()).state()).as(ref).isEqualTo(JobState.QUEUED);
                }
            } finally {
                other.close();
            }
        }

        /// R-M2-8: the supersede key is the full recipe (spec §2.4), not its kind. Two module-test jobs on one branch
        /// with different `pl` are different recipes and must both survive.
        @Test
        void submitByRef_moduleTestWithOtherPl_doesNotSupersede() {
            var core = harness.submit(TestJobs.byRef("feature/pl", TestJobs.sha(), false, -20, TestJobs.moduleTest("core")));
            var store = harness.submit(TestJobs.byRef("feature/pl", TestJobs.sha(), false, -20, TestJobs.moduleTest("store")));

            assertThat(job(core).state()).isEqualTo(JobState.QUEUED);
            assertThat(job(core).superseded()).isFalse();
            assertThat(job(store).state()).isEqualTo(JobState.QUEUED);
            assertThat(job(store).superseded()).isFalse();
        }

        @Test
        void submitByRef_sameModuleTestRecipe_supersedesTheEarlier() {
            var old = harness.submit(TestJobs.byRef("feature/mt", TestJobs.sha(), false, -20, TestJobs.moduleTest("core", "store")));
            var outcome = harness.submitOutcome(TestJobs.byRef("feature/mt", TestJobs.sha(), false, -20, TestJobs.moduleTest("core", "store")));

            assertThat(job(old).state()).isEqualTo(JobState.CANCELLED);
            assertThat(job(old).supersededBy()).isEqualTo(some(outcome.job()));
        }

        @Test
        void submitByRef_gate_supersedesTheEarlierGate() {
            var old = harness.submit(TestJobs.byRef("feature/g", TestJobs.sha(), false, -20, Recipe.gate()));
            var outcome = harness.submitOutcome(TestJobs.byRef("feature/g", TestJobs.sha(), false, -20, Recipe.gate()));

            assertThat(job(old).state()).isEqualTo(JobState.CANCELLED);
            assertThat(job(old).supersededBy()).isEqualTo(some(outcome.job()));
        }

        /// Priority and keep are not part of the supersede key: a push that changes only them still supersedes.
        @Test
        void submitByRef_otherPriorityAndKeepOnly_stillSupersedes() {
            var old = harness.submit(TestJobs.byRef("feature/p", TestJobs.sha(), false, -20, TestJobs.moduleTest("core")));
            var outcome = harness.submitOutcome(TestJobs.byRef("feature/p", TestJobs.sha(), true, 10, TestJobs.moduleTest("core")));

            assertThat(job(old).state()).isEqualTo(JobState.CANCELLED);
            assertThat(job(old).supersededBy()).isEqualTo(some(outcome.job()));
        }

        @Test
        void submitBySha_neverSupersedes_andSameShaNeverSupersedes() {
            var sha = TestJobs.sha();
            var byRef = harness.submit(TestJobs.byRef("feature/w", sha, false, -20));

            harness.submit(TestJobs.bySha(TestJobs.key(), false));
            harness.submit(TestJobs.byRef("feature/w", sha, false, -20));

            assertThat(job(byRef).state()).isEqualTo(JobState.QUEUED);
            assertThat(job(byRef).superseded()).isFalse();
        }
    }

    @Nested
    class Reads {
        @Test
        void recordRefused_persistsRefusalWithoutUnitsOrEvents() {
            var job = TestJobs.bySha(TestJobs.key(), false);
            var refused = NewJob.newJob(job.submittedBy(), job.priority(), false, false, job.request(), none(), job.recipe(),
                                        none(), List.of());
            var id = harness.store.jobs()
                                  .recordRefused(refused, Refusal.refusal(RefusalCode.R_M2, "-Dmaven.repo.local=/x"))
                                  .await(PgFixture.WAIT)
                                  .unwrap();
            var row = job(id);

            assertThat(row.state()).isEqualTo(JobState.REFUSED);
            assertThat(row.refusalCode()).isEqualTo(some("R-M2"));
            assertThat(row.refusalDetail()).isEqualTo(some("-Dmaven.repo.local=/x"));
            assertThat(row.sha()).isEqualTo(job.source().map(source -> source.sha()));
            assertThat(row.units()).isEmpty();
            assertThat(row.finishedAt().isPresent()).isTrue();
            assertThat(kinds(id)).isEmpty();
        }

        @Test
        void list_filtersAndOrdersNewestFirst_withUnits() {
            var first = harness.submit(TestJobs.byRef("feature/l", TestJobs.sha(), false, -20));
            var second = harness.submit(TestJobs.bySha(TestJobs.key(), false));
            var all = list(JobQuery.jobQuery(none(), none(), none(), none(), 10).unwrap());
            var byRef = list(JobQuery.jobQuery(none(), none(), none(), some(job(first).ref().unwrap()), 10).unwrap());
            var queuedByOwner = list(JobQuery.jobQuery(some(JobState.QUEUED), some(OwnerTag.ownerTag("v2174").unwrap()), none(),
                                                       none(), 1).unwrap());

            assertThat(all.stream().map(JobRow::id).toList()).containsExactly(second, first);
            assertThat(all.getFirst().units()).hasSize(1);
            assertThat(byRef.stream().map(JobRow::id).toList()).containsExactly(first);
            assertThat(queuedByOwner).hasSize(1);
            assertThat(JobQuery.jobQuery(none(), none(), none(), none(), 0).isFailure()).isTrue();
        }

        /// The keyset cursor: the page after a job holds only the older ones, newest first, still at most `limit`.
        @Test
        void list_after_answersTheOlderPage() {
            var first = harness.submit(TestJobs.byRef("feature/p", TestJobs.sha(), false, -20));
            var second = harness.submit(TestJobs.bySha(TestJobs.key(), false));
            var third = harness.submit(TestJobs.bySha(TestJobs.key(), false));
            var page = JobQuery.jobQuery(none(), none(), none(), none(), 1).unwrap();

            assertThat(list(page).stream().map(JobRow::id).toList()).containsExactly(third);
            assertThat(list(page.after(third)).stream().map(JobRow::id).toList()).containsExactly(second);
            assertThat(list(page.after(second)).stream().map(JobRow::id).toList()).containsExactly(first);
            assertThat(list(page.after(first))).isEmpty();
        }

        @Test
        void job_unknown_isNone() {
            assertThat(harness.store.jobs().job(JobId.jobId(999_999).unwrap()).await(PgFixture.WAIT).unwrap().isEmpty()).isTrue();
        }

        @Test
        void refusals_seededRowsParse_includingFailsafeRunner() {
            var rules = harness.store.refusals().active().await(PgFixture.WAIT).unwrap();

            assertThat(rules).containsExactly(RefusalRule.ExcludeTestClass.excludeTestClass("R-1553",
                                                                                             "StreamPublishReshuffleTest",
                                                                                             "#1553",
                                                                                             some(TestRunner.FAILSAFE)),
                                              RefusalRule.ItTestRequired.itTestRequired("R-FAILSAFE", "forge-tests"));

            exec("UPDATE refusals SET expired_at = now() WHERE code = 'R-1553'");
            assertThat(harness.store.refusals().active().await(PgFixture.WAIT).unwrap()).hasSize(1);
        }

        @Test
        void hosts_upsertStateFacts_andUnknownHostFails() {
            var updated = HostConfig.hostConfig(TestJobs.HOST, "198.51.100.2", some("ssh-ed25519 AAAA"), 3, 1024, true).unwrap();

            harness.store.hosts().upsert(updated).await(PgFixture.WAIT).unwrap();
            harness.store.hosts().state(TestJobs.HOST, "UP").await(PgFixture.WAIT).unwrap();
            harness.store.hosts().facts(TestJobs.HOST, TestJobs.facts()).await(PgFixture.WAIT).unwrap();

            assertThat(count("SELECT count(*) AS n FROM hosts WHERE name = 'bigboy' AND address = '198.51.100.2' AND cap = 3 "
                             + "AND port_isolated AND state = 'UP' AND (facts->>'cores')::int = 32 "
                             + "AND facts_at = '2026-10-13T10:00:00.123456Z'")).isEqualTo(1);

            var nowhere = HostName.hostName("nowhere").unwrap();

            harness.store.hosts()
                         .state(nowhere, "UP")
                         .await(PgFixture.WAIT)
                         .onSuccess(_ -> fail("expected HostNotFound"))
                         .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.HostNotFound.class));
            harness.store.hosts()
                         .state(TestJobs.HOST, "SIDEWAYS")
                         .await(PgFixture.WAIT)
                         .onSuccess(_ -> fail("expected the state check constraint to reject"))
                         .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.Database.class));
        }
    }

    private JobRow job(JobId id) {
        return harness.store.jobs().job(id).await(PgFixture.WAIT).unwrap().unwrap();
    }

    private ResultRecordDto result(JobId id) {
        return harness.store.results().result(id).await(PgFixture.WAIT).unwrap().map(WireRecords::resultRecordDto).unwrap();
    }

    private CancelOutcome cancel(JobId id) {
        return harness.store.jobs().requestCancel(id).await(PgFixture.WAIT).unwrap();
    }

    private List<JobRow> list(JobQuery query) {
        return harness.store.jobs().list(query).await(PgFixture.WAIT).unwrap();
    }

    private List<EventRow> events(JobId id) {
        return harness.store.events()
                            .after(0, Set.of(), 1000)
                            .await(PgFixture.WAIT)
                            .unwrap()
                            .stream()
                            .filter(event -> event.job().equals(Option.some(id)))
                            .toList();
    }

    private List<EventKind> kinds(JobId id) {
        return events(id).stream().map(EventRow::kind).map(WireRecords::eventKind).toList();
    }

    private EventRow lastEvent(JobId id) {
        return events(id).getLast();
    }

    private long count(String sql) {
        return harness.raw.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap().index(0).getLong("n");
    }

    private void exec(String sql) {
        harness.raw.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap();
    }
}
