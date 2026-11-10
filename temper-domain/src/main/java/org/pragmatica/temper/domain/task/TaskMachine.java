package org.pragmatica.temper.domain.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// The task state machine (m3-design §5.2), pure: `apply(state, command, now)` is the new state and the events to
/// append, or the refusal. Every row T1–T16 is one branch; every (stage, status, command) no row admits is refused
/// (`TaskMachineTest`).
///
/// Fencing is not decided here: a lease-scoped command on a task that is not LEASED is `lease-lost`, but whether the
/// caller's epoch and token are the current ones is the store's conditional UPDATE (§6.4), so that a stale call is
/// refused by the one statement that writes.
///
/// Counting (§5.2, §6.2): `round` moves on T9 only; CI→FIX counts in `ciRounds` (ruling R-M3-10); the second expiry in
/// one visit parks (`stuck-lease`) and the third release that is not `budget` parks (`worker-failures`) (ruling
/// R-M3-11). A visit starts on T1, T4, T9, T11, T12, T13 and T14, and only there.
public sealed interface TaskMachine {
    /// "Stuck past its lease twice" (§6.2).
    int EXPIRIES_TO_PARK = 2;
    /// Three releases in a visit park as `worker-failures` (§6.2 [G, Q-M3-9], ruling R-M3-11).
    int FAILURES_TO_PARK = 3;

    static Result<Transition> apply(TaskState state, Command command, Instant now) {
        return Command.isLeaseScoped(command) && state.status() != Status.LEASED
               ? TaskError.General.LEASE_LOST.result()
               : open(state, command, now);
    }

    private static Result<Transition> open(TaskState state, Command command, Instant now) {
        return state.stage()
                    .isTerminal()
               ? refused(state, command)
               : decided(state, command, now);
    }

    private static Result<Transition> decided(TaskState state, Command command, Instant now) {
        return switch (command) {
            case Command.Advance advance -> advance(state, advance, now);
            case Command.Claim claim -> claim(state, claim, now);
            case Command.Heartbeat _ -> success(extend(state, TaskEvent.Kind.HEARTBEAT, now));
            case Command.LinkJob _ -> success(extend(state, TaskEvent.Kind.JOB_LINKED, now));
            case Command.Evidence evidence -> evidence(state, evidence, now);
            case Command.Wait wait -> waitOn(state, wait.awaited(), now);
            case Command.Resume resume -> resume(state, resume, now);
            case Command.Release release -> success(release(state, release.reason(), now));
            case Command.Expire expire -> expire(state, expire, now);
            case Command.Park park -> park(state, park, now);
            case Command.Rule rule -> rule(state, rule.decision(), now);
            case Command.CiDecision decision -> ciDecision(state, decision.outcome(), now);
            case Command.Bounce bounce -> bounce(state, bounce.item(), now);
            case Command.CloseMerged close -> closeMerged(state, close, now);
            case Command.CloseAbandoned _ -> success(closed(state, Stage.ABANDONED, "abandoned", now));
            case Command.ControllerStart start -> controllerStart(state, start, now);
        };
    }

    // ---- T1: TRIAGED → DESIGN or CODE -----------------------------------------------------------------------------
    private static Result<Transition> advance(TaskState state, Command.Advance advance, Instant now) {
        return isIn(state, Stage.TRIAGED, Status.READY)
               ? advanceIfReached(state, advance, now)
               : refused(state, advance);
    }

    private static Result<Transition> advanceIfReached(TaskState state, Command.Advance advance, Instant now) {
        return advance.afterReached()
               ? success(entering(state,
                                  firstStage(advance),
                                  TaskEvent.taskEvent(TaskEvent.Kind.ADVANCED),
                                  "advanced",
                                  now))
               : TaskError.General.AFTER_PENDING.result();
    }

    /// DESIGN when the brief asks for one, else CODE.
    private static Stage firstStage(Command.Advance advance) {
        return advance.design()
               ? Stage.DESIGN
               : Stage.CODE;
    }

    // ---- T2: claim -------------------------------------------------------------------------------------------------
    private static Result<Transition> claim(TaskState state, Command.Claim claim, Instant now) {
        return state.stage()
                    .isWorkerStage() && state.status() == Status.READY
               ? success(moved(state.leased(state.lease()
                                                 .granted(claim.tokenHash(),
                                                          claim.holder(),
                                                          now,
                                                          LeaseTerms.forStage(state.stage()),
                                                          claim.readPoint())),
                               TaskEvent.taskEvent(TaskEvent.Kind.LEASED,
                                                   Map.of("holder",
                                                          claim.holder(),
                                                          "readPoint",
                                                          claim.readPoint().value()))))
               : TaskError.General.NOT_READY.result();
    }

