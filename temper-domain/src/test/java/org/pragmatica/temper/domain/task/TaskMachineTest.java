package org.pragmatica.temper.domain.task;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.temper.domain.model.JobId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.task.Tasks.T0;
import static org.pragmatica.temper.domain.task.Tasks.applied;
import static org.pragmatica.temper.domain.task.Tasks.later;
import static org.pragmatica.temper.domain.task.Tasks.state;

/// m3-design §11.5 `TaskMachineTest`: every row T1–T16 of §5.2 as a test, then the whole (stage, status, command)
/// space: a command is accepted exactly where a row admits it, and refused everywhere else.
///
/// Probes: allow VERIFY → GATE (`t4_verifyPass_toGate_isBadNext` goes red); let T9 apply at `round = round_cap`
/// (`t9prime_atTheCap_parksRoundCap` goes red).
class TaskMachineTest {
    private static final Set<Stage> WORKER = Set.of(Stage.DESIGN, Stage.CODE, Stage.VERIFY, Stage.FIX, Stage.GATE);

    // ---- one test per row -------------------------------------------------------------------------------------------
    @Test
    void t1_advance_toCode_newVisit_readyAdvanced() {
        var moved = applied(state(Stage.TRIAGED, Status.READY), new Command.Advance(false, true), T0);

        assertThat(moved.state().stage()).isEqualTo(Stage.CODE);
        assertThat(moved.state().status()).isEqualTo(Status.READY);
        assertThat(moved.state().counters().visit()).isEqualTo(2);
        assertThat(kinds(moved)).containsExactly(TaskEvent.Kind.ADVANCED, TaskEvent.Kind.READY);
        assertThat(moved.events().get(1).facts()).containsEntry("reason", "advanced");
    }

    @Test
    void t1_advance_designBrief_toDesign() {
        assertThat(applied(state(Stage.TRIAGED, Status.READY), new Command.Advance(true, true), T0).state().stage())
            .isEqualTo(Stage.DESIGN);
    }

    @Test
    void t1_advance_afterNotReached_isAfterPending() {
        assertRefused(state(Stage.TRIAGED, Status.READY), new Command.Advance(false, false), TaskError.General.AFTER_PENDING);
    }

    @Test
    void t2_claim_leasesAtTheNextEpoch_withBothClocks() {
        var moved = applied(state(Stage.CODE, Status.READY), Tasks.claim(), T0);
        var lease = moved.state().lease();

        assertThat(moved.state().status()).isEqualTo(Status.LEASED);
        assertThat(lease.epoch()).isEqualTo(2);
        assertThat(lease.hash()).isEqualTo(some("hash-2"));
        assertThat(lease.holder()).isEqualTo(some("w2"));
        assertThat(lease.until()).isEqualTo(some(T0.plus(Duration.ofMinutes(30))));
        assertThat(lease.stepDeadline()).isEqualTo(some(T0.plus(Duration.ofHours(6))));
        assertThat(lease.readPoint()).isEqualTo(some(Tasks.READ_POINT));
        assertThat(moved.state().readySince().isEmpty()).isTrue();
        assertThat(moved.state().counters().visit()).isEqualTo(1);
        assertThat(kinds(moved)).containsExactly(TaskEvent.Kind.LEASED);
    }

    @Test
    void t2_claim_notReady_isNotReady() {
        assertRefused(state(Stage.CODE, Status.WAITING), Tasks.claim(), TaskError.General.NOT_READY);
        assertRefused(state(Stage.CI, Status.WAITING), Tasks.claim(), TaskError.General.NOT_READY);
    }

    @Test
    void t3_heartbeat_extendsTheTtl_notTheStepDeadline() {
        var leased = state(Stage.CODE, Status.LEASED);
        var moved = applied(leased, new Command.Heartbeat(), later(Duration.ofMinutes(10)));

        assertThat(moved.state().lease().until()).isEqualTo(some(T0.plus(Duration.ofMinutes(40))));
        assertThat(moved.state().lease().stepDeadline()).isEqualTo(leased.lease().stepDeadline());
        assertThat(moved.state().epoch()).isEqualTo(1);
        assertThat(kinds(moved)).containsExactly(TaskEvent.Kind.HEARTBEAT);
    }

