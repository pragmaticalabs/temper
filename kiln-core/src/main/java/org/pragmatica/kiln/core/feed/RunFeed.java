package org.pragmatica.kiln.core.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// `PUSH_RUN_CONCLUDED`, `SCHEDULED_RUN_CONCLUDED` and `RUN_CANCELLED` (design §2.1, review F11, R2-11).
///
/// A completed run is reported when kiln had not stored it as completed with the same conclusion and attempt: a first
/// sight of a completed run (it concluded while kiln was not looking), a run that completed since, and a rerun (a new
/// `run_attempt`, or a conclusion that changed). A run still going reports nothing. A `cancelled` conclusion is also one
/// `RUN_CANCELLED` (design §8: a cancelled push run on a release branch is an alarm), beside its `*_RUN_CONCLUDED`, so a
/// reader waiting for the conclusion is never left waiting.
public sealed interface RunFeed {
    String SCHEDULE = "schedule";
    String PUSH = "push";
    String CANCELLED = "cancelled";
    /// The read window reaches this far behind the newest `created_at` stored (design §2.1).
    Duration OVERLAP = Duration.ofMinutes(10);
    /// A run seen this recently is re-read by id (R2-11), and a first read reaches this far back.
    Duration RECENT = Duration.ofDays(7);

    /// A run as kiln last stored it.
    record Stored(String status, Option<String> conclusion, long attempt) {
        boolean same(WorkflowRun run) {
            return WorkflowRun.COMPLETED.equals(status)
                   && conclusion.equals(run.conclusion())
                   && attempt == run.attempt();
        }
    }

    /// The `created>=` start of the window: [#OVERLAP] before the newest stored run's `created_at`, else [#RECENT]
    /// before `at`.
    static Instant windowStart(Option<Instant> newestCreated, Instant at) {
        return newestCreated.map(created -> created.minus(OVERLAP))
                            .or(at.minus(RECENT));
    }

    static List<FeedChange> changes(Option<Stored> stored, WorkflowRun run, Instant at) {
        return run.completed() && !stored.filter(previous -> previous.same(run))
                                         .isPresent()
               ? reported(stored, run, at)
               : List.of();
    }

    private static List<FeedChange> reported(Option<Stored> stored, WorkflowRun run, Instant at) {
        var conclusion = run.conclusion().or(WorkflowRun.COMPLETED);
        var concluded = change(concludedKind(run), stored, run, conclusion, at);

        return CANCELLED.equals(conclusion)
               ? List.of(concluded, change(FeedKind.RUN_CANCELLED, stored, run, conclusion, at))
               : List.of(concluded);
    }

    private static FeedKind concludedKind(WorkflowRun run) {
        return scheduled(run)
               ? FeedKind.SCHEDULED_RUN_CONCLUDED
               : FeedKind.PUSH_RUN_CONCLUDED;
    }

    static boolean scheduled(WorkflowRun run) {
        return run.event()
                  .filter(SCHEDULE::equals)
                  .isPresent();
    }

    /// Subject: the branch of a push run, the workflow of a scheduled one.
    private static FeedChange change(FeedKind kind,
                                     Option<Stored> stored,
                                     WorkflowRun run,
                                     String conclusion,
                                     Instant at) {
        return new FeedChange(kind,
                              none(),
                              scheduled(run)
                              ? run.name()
                              : run.headBranch().or(run.name()),
                              stored.flatMap(Stored::conclusion).or(FeedChange.NONE),
                              conclusion,
                              at,
                              FeedChange.payload("workflow",
                                                 run.name(),
                                                 "runId",
                                                 run.id(),
                                                 "attempt",
                                                 run.attempt(),
                                                 "conclusion",
                                                 conclusion,
                                                 "event",
                                                 run.event(),
                                                 "branch",
                                                 run.headBranch(),
                                                 "headSha",
                                                 run.headSha(),
                                                 "url",
                                                 run.htmlUrl(),
                                                 "createdAt",
                                                 run.createdAt().toString(),
                                                 "previousAttempt",
                                                 stored.map(Stored::attempt)));
    }

    /// The runs to re-read by id this cycle: at most `cap` of `candidates`, which come oldest-refreshed first.
    static List<Long> reread(List<Long> candidates, int cap) {
        return candidates.stream()
                         .limit(Math.max(0, cap))
                         .toList();
    }

    record unused() implements RunFeed {}
}