    // ---- T3 and a linked submit: the TTL clock restarts ------------------------------------------------------------
    private static Transition extend(TaskState state, TaskEvent.Kind kind, Instant now) {
        return moved(state.extended(state.lease().extended(now,
                                                           LeaseTerms.forStage(state.stage()))),
                     TaskEvent.taskEvent(kind));
    }

    // ---- T4, T9, T9′, T13: accepted evidence -----------------------------------------------------------------------
    private static Result<Transition> evidence(TaskState state, Command.Evidence evidence, Instant now) {
        return state.stage() == Stage.VERIFY
               ? verified(state.withWork(evidence.outcome().head(),
                                         evidence.outcome().pr()),
                          evidence,
                          now)
               : handedOn(state.withWork(evidence.outcome().head(),
                                         evidence.outcome().pr()),
                          evidence,
                          now);
    }

    /// VERIFY: `fail` → T9 or T9′; `pass` must ask for CI; no verdict is not a VERIFY document.
    private static Result<Transition> verified(TaskState state, Command.Evidence evidence, Instant now) {
        return switch (evidence.outcome()
                               .verdict()) {
            case FAIL -> success(failedVerify(state, evidence, now));
            case PASS -> nextOrRefused(state, evidence, Stage.CI, now);
            case NONE -> TaskError.General.BAD_NEXT.result();
        };
    }

    /// T9 while a round is left: FIX, `round+1`. T9′ at the cap: NEEDS_RULING `round-cap` with the last VERIFY and FIX
    /// evidence and the open items (HR2, §7.6).
    private static Transition failedVerify(TaskState state, Command.Evidence evidence, Instant now) {
        return state.counters()
                    .hasRoundLeft()
               ? entering(state.withCounters(state.counters().plusRound()),
                          Stage.FIX,
                          accepted(evidence, "fail"),
                          "advanced",
                          now)
               : parking(state, state.counters(), roundCapRequest(state, evidence), accepted(evidence, "fail"), now);
    }

    private static ParkRequest roundCapRequest(TaskState state, Command.Evidence evidence) {
        return ParkRequest.raisedByTemper(RulingKind.ROUND_CAP,
                                          "VERIFY failed with the round cap " + state.counters()
                                                                                     .roundCap()
                                         + " reached; rule to grant more rounds or abandon",
                                          "Return to VERIFY with more rounds (rule --extra-rounds K)",
                                          Stream.concat(Stream.of(evidence.evidence()),
                                                        evidence.lastFix().stream())
                                                .toList(),
                                          evidence.outcome().openItems());
    }

    /// The `next` each stage may ask for (T4, T13): DESIGN → CODE; CODE → VERIFY; FIX → VERIFY, or FIX → CI when the
    /// only open item is a merge-forward pushed as a proven MERGE-FORWARD; GATE → MERGE_READY. Anything else is
    /// `bad-next`, as is a VERIFY verdict on another stage.
    private static Result<Transition> handedOn(TaskState state, Command.Evidence evidence, Instant now) {
        return evidence.outcome()
                       .verdict() == EvidenceOutcome.Verdict.NONE
               ? nextOrRefused(state,
                               evidence,
                               allowedNext(state.stage(), evidence.outcome()),
                               now)
               : TaskError.General.BAD_NEXT.result();
    }

    private static Option<Stage> allowedNext(Stage stage, EvidenceOutcome outcome) {
        return switch (stage) {
            case DESIGN -> some(Stage.CODE);
            case CODE -> some(Stage.VERIFY);
            case FIX -> outcome.mergeForwardOnly() && outcome.next().filter(next -> next == Stage.CI).isPresent()
                        ? some(Stage.CI)
                        : some(Stage.VERIFY);
            case GATE -> some(Stage.MERGE_READY);
            case TRIAGED, VERIFY, CI, MERGE_READY, NEEDS_RULING, MERGED, ABANDONED -> none();
        };
    }