    @Test
    void t3_heartbeat_notLeased_isLeaseLost() {
        assertRefused(state(Stage.CODE, Status.READY), new Command.Heartbeat(), TaskError.General.LEASE_LOST);
        assertRefused(state(Stage.MERGED, Status.CLOSED), new Command.Heartbeat(), TaskError.General.LEASE_LOST);
    }

    @Test
    void t4_codeEvidence_toVerify_newVisit_epochKept_headTaken() {
        var moved = applied(state(Stage.CODE, Status.LEASED), Tasks.evidence(Stage.VERIFY), T0);

        assertThat(moved.state().stage()).isEqualTo(Stage.VERIFY);
        assertThat(moved.state().status()).isEqualTo(Status.READY);
        assertThat(moved.state().counters().visit()).isEqualTo(2);
        assertThat(moved.state().epoch()).isEqualTo(1);
        assertThat(moved.state().lease().hash().isEmpty()).isTrue();
        assertThat(moved.state().head()).isEqualTo(some(Tasks.HEAD));
        assertThat(kinds(moved)).containsExactly(TaskEvent.Kind.EVIDENCE_ACCEPTED, TaskEvent.Kind.READY);
    }

    @Test
    void t4_designToCode_fixToVerify_verifyPassToCi() {
        assertThat(applied(state(Stage.DESIGN, Status.LEASED), Tasks.evidence(Stage.CODE), T0).state().stage()).isEqualTo(Stage.CODE);
        assertThat(applied(state(Stage.FIX, Status.LEASED), Tasks.evidence(Stage.VERIFY), T0).state().stage()).isEqualTo(Stage.VERIFY);

        var ci = applied(state(Stage.VERIFY, Status.LEASED), Tasks.verify(EvidenceOutcome.Verdict.PASS, List.of()), T0).state();

        assertThat(ci.stage()).isEqualTo(Stage.CI);
        assertThat(ci.status()).isEqualTo(Status.WAITING);
        assertThat(ci.waiting().ci()).isTrue();
    }

    @Test
    void t4_fixToCi_onlyForAProvenMergeForward() {
        var mergeForward = new Command.Evidence(5,
                                                none(),
                                                EvidenceOutcome.evidenceOutcome(some(Stage.CI),
                                                                                EvidenceOutcome.Verdict.NONE,
                                                                                true,
                                                                                some(Tasks.HEAD),
                                                                                none(),
                                                                                List.of()));

        assertThat(applied(state(Stage.FIX, Status.LEASED), mergeForward, T0).state().stage()).isEqualTo(Stage.CI);
        assertRefused(state(Stage.FIX, Status.LEASED), Tasks.evidence(Stage.CI), TaskError.General.BAD_NEXT);
    }

    @Test
    void t4_verifyPass_toGate_isBadNext() {
        var toGate = new Command.Evidence(5, none(), Tasks.outcome(some(Stage.GATE), EvidenceOutcome.Verdict.PASS));

        assertRefused(state(Stage.VERIFY, Status.LEASED), toGate, TaskError.General.BAD_NEXT);
    }

    @Test
    void t4_anyOtherNext_isBadNext() {
        assertRefused(state(Stage.CODE, Status.LEASED), Tasks.evidence(Stage.GATE), TaskError.General.BAD_NEXT);
        assertRefused(state(Stage.DESIGN, Status.LEASED), Tasks.evidence(Stage.VERIFY), TaskError.General.BAD_NEXT);
        assertRefused(state(Stage.GATE, Status.LEASED), Tasks.evidence(Stage.CI), TaskError.General.BAD_NEXT);
        assertRefused(state(Stage.CODE, Status.LEASED),
                      new Command.Evidence(5, none(), Tasks.outcome(none(), EvidenceOutcome.Verdict.NONE)),
                      TaskError.General.BAD_NEXT);
        assertRefused(state(Stage.CODE, Status.LEASED),
                      new Command.Evidence(5, none(), Tasks.outcome(some(Stage.VERIFY), EvidenceOutcome.Verdict.PASS)),
                      TaskError.General.BAD_NEXT);
        assertRefused(state(Stage.VERIFY, Status.LEASED),
                      new Command.Evidence(5, none(), Tasks.outcome(some(Stage.CI), EvidenceOutcome.Verdict.NONE)),
                      TaskError.General.BAD_NEXT);
    }

