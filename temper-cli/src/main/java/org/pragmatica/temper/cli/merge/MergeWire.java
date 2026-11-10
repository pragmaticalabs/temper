package org.pragmatica.temper.cli.merge;

import java.util.List;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// The T2 routes' JSON as the CLI reads and writes it, field for field. Nullable fields are
/// [Option]s; lists the controller may omit read as empty. Request bodies with optional members are separate records,
/// so an absent member is absent on the wire rather than `null` (a carry's report fields "must be absent").
public sealed interface MergeWire {
    // --- GET fleet/pr-fresh ---
    record Fleet(String epoch, long seq) {}

    record Pull(String state, String headSha, String headRef, Option<String> headRepo, String base, long changedFiles) {}

    record BaseTip(boolean tracked, Option<String> sha, Option<String> seenAt) {}

    record Check(String context,
                 Option<Long> appId,
                 String outcome,
                 Option<String> observation,
                 Option<String> workflow,
                 Option<Long> runId,
                 Option<Long> runNumber,
                 Option<Long> attempt,
                 Option<String> conclusion) {}

    record VerdictView(long id,
                       String verdict,
                       String by,
                       String at,
                       String base,
                       String reportPath,
                       String reportSha256,
                       Option<String> carriedFrom) {}

    record ServerReason(String check, String line) {}

    /// `pull`, `base`, `baseTip` are absent when kiln could not be asked (the single G7 reason).
    record PrFresh(String repo,
                   long pr,
                   Option<String> sha,
                   String checkedAt,
                   Fleet fleet,
                   Option<Pull> pull,
                   Option<String> base,
                   Option<BaseTip> baseTip,
                   List<Check> checks,
                   Option<VerdictView> verdict,
                   List<ServerReason> reasons,
                   Option<String> freshToken) {
        public PrFresh {
            checks = option(checks).map(List::copyOf).or(List.of());
            reasons = option(reasons).map(List::copyOf).or(List.of());
        }
    }

    // --- POST fleet/go ---
    record GoBody(String repo,
                  long pr,
                  String sha,
                  String base,
                  String baseTip,
                  long verdictId,
                  String fleetEpoch,
                  long fleetSeq,
                  String checkedAt,
                  String freshToken) {}

    record GoCreated(String id, String createdAt, String expiresAt) {}

    // --- consume and outcome ---
    record EmptyBody() {}

    record ConsumedVerdict(String reportPath, String reportSha256) {}

    record Consumed(String id,
                    String repo,
                    long pr,
                    String sha,
                    String base,
                    String baseTip,
                    long verdictId,
                    String consumedAt,
                    List<String> invalidated,
                    ConsumedVerdict verdict) {
        public Consumed {
            invalidated = option(invalidated).map(List::copyOf).or(List.of());
        }
    }

    /// `{"outcome":"merged"}`.
    record OutcomeOnly(String outcome) {}

    /// `{"outcome":"refused|merge-failed","detail":…}`.
    record OutcomeDetail(String outcome, String detail) {}

    /// `{"outcome":"merging","baseTipBefore":…}`.
    record OutcomeMerging(String outcome, String baseTipBefore) {}

    // --- verdicts ---
    record ReportVerdict(String repo,
                         long pr,
                         String sha,
                         String base,
                         String verdict,
                         String by,
                         String reportPath,
                         String reportSha256,
                         String verdictLine) {}

    record CarriedVerdict(String repo,
                          long pr,
                          String sha,
                          String base,
                          String verdict,
                          String by,
                          String carriedFrom) {}

    /// `reportSha256`: the registered row's digest; for a carry, the carried row's (ruling 11-03, item 4).
    record Registered(long id, String at, String reportSha256, List<String> invalidated) {
        public Registered {
            invalidated = option(invalidated).map(List::copyOf).or(List.of());
        }
    }

    // --- POST/GET fleet/pr-authors, POST fleet/reviews ---
    record AuthorsAdd(String repo, long pr, List<String> lanes, String addedBy) {}

    record AuthorRow(String lane, String addedBy, String addedAt) {}

    record Authors(String repo, long pr, List<AuthorRow> authors) {
        public Authors {
            authors = option(authors).map(List::copyOf).or(List.of());
        }
    }

    /// The review event is not a member: the controller derives it from the verdict registry.
    record ReviewPost(String repo, long pr, String sha, String as) {}

    record ReviewPostWithBody(String repo, long pr, String sha, String as, String body) {}

    record Reviewed(long review,
                    String repo,
                    long pr,
                    String sha,
                    String event,
                    String as,
                    long verdict,
                    boolean alreadyPosted) {}

    // --- merge queue ---
    record QueueGo(String id, String sha, String expiresAt) {}

    record QueueEntry(int position, long pr, String addedBy, String addedAt, Option<QueueGo> go) {}

    record Queue(String repo, String base, List<QueueEntry> entries) {
        public Queue {
            entries = option(entries).map(List::copyOf).or(List.of());
        }
    }

    record Queues(List<Queue> queues) {
        public Queues {
            queues = option(queues).map(List::copyOf).or(List.of());
        }
    }

    record QueueAdd(String repo, long pr, String addedBy) {}

    record QueueAddAt(String repo, long pr, String base, String addedBy) {}

    record QueueRemove(String repo, long pr) {}

    /// `base` given: the row must be at it (lead guess 1: the controller answers 404 `NOT_QUEUED` otherwise).
    record QueueRemoveAt(String repo, long pr, String base) {}

    record QueueMove(String repo, long pr, int to) {}

    record QueueMoveAt(String repo, long pr, int to, String base) {}

    record QueuePosition(String repo, String base, long pr, int position) {}

    // --- ci-control-paths ---
    record PathEntry(String glob, String addedBy, String addedAt) {}

    record Paths(String repo, List<PathEntry> globs) {
        public Paths {
            globs = option(globs).map(List::copyOf).or(List.of());
        }
    }

    record PathAdd(String repo, String glob, String addedBy) {}

    record PathRemove(String repo, String glob) {}

    record unused() implements MergeWire {}
}
