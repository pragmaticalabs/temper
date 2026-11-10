package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The T2 merge gate's tables. Every T2 writing transaction first takes the T2 lock
/// (`pg_advisory_xact_lock(70700002)`), then, when it inserts fleet events, the order lock, so T2 writers are
/// serialised among themselves and the lock order is total.
public interface T2Store {
    /// One `t2:detector` batch in ONE transaction under the T2 lock: the detector's cursor and `started_at`,
    /// the fleet rows after the cursor (at most `limit`, or the gap when the floor is above it) and the outstanding
    /// GOs, handed to `plan`
    /// with a reader of the merge facts bound to the same transaction; then the batch `plan` answers is
    /// written: the GO invalidations, the `merges` rows, the queue removals, the cursor with `last_ok_at`, and the events last (order lock).
    /// Answers the lowest and highest seq inserted (none when no event was).
    Promise<Option<FleetSeqs>> detect(int limit, Fn2<Promise<DetectorBatch>, DetectorInput, MergeFacts.Reader> plan);

    /// Registers a verdict in ONE transaction under the T2 lock: the row (a carry copies the report fields of
    /// the latest row at its carried SHA, which must be ACCEPT at the same base, else [StoreError.CarryNoAccept]), then
    /// every outstanding GO for (repo, pr) invalidated with `verdict-changed`, then the events `events` makes of
    /// the result, inserted last under the order lock.
    Promise<Committed<RegisteredVerdict>> register(NewVerdict verdict,
                                                   Fn1<List<NewFleetEvent>, RegisteredVerdict> events);

    /// The latest verdict row by id for (repo, pr, sha), a single read; none when the head has none.
    Promise<Option<VerdictRow>> latestVerdict(String repo, long pr, String sha);
    /// Creates a GO (steps 1-4, the token checked by the caller) in ONE transaction under the T2 lock: temper's
    /// epoch ≠ `fleetEpoch` → [StoreError.EpochChanged]; the latest verdict id for (repo, pr, sha) ≠ `verdictId` →
    /// [StoreError.CreationInvalidated] `verdict-changed`; the fleet rows after `fleetSeq` through the head, in seq
    /// order, handed to `scan`, whose first reason is [StoreError.CreationInvalidated]; else the row is inserted.
    Promise<CreatedGo> createGo(NewGo go, Fn1<Option<String>, List<FleetEventRow>> scan);
    /// Consumes the GO in ONE transaction under the T2 lock: the single-use update; then every other outstanding
    /// GO of its (repo, base) invalidated with `sibling-consumed` and the events `events` makes of the result, inserted
    /// last. Zero rows updated: [StoreError.GoNotFound], [StoreError.GoUsed], [StoreError.GoInvalidated] or
    /// [StoreError.GoExpired], classified in the same transaction.
    Promise<Committed<ConsumedGo>> consume(String id, Fn1<List<NewFleetEvent>, ConsumedGo> events);

    /// Records an outcome in ONE transaction under the T2 lock: only `consumed→refused|merging` and
    /// `merging→merged|merge-failed`, else [StoreError.BadTransition] ([StoreError.GoNotFound] for no row). `merged`
    /// inserts the `merges` row (route `temper-merge`) unless (repo, pr) has one already (R3-2.i: one merge per PR,
    /// across both routes), and when it did, removes the PR from its queue; the events `events` makes of the result
    /// are inserted last, and the row's `event_seq` is the first of them (the `merged` event, WP-0T item 3).
    Promise<Committed<RecordedOutcome>> outcome(GoOutcome outcome, Fn1<List<NewFleetEvent>, RecordedOutcome> events);

    /// The merge queues, ordered by repo, base, position; `repo` and `base` filter when given.
    Promise<List<QueuedPr>> queues(Option<String> repo, Option<String> base);
    /// Appends the PR at position n+1 of (repo, base), under the T2 lock; [StoreError.AlreadyQueued] when it is queued.
    Promise<QueueEntry> enqueue(String repo, String base, long pr, String addedBy);
    /// Removes the PR from its queue (at `base` only, when given), the later positions moving down by one, under the T2
    /// lock; answers the entry as it was; [StoreError.NotQueued] when there is none.
    Promise<QueueEntry> dequeue(String repo, long pr, Option<String> base);
    /// Moves the PR to position `to` of its queue (at `base` only, when given), the positions between moving by one so
    /// they stay dense, under the T2 lock; [StoreError.NotQueued], or [StoreError.PositionOutOfRange] unless
    /// 1 ≤ `to` ≤ n.
    Promise<QueueEntry> move(String repo, long pr, int to, Option<String> base);
    /// The repo's G9 globs, by glob.
    Promise<List<ControlPath>> controlPaths(String repo);
    /// Adds a G9 glob under the T2 lock; [StoreError.PathExists] when the repo has it.
    Promise<ControlPath> addControlPath(String repo, String glob, String addedBy);
    /// Removes a G9 glob under the T2 lock; [StoreError.PathNotFound] when the repo has none.
    Promise<Unit> removeControlPath(String repo, String glob);
}