    @Test
    void t5_wait_revokesTheLease_andRecordsTheJobs() {
        var moved = applied(state(Stage.CODE, Status.LEASED), Tasks.waitOnJob(), T0);

        assertThat(moved.state().status()).isEqualTo(Status.WAITING);
        assertThat(moved.state().epoch()).isEqualTo(2);
        assertThat(moved.state().waiting().jobs()).containsExactly(JobId.jobId(9).unwrap());
        assertThat(moved.state().counters().visit()).isEqualTo(1);
        assertThat(moved.events().getFirst().facts()).containsEntry("jobs", "9").containsEntry("ci", "false");
    }

    @Test
    void t5_waitOnNothing_isRefused() {
        assertRefused(state(Stage.CODE, Status.LEASED), new Command.Wait(Waiting.NOTHING), TaskError.General.NOTHING_AWAITED);
    }

    /// Review #2: a CI wait outside CI would never end (only T12 ends it, from CI), so it is refused, with or without jobs.
    @Test
    void t5_waitOnCi_outsideTheCiStage_isRefused() {
        assertRefused(state(Stage.CODE, Status.LEASED), new Command.Wait(Waiting.waiting(List.of(), true)), TaskError.General.CI_WAIT_OUTSIDE_CI);
        assertRefused(state(Stage.FIX, Status.LEASED),
                      new Command.Wait(Waiting.waiting(List.of(JobId.jobId(9).unwrap()), true)),
                      TaskError.General.CI_WAIT_OUTSIDE_CI);
    }

    /// §6.2 [G]: at a controller start, a lease whose TTL lapsed during the downtime gets start + its stage's TTL; its
    /// step deadline does not move; the extended lease is then live and not extended again.
    @Test
    void controllerStart_extendsALapsedTtl_notTheStepDeadline() {
        var leased = state(Stage.CODE, Status.LEASED);
        var start = later(Duration.ofHours(2));
        var moved = applied(leased, new Command.ControllerStart(false), start);

        assertThat(moved.state().lease().until()).isEqualTo(some(start.plus(Duration.ofMinutes(30))));
        assertThat(moved.state().lease().stepDeadline()).isEqualTo(leased.lease().stepDeadline());
        assertThat(moved.state().epoch()).isEqualTo(leased.epoch());
        assertThat(kinds(moved)).containsExactly(TaskEvent.Kind.LEASE_EXTENDED);
    }

    /// Review D1: a live lease is left alone (a restart 5 s after the claim extends nothing).
    @Test
    void controllerStart_leavesALiveLeaseAlone() {
        var claimed = applied(state(Stage.CODE, Status.READY), Tasks.claim(), T0).state();

        assertThat(TaskMachine.apply(claimed, new Command.ControllerStart(false), T0.plusSeconds(5)))
            .isEqualTo(TaskError.General.NOT_EXPIRED.result());
    }

    /// Review D1: a lease past its step deadline is not extended; the sweep expires it as usual.
    @Test
    void controllerStart_pastTheStepDeadline_extendsNothing() {
        assertThat(TaskMachine.apply(state(Stage.CODE, Status.LEASED), new Command.ControllerStart(false), later(Duration.ofHours(7))))
            .isEqualTo(TaskError.General.NOT_EXPIRED.result());
    }

    /// Review D1: at most once per epoch.
    @Test
    void controllerStart_extendedThisEpochAlready_extendsNothing() {
        assertThat(TaskMachine.apply(state(Stage.CODE, Status.LEASED), new Command.ControllerStart(true), later(Duration.ofHours(2))))
            .isEqualTo(TaskError.General.NOT_EXPIRED.result());
    }