    private static Result<Transition> nextOrRefused(TaskState state,
                                                    Command.Evidence evidence,
                                                    Option<Stage> allowed,
                                                    Instant now) {
        return evidence.outcome()
                       .next()
                       .filter(next -> allowed.filter(next::equals)
                                              .isPresent())
                       .map(next -> entering(state,
                                             next,
                                             accepted(evidence,
                                                      next.name()),
                                             "advanced",
                                             now))
                       .toResult(TaskError.General.BAD_NEXT);
    }

    private static Result<Transition> nextOrRefused(TaskState state,
                                                    Command.Evidence evidence,
                                                    Stage allowed,
                                                    Instant now) {
        return nextOrRefused(state, evidence, some(allowed), now);
    }

    private static TaskEvent accepted(Command.Evidence evidence, String outcome) {
        return TaskEvent.taskEvent(TaskEvent.Kind.EVIDENCE_ACCEPTED,
                                   Map.of("evidence",
                                          Long.toString(evidence.evidence()),
                                          "outcome",
                                          outcome));
    }

    // ---- T5, T6: WAITING ------------------------------------------------------------------------------------------
    /// A CI wait belongs to the CI stage only (T12 is its only end); elsewhere it is refused, never left to wedge.
    private static Result<Transition> waitOn(TaskState state, Waiting awaited, Instant now) {
        return awaited.ci() && state.stage() != Stage.CI
               ? TaskError.General.CI_WAIT_OUTSIDE_CI.result()
               : waitOnAnything(state, awaited, now);
    }

    private static Result<Transition> waitOnAnything(TaskState state, Waiting awaited, Instant now) {
        return awaited.isAnything()
               ? success(moved(state.waitingOn(awaited, now),
                               TaskEvent.taskEvent(TaskEvent.Kind.WAITING,
                                                   Map.of("jobs",
                                                          jobList(awaited.jobs()),
                                                          "ci",
                                                          Boolean.toString(awaited.ci())))))
               : TaskError.General.NOTHING_AWAITED.result();
    }

    private static String jobList(List<JobId> jobs) {
        return jobs.stream()
                   .map(JobId::toString)
                   .collect(Collectors.joining(","));
    }

    /// A CI wait is decided by the watcher (T12), never resumed here.
    private static Result<Transition> resume(TaskState state, Command.Resume resume, Instant now) {
        return state.status() == Status.WAITING && state.stage() != Stage.CI
               ? resumeIfSettled(state, resume, now)
               : refused(state, resume);
    }

    private static Result<Transition> resumeIfSettled(TaskState state, Command.Resume resume, Instant now) {
        return resume.settled() && !state.waiting()
                                         .ci()
               ? success(moved(state.resumed(now), ready("resumed")))
               : TaskError.General.NOT_SETTLED.result();
    }

    // ---- T7: release ----------------------------------------------------------------------------------------------
    private static Transition release(TaskState state, ReleaseReason reason, Instant now) {
        return requeueOrPark(state,
                             reason.isBudget()
                             ? state.counters()
                             : state.counters().plusFailure(),
                             TaskEvent.taskEvent(TaskEvent.Kind.RELEASED,
                                                 Map.of("reason", reason.value())),
                             now);
    }

    private static Transition requeueOrPark(TaskState state, Counters counted, TaskEvent ended, Instant now) {
        return counted.visitFailures() >= FAILURES_TO_PARK
               ? parking(state,
                         counted,
                         ParkRequest.raisedByTemper(RulingKind.WORKER_FAILURES,
                                                    FAILURES_TO_PARK
                                                   + " worker failures in one " + state.stage()
                                                   + " visit; rule to retry or abandon",
                                                    "Return to " + state.stage() + " as a new visit",
                                                    List.of(),
                                                    List.of()),
                         ended,
                         now)
               : requeued(state, counted, ended, now);
    }

    private static Transition requeued(TaskState state, Counters counted, TaskEvent ended, Instant now) {
        return Transition.transition(state.requeued(counted, now), List.of(ended, ready("requeued")), none());
    }

    // ---- T8: expiry ------------------------------------------------------------------------------------------------
    private static Result<Transition> expire(TaskState state, Command.Expire expire, Instant now) {
        return state.status() == Status.LEASED
               ? expiredClock(state, expire, now).map(clock -> expired(state, clock, now))
                             .toResult(TaskError.General.NOT_EXPIRED)
               : refused(state, expire);
    }

    /// The step deadline always applies; the TTL only while no linked job at this epoch is running (§6.2).
    private static Option<String> expiredClock(TaskState state, Command.Expire expire, Instant now) {
        return state.lease()
                    .isStepDeadlinePassed(now)
               ? some("step-deadline")
               : ttlExpired(state, expire, now);
    }

