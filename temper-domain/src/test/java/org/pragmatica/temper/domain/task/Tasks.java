package org.pragmatica.temper.domain.task;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;

import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Builders for [TaskMachine] tests: states in any (stage, status), a sample of every command, and a runner that
/// applies a command sequence (each must succeed) on a moving clock.
final class Tasks {
    static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");
    static final Sha READ_POINT = Sha.sha("0cf563192a" + "0".repeat(30)).unwrap();
    static final Sha HEAD = Sha.sha("a".repeat(40)).unwrap();
    static final LeaseTerms TERMS = LeaseTerms.forStage(Stage.CODE);

    private Tasks() {}

    /// A task in `stage`/`status` at visit 1; LEASED at epoch 1 with clocks from [#T0]; NEEDS_RULING raised from CODE;
    /// WAITING on job 7 (CI waits on CI).
    static TaskState state(Stage stage, Status status) {
        return TaskState.taskState(stage,
                                   status,
                                   stage == Stage.NEEDS_RULING
                                   ? some(Stage.CODE)
                                   : none(),
                                   Counters.counters(0, 3, 0, 1, 0, 0),
                                   status == Status.LEASED
                                   ? LeaseState.leaseState(1,
                                                           some("hash"),
                                                           some("w"),
                                                           some(T0.plus(TERMS.ttl())),
                                                           some(T0.plus(TERMS.stepTimeout())),
                                                           some(READ_POINT))
                                   : LeaseState.leaseState(1, none(), none(), none(), none(), some(READ_POINT)),
                                   some(HEAD),
                                   some(12),
                                   status == Status.READY
                                   ? some(T0)
                                   : none(),
                                   status == Status.WAITING
                                   ? stage == Stage.CI
                                     ? Waiting.CI_ONLY
                                     : Waiting.waiting(List.of(JobId.jobId(7).unwrap()), false)
                                   : Waiting.NOTHING,
                                   Audit.NONE,
                                   status == Status.CLOSED
                                   ? some(T0)
                                   : none());
    }

    static ParkRequest request() {
        return ParkRequest.requested(RulingKind.DESIGN,
                                     "Which API shape?",
                                     List.of(ParkRequest.Choice.choice("a", "Shape A").unwrap(),
                                             ParkRequest.Choice.choice("b", "Shape B").unwrap()),
                                     ParkRequest.Recommendation.recommendation("a", "smaller", false).unwrap(),
                                     ParkRequest.Blocked.blocked(List.of(), "nothing else").unwrap())
                          .unwrap();
    }

    static Command claim() {
        return new Command.Claim("hash-2", "w2", READ_POINT);
    }

    static Command evidence(Stage next) {
        return new Command.Evidence(5, none(), outcome(some(next), EvidenceOutcome.Verdict.NONE));
    }

    static Command verify(EvidenceOutcome.Verdict verdict, List<String> open) {
        return new Command.Evidence(5,
                                    some(4L),
                                    EvidenceOutcome.evidenceOutcome(verdict == EvidenceOutcome.Verdict.PASS
                                                                    ? some(Stage.CI)
                                                                    : none(),
                                                                    verdict,
                                                                    false,
                                                                    some(HEAD),
                                                                    none(),
                                                                    open));
    }

    static EvidenceOutcome outcome(Option<Stage> next, EvidenceOutcome.Verdict verdict) {
        return EvidenceOutcome.evidenceOutcome(next, verdict, false, some(HEAD), none(), List.of());
    }

    static Command rule(Option<Integer> extra) {
        return new Command.Rule(RulingDecision.rulingDecision("a", "go", none(), extra).unwrap());
    }

    static Command release(String reason) {
        return new Command.Release(ReleaseReason.releaseReason(reason).unwrap());
    }

    static Command waitOnJob() {
        return new Command.Wait(Waiting.waiting(List.of(JobId.jobId(9).unwrap()), false));
    }

    /// Applies `command` to `state` at `at`; the command must be accepted.
    static Transition applied(TaskState state, Command command, Instant at) {
        return TaskMachine.apply(state, command, at)
                          .onFailure(cause -> fail(command.name() + " in " + state.stage() + "/" + state.status() + " refused: "
                                                   + cause.message()))
                          .unwrap();
    }

    static Instant later(Duration by) {
        return T0.plus(by);
    }
}