    /// Verifier G2: a ruling starts a new visit, so a stuck-lease park's expiries (and releases) start again at 0: the
    /// next expiry requeues, it does not park again.
    @Test
    void t11_afterAStuckLeaseRuling_theNextExpiryRequeues() {
        var parked = TaskState.taskState(Stage.NEEDS_RULING, Status.HELD, some(Stage.CODE), Counters.counters(0, 3, 0, 4, 2, 2),
                                         LeaseState.leaseState(3, none(), none(), none(), none(), some(Tasks.READ_POINT)), none(), none(),
                                         none(), Waiting.NOTHING, Audit.NONE, none());
        var ruled = applied(parked, Tasks.rule(none()), T0).state();
        var leased = applied(ruled, Tasks.claim(), T0).state();
        var expired = applied(leased, new Command.Expire(false), later(Duration.ofMinutes(31)));

        assertThat(ruled.counters().visitExpiries()).isZero();
        assertThat(ruled.counters().visitFailures()).isZero();
        assertThat(expired.state().stage()).isEqualTo(Stage.CODE);
        assertThat(expired.state().status()).isEqualTo(Status.READY);
    }

    @Test
    void t6_resume_settled_readySameVisit() {
        var moved = applied(state(Stage.CODE, Status.WAITING), new Command.Resume(true), later(Duration.ofMinutes(5)));

        assertThat(moved.state().status()).isEqualTo(Status.READY);
        assertThat(moved.state().counters().visit()).isEqualTo(1);
        assertThat(moved.state().readySince()).isEqualTo(some(later(Duration.ofMinutes(5))));
        assertThat(moved.state().waiting()).isEqualTo(Waiting.NOTHING);
        assertThat(moved.events().getFirst().facts()).containsEntry("reason", "resumed");
    }

    @Test
    void t6_resume_unsettled_orOnCi_isRefused() {
        assertRefused(state(Stage.CODE, Status.WAITING), new Command.Resume(false), TaskError.General.NOT_SETTLED);
        assertThat(TaskMachine.apply(state(Stage.CI, Status.WAITING), new Command.Resume(true), T0).isFailure()).isTrue();
    }

    @Test
    void t7_release_requeues_countsAFailure_budgetDoesNot() {
        var failed = applied(state(Stage.CODE, Status.LEASED), Tasks.release("no-evidence"), T0).state();
        var budget = applied(state(Stage.CODE, Status.LEASED), Tasks.release("budget"), T0).state();

        assertThat(failed.status()).isEqualTo(Status.READY);
        assertThat(failed.epoch()).isEqualTo(2);
        assertThat(failed.counters().visitFailures()).isEqualTo(1);
        assertThat(budget.counters().visitFailures()).isZero();
    }

    @Test
    void t7_thirdFailureInAVisit_parksWorkerFailures() {
        var state = state(Stage.CODE, Status.READY);

        for (var round = 0; round < 2; round++) {
            state = applied(applied(state, Tasks.claim(), T0).state(), Tasks.release("crash"), T0).state();
        }
        var third = applied(applied(state, Tasks.claim(), T0).state(), Tasks.release("crash"), T0);

        assertThat(third.state().stage()).isEqualTo(Stage.NEEDS_RULING);
        assertThat(third.parked().map(ParkRequest::kind)).isEqualTo(some(RulingKind.WORKER_FAILURES));
        assertThat(third.parked().map(request -> request.recommendation().temperDefault())).isEqualTo(some(true));
    }

    @Test
    void t8_expiry_ttlPassed_requeues() {
        var moved = applied(state(Stage.CODE, Status.LEASED), new Command.Expire(false), later(Duration.ofMinutes(31)));

        assertThat(moved.state().status()).isEqualTo(Status.READY);
        assertThat(moved.state().epoch()).isEqualTo(2);
        assertThat(moved.state().counters().visitExpiries()).isEqualTo(1);
        assertThat(moved.events().getFirst().facts()).containsEntry("clock", "ttl");
        assertThat(moved.events().get(1).facts()).containsEntry("reason", "requeued");
    }

    @Test
    void t8_expiry_beforeEitherClock_isNotExpired() {
        assertRefused(state(Stage.CODE, Status.LEASED), new Command.Expire(false), TaskError.General.NOT_EXPIRED);
    }

    @Test
    void t9_verifyFail_belowTheCap_toFix_roundPlusOne() {
        var moved = applied(state(Stage.VERIFY, Status.LEASED), Tasks.verify(EvidenceOutcome.Verdict.FAIL, List.of("i1")), T0);

        assertThat(moved.state().stage()).isEqualTo(Stage.FIX);
        assertThat(moved.state().status()).isEqualTo(Status.READY);
        assertThat(moved.state().counters().round()).isEqualTo(1);
        assertThat(moved.state().counters().visit()).isEqualTo(2);
    }