    private static Option<String> ttlExpired(TaskState state, Command.Expire expire, Instant now) {
        return state.lease()
                    .isTtlPassed(now) && !expire.linkedJobActive()
               ? some("ttl")
               : none();
    }

    /// Each expiry revokes the lease; the second in one visit parks (`stuck-lease`), never a third requeue.
    private static Transition expired(TaskState state, String clock, Instant now) {
        var counted = state.counters().plusExpiry();
        var ended = TaskEvent.taskEvent(TaskEvent.Kind.LEASE_EXPIRED, Map.of("clock", clock));

        return counted.visitExpiries() >= EXPIRIES_TO_PARK
               ? parking(state,
                         counted,
                         ParkRequest.raisedByTemper(RulingKind.STUCK_LEASE,
                                                    "Stuck past its lease twice in one " + state.stage()
                                                   + " visit; rule to retry or abandon",
                                                    "Return to " + state.stage() + " as a new visit",
                                                    List.of(),
                                                    List.of()),
                         ended,
                         now)
               : requeued(state, counted, ended, now);
    }

    // ---- controller start (verifier V2 [G]) -------------------------------------------------------------------------
    /// §6.2 [G]: only a lease whose TTL lapsed before the start (`lease_until < start`), whose step deadline has not
    /// passed, and that was not extended at this epoch already, gets start + its stage's TTL. A live lease is left as it
    /// is, and so is one past its step deadline, which the sweep then expires as usual (`not-expired`: nothing to
    /// extend). The step deadline never moves.
    private static Result<Transition> controllerStart(TaskState state, Command.ControllerStart start, Instant now) {
        return state.status() == Status.LEASED
               ? extendedFromStart(state, start, now)
               : refused(state, start);
    }

    private static Result<Transition> extendedFromStart(TaskState state, Command.ControllerStart start, Instant now) {
        return isLapsedAt(state, now) && !state.lease()
                                               .isStepDeadlinePassed(now) && !start.extendedThisEpoch()
               ? success(startExtended(state, now))
               : TaskError.General.NOT_EXPIRED.result();
    }

    private static boolean isLapsedAt(TaskState state, Instant start) {
        return state.lease()
                    .isTtlPassed(start);
    }

    private static Transition startExtended(TaskState state, Instant now) {
        return moved(state.extended(state.lease().extended(now,
                                                           LeaseTerms.forStage(state.stage()))),
                     TaskEvent.taskEvent(TaskEvent.Kind.LEASE_EXTENDED, Map.of("reason", "controller-start")));
    }

    // ---- T10, T11: NEEDS-RULING ------------------------------------------------------------------------------------
    /// A parked task is parked once: a second request waits for the first ruling.
    private static Result<Transition> park(TaskState state, Command.Park park, Instant now) {
        return state.stage() == Stage.NEEDS_RULING
               ? refused(state, park)
               : success(Transition.transition(state.parked(state.counters(), now),
                                               List.of(parkedEvent(park.request())),
                                               some(park.request())));
    }

    private static Result<Transition> rule(TaskState state, RulingDecision decision, Instant now) {
        return isIn(state, Stage.NEEDS_RULING, Status.HELD)
               ? state.raisedFrom()
                      .map(from -> ruledBack(state, decision, from, now))
                      .toResult(TaskError.Refused.FACTORY.apply("rule",
                                                                state.stage(),
                                                                state.status()))
               : TaskError.General.NOT_HELD.result();
    }

    /// T11 returns to `raised_from` in that stage's entry status: READY for a worker stage, and for CI the watcher's
    /// CI/WAITING, since nobody claims CI [G: §5.2 says READY; CI has no READY, pending the design's ruling].
    private static Transition ruledBack(TaskState state, RulingDecision decision, Stage from, Instant now) {
        return entering(state.withCounters(state.counters().plusRoundCap(decision.extraRounds().or(0))),
                        from,
                        TaskEvent.taskEvent(TaskEvent.Kind.RULED,
                                            Map.of("option", decision.option())),
                        "ruled",
                        now);
    }

    // ---- T12: the CI watcher's decision ----------------------------------------------------------------------------
    private static Result<Transition> ciDecision(TaskState state, Command.CiOutcome outcome, Instant now) {
        return isIn(state, Stage.CI, Status.WAITING)
               ? success(decidedCi(state, outcome, now))
               : refused(state, "ci-decision");
    }