    @Test
    void t9prime_atTheCap_parksRoundCap() {
        var atCap = state(Stage.VERIFY, Status.LEASED).withCounters(Counters.counters(3, 3, 0, 7, 0, 0));
        var moved = applied(atCap, Tasks.verify(EvidenceOutcome.Verdict.FAIL, List.of("i1", "i2")), T0);

        assertThat(moved.state().stage()).isEqualTo(Stage.NEEDS_RULING);
        assertThat(moved.state().status()).isEqualTo(Status.HELD);
        assertThat(moved.state().raisedFrom()).isEqualTo(some(Stage.VERIFY));
        assertThat(moved.state().counters().round()).isEqualTo(3);
        assertThat(moved.parked().map(ParkRequest::kind)).isEqualTo(some(RulingKind.ROUND_CAP));
        assertThat(moved.parked().map(ParkRequest::evidence)).isEqualTo(some(List.of(5L, 4L)));
        assertThat(moved.parked().map(ParkRequest::items)).isEqualTo(some(List.of("i1", "i2")));
        assertThat(kinds(moved)).containsExactly(TaskEvent.Kind.EVIDENCE_ACCEPTED, TaskEvent.Kind.PARKED);
    }

    @Test
    void t10_park_byTheCto_fromAnyNonTerminal_raisedFromTheStage() {
        var moved = applied(state(Stage.CODE, Status.READY), new Command.Park(Tasks.request(), false), T0);

        assertThat(moved.state().stage()).isEqualTo(Stage.NEEDS_RULING);
        assertThat(moved.state().status()).isEqualTo(Status.HELD);
        assertThat(moved.state().raisedFrom()).isEqualTo(some(Stage.CODE));
        assertThat(moved.state().epoch()).isEqualTo(1);
        assertThat(moved.parked()).isEqualTo(some(Tasks.request()));
    }

    @Test
    void t10_park_ofALeasedTask_revokesTheLease() {
        assertThat(applied(state(Stage.CODE, Status.LEASED), new Command.Park(Tasks.request(), true), T0).state().epoch()).isEqualTo(2);
    }

    @Test
    void t11_rule_returnsToRaisedFrom_asANewVisit_withExtraRounds() {
        var moved = applied(state(Stage.NEEDS_RULING, Status.HELD), Tasks.rule(some(2)), T0);

        assertThat(moved.state().stage()).isEqualTo(Stage.CODE);
        assertThat(moved.state().status()).isEqualTo(Status.READY);
        assertThat(moved.state().raisedFrom().isEmpty()).isTrue();
        assertThat(moved.state().counters().visit()).isEqualTo(2);
        assertThat(moved.state().counters().roundCap()).isEqualTo(5);
        assertThat(moved.events().get(1).facts()).containsEntry("reason", "ruled");
    }

    @Test
    void t11_rule_notHeld_isNotHeld() {
        assertRefused(state(Stage.CODE, Status.READY), Tasks.rule(none()), TaskError.General.NOT_HELD);
    }

    @Test
    void t12_ciDecisions() {
        var ci = state(Stage.CI, Status.WAITING);
        var green = applied(ci, new Command.CiDecision(new Command.CiOutcome.Green()), T0).state();
        var red = applied(ci, new Command.CiDecision(new Command.CiOutcome.Red("ci-run-9-1")), T0).state();
        var merge = applied(ci, new Command.CiDecision(new Command.CiOutcome.MergeForward("merge-forward")), T0).state();
        var parked = applied(ci, new Command.CiDecision(new Command.CiOutcome.Parked(Tasks.request())), T0);

        assertThat(green.stage()).isEqualTo(Stage.GATE);
        assertThat(green.status()).isEqualTo(Status.READY);
        assertThat(red.stage()).isEqualTo(Stage.FIX);
        assertThat(red.counters().ciRounds()).isEqualTo(1);
        assertThat(red.counters().round()).isZero();
        assertThat(merge.stage()).isEqualTo(Stage.FIX);
        assertThat(merge.counters().ciRounds()).isZero();
        assertThat(parked.state().stage()).isEqualTo(Stage.NEEDS_RULING);
        assertThat(parked.state().raisedFrom()).isEqualTo(some(Stage.CI));
    }

    @Test
    void t13_gateEvidence_toMergeReadyHeld() {
        var moved = applied(state(Stage.GATE, Status.LEASED), Tasks.evidence(Stage.MERGE_READY), T0);

        assertThat(moved.state().stage()).isEqualTo(Stage.MERGE_READY);
        assertThat(moved.state().status()).isEqualTo(Status.HELD);
        assertThat(kinds(moved)).containsExactly(TaskEvent.Kind.EVIDENCE_ACCEPTED);
    }

    @Test
    void t14_bounce_toFixWithTheItem() {
        var moved = applied(state(Stage.MERGE_READY, Status.HELD), new Command.Bounce("rename it"), T0);

        assertThat(moved.state().stage()).isEqualTo(Stage.FIX);
        assertThat(moved.state().status()).isEqualTo(Status.READY);
        assertThat(moved.events().getFirst().facts()).containsEntry("item", "rename it");
        assertThat(moved.events().get(1).facts()).containsEntry("reason", "bounced");
    }

    @Test
    void t15_closeMerged_needsTheMerge_andNoPendingAudit() {
        var held = state(Stage.MERGE_READY, Status.HELD);
        var merged = applied(held, new Command.CloseMerged(true), T0).state();
        var pending = TaskState.taskState(held.stage(), held.status(), held.raisedFrom(), held.counters(), held.lease(), held.head(),
                                          held.pr(), held.readySince(), held.waiting(), Audit.PENDING, held.closedAt());

        assertThat(merged.stage()).isEqualTo(Stage.MERGED);
        assertThat(merged.status()).isEqualTo(Status.CLOSED);
        assertThat(merged.closedAt()).isEqualTo(some(T0));
        assertRefused(held, new Command.CloseMerged(false), TaskError.General.NOT_MERGED);
        assertRefused(pending, new Command.CloseMerged(true), TaskError.General.AUDIT_PENDING);
    }

    @Test
    void t16_closeAbandoned_revokesALiveLease() {
        var moved = applied(state(Stage.CODE, Status.LEASED), new Command.CloseAbandoned(), T0).state();

        assertThat(moved.stage()).isEqualTo(Stage.ABANDONED);
        assertThat(moved.status()).isEqualTo(Status.CLOSED);
        assertThat(moved.epoch()).isEqualTo(2);
    }

    // ---- the whole space ---------------------------------------------------------------------------------------------
    /// Every reachable (stage, status) against every command, with arguments that pass each guard where the pair is
    /// admitted: accepted exactly where a row of §5.2 admits it.
    @ParameterizedTest(name = "{0}/{1} {2}")
    @MethodSource("space")
    void command_acceptedExactlyWhereARowAdmitsIt(Stage stage, Status status, String name, Command command, boolean admitted) {
        var outcome = TaskMachine.apply(state(stage, status), command, later(Duration.ofHours(9)));

        if (admitted) {
            outcome.onFailure(cause -> fail(name + " refused in " + stage + "/" + status + ": " + cause.message()));
        } else {
            outcome.onSuccess(moved -> fail(name + " accepted in " + stage + "/" + status + " → " + moved.state().stage()));
        }
    }

    static Stream<Arguments> space() {
        var rows = new ArrayList<Arguments>();

        reachable().forEach(pair -> commands(pair.getKey()).forEach((name, command) -> rows.add(Arguments.of(pair.getKey(),
                                                                                                             pair.getValue(),
                                                                                                             name,
                                                                                                             command,
                                                                                                             isAdmitted(pair.getKey(),
                                                                                                                        pair.getValue(),
                                                                                                                        name)))));
        return rows.stream();
    }