    private static Transition decidedCi(TaskState state, Command.CiOutcome outcome, Instant now) {
        return switch (outcome) {
            case Command.CiOutcome.Green _ -> entering(state, Stage.GATE, ciDecided("green", none()), "advanced", now);
            case Command.CiOutcome.Red red -> entering(state.withCounters(state.counters().plusCiRound()),
                                                       Stage.FIX,
                                                       ciDecided("red", some(red.item())),
                                                       "advanced",
                                                       now);
            case Command.CiOutcome.MergeForward merge -> entering(state,
                                                                  Stage.FIX,
                                                                  ciDecided("merge-forward", some(merge.item())),
                                                                  "advanced",
                                                                  now);
            case Command.CiOutcome.Parked parked -> parking(state,
                                                            state.counters(),
                                                            parked.request(),
                                                            ciDecided("park", none()),
                                                            now);
        };
    }

    private static TaskEvent ciDecided(String decision, Option<String> item) {
        return TaskEvent.taskEvent(TaskEvent.Kind.CI_DECIDED,
                                   item.map(text -> Map.of("decision", decision, "item", text))
                                       .or(() -> Map.of("decision", decision)));
    }

    // ---- T14, T15, T16: the maintainer's queue ----------------------------------------------------------------------------
    private static Result<Transition> bounce(TaskState state, String item, Instant now) {
        return isIn(state, Stage.MERGE_READY, Status.HELD)
               ? success(entering(state,
                                  Stage.FIX,
                                  TaskEvent.taskEvent(TaskEvent.Kind.BOUNCED, Map.of("item", item)),
                                  "bounced",
                                  now))
               : TaskError.General.NOT_HELD.result();
    }

    private static Result<Transition> closeMerged(TaskState state, Command.CloseMerged close, Instant now) {
        return isIn(state, Stage.MERGE_READY, Status.HELD)
               ? mergedIfGuarded(state, close, now)
               : TaskError.General.NOT_HELD.result();
    }

    /// T15's guard (§10.5, §7.7): the PR is merged on GitHub and no spot audit is pending.
    private static Result<Transition> mergedIfGuarded(TaskState state, Command.CloseMerged close, Instant now) {
        return close.prMerged()
               ? unaudited(state, now)
               : TaskError.General.NOT_MERGED.result();
    }

    private static Result<Transition> unaudited(TaskState state, Instant now) {
        return state.audit() == Audit.PENDING
               ? TaskError.General.AUDIT_PENDING.result()
               : success(closed(state, Stage.MERGED, "merged", now));
    }

    private static Transition closed(TaskState state, Stage terminal, String how, Instant now) {
        return moved(state.closed(terminal, now),
                     TaskEvent.taskEvent(TaskEvent.Kind.CLOSED, Map.of("how", how)));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------
    /// A new visit of `next`; a `ready` event follows when it is entered READY.
    private static Transition entering(TaskState state, Stage next, TaskEvent first, String reason, Instant now) {
        var entered = state.entered(next, now);

        return Transition.transition(entered,
                                     entered.status() == Status.READY
                                     ? List.of(first, ready(reason))
                                     : List.of(first),
                                     none());
    }

    private static Transition parking(TaskState state,
                                      Counters counted,
                                      ParkRequest request,
                                      TaskEvent first,
                                      Instant now) {
        return Transition.transition(state.parked(counted, now), List.of(first, parkedEvent(request)), some(request));
    }

    private static TaskEvent parkedEvent(ParkRequest request) {
        return TaskEvent.taskEvent(TaskEvent.Kind.PARKED,
                                   Map.of("kind",
                                          request.kind().wire()));
    }

    private static TaskEvent ready(String reason) {
        return TaskEvent.taskEvent(TaskEvent.Kind.READY, Map.of("reason", reason));
    }

    private static Transition moved(TaskState state, TaskEvent event) {
        return Transition.transition(state, List.of(event), none());
    }

    private static boolean isIn(TaskState state, Stage stage, Status status) {
        return state.stage() == stage && state.status() == status;
    }

    private static Result<Transition> refused(TaskState state, Command command) {
        return refused(state, command.name());
    }

    private static Result<Transition> refused(TaskState state, String command) {
        return TaskError.Refused.FACTORY.apply(command,
                                               state.stage(),
                                               state.status())
                                        .result();
    }

    record unused() implements TaskMachine {}
}