    /// TRIAGED/READY; each worker stage READY, LEASED or WAITING; CI/WAITING; the two HELD stages; the closed ones.
    private static List<Map.Entry<Stage, Status>> reachable() {
        var pairs = new ArrayList<Map.Entry<Stage, Status>>();

        pairs.add(Map.entry(Stage.TRIAGED, Status.READY));
        WORKER.forEach(stage -> List.of(Status.READY, Status.LEASED, Status.WAITING).forEach(status -> pairs.add(Map.entry(stage, status))));
        pairs.add(Map.entry(Stage.CI, Status.WAITING));
        pairs.add(Map.entry(Stage.MERGE_READY, Status.HELD));
        pairs.add(Map.entry(Stage.NEEDS_RULING, Status.HELD));
        pairs.add(Map.entry(Stage.MERGED, Status.CLOSED));
        pairs.add(Map.entry(Stage.ABANDONED, Status.CLOSED));
        return pairs;
    }

    /// One command of each kind; evidence names the `next` its stage may ask for (and VERIFY passes), so a refusal can
    /// only come from the (stage, status).
    private static Map<String, Command> commands(Stage stage) {
        return Map.ofEntries(Map.entry("advance", new Command.Advance(false, true)),
                             Map.entry("claim", Tasks.claim()),
                             Map.entry("heartbeat", new Command.Heartbeat()),
                             Map.entry("link-job", new Command.LinkJob()),
                             Map.entry("evidence", stageEvidence(stage)),
                             Map.entry("wait", Tasks.waitOnJob()),
                             Map.entry("resume", new Command.Resume(true)),
                             Map.entry("release", Tasks.release("crash")),
                             Map.entry("expire", new Command.Expire(false)),
                             Map.entry("park-lease", new Command.Park(Tasks.request(), true)),
                             Map.entry("park-cto", new Command.Park(Tasks.request(), false)),
                             Map.entry("rule", Tasks.rule(none())),
                             Map.entry("ci-decision", new Command.CiDecision(new Command.CiOutcome.Green())),
                             Map.entry("bounce", new Command.Bounce("item")),
                             Map.entry("close-merged", new Command.CloseMerged(true)),
                             Map.entry("close-abandoned", new Command.CloseAbandoned()),
                             Map.entry("controller-start", new Command.ControllerStart(false)));
    }

    private static Command stageEvidence(Stage stage) {
        return switch (stage) {
            case DESIGN -> Tasks.evidence(Stage.CODE);
            case VERIFY -> Tasks.verify(EvidenceOutcome.Verdict.PASS, List.of());
            case GATE -> Tasks.evidence(Stage.MERGE_READY);
            default -> Tasks.evidence(Stage.VERIFY);
        };
    }

    /// The admitted (stage, status, command) of §5.2, written out independently of [TaskMachine].
    private static boolean isAdmitted(Stage stage, Status status, String command) {
        var terminal = stage == Stage.MERGED || stage == Stage.ABANDONED;
        var worker = WORKER.contains(stage);

        return switch (command) {
            case "advance" -> stage == Stage.TRIAGED && status == Status.READY;
            case "claim" -> worker && status == Status.READY;
            case "heartbeat", "link-job", "evidence", "wait", "release", "expire", "park-lease" -> worker && status == Status.LEASED;
            // At the matrix's T0+9h every fixture lease is past its step deadline, which §6.2 never extends (review D1);
            // the LEASED rows that do extend are controllerStart_extendsALapsedTtl_notTheStepDeadline's.
            case "controller-start" -> false;
            case "resume" -> worker && status == Status.WAITING;
            case "park-cto" -> !terminal && stage != Stage.NEEDS_RULING;
            case "rule" -> stage == Stage.NEEDS_RULING && status == Status.HELD;
            case "ci-decision" -> stage == Stage.CI && status == Status.WAITING;
            case "bounce", "close-merged" -> stage == Stage.MERGE_READY && status == Status.HELD;
            case "close-abandoned" -> !terminal;
            default -> throw new IllegalArgumentException(command);
        };
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------
    private static List<TaskEvent.Kind> kinds(Transition transition) {
        return transition.events().stream().map(TaskEvent::kind).toList();
    }

    private static void assertRefused(TaskState state, Command command, TaskError expected) {
        TaskMachine.apply(state, command, T0)
                   .onSuccess(moved -> fail(command.name() + " accepted, expected " + expected.message()))
                   .onFailure(cause -> assertThat(cause).isEqualTo(expected));
    }
}
