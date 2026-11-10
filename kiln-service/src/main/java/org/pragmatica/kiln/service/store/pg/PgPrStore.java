package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.core.summary.Changes;
import org.pragmatica.kiln.core.summary.PrSnapshot;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;

import static org.pragmatica.lang.Option.option;


/// [PrStore] over `pr`, `pr_mirror` and `gh_cursor`. No statement here deletes a `pr` row, and the table's triggers
/// refuse one (K-13). [#upsert] reads the stored states and heads, writes the PRs and inserts the `PR_STATE` and
/// `HEAD_MOVED` changes about `repo` in one transaction, so a change commits or rolls back with its value. A PR not yet
/// stored has no head to move from: its first sighting records nothing, except a merge first seen after the backfill
/// ([Changes#firstMerged], judged on the `backfill_done` cursor read in the same transaction). Every `PR_STATE` row
/// carries the listed PR's [Changes#prStatePayload].
record PgPrStore(Db db, String repo, ChangeDiff diff, Watermark committed, HighWater feedMark) implements PrStore {
    private static final String HIGH_WATER = "pulls_high_water";
    private static final String BACKFILL_DONE = "backfill_done";

    private static final String STATES = "SELECT number, state, head_sha, base_ref FROM pr WHERE number = ANY($1::text[]::bigint[]) FOR UPDATE";

    private static final String UPSERT = "INSERT INTO pr (number, title, state, draft, base_ref, head_ref, head_repo, head_sha, merged_at, "
                                       + "closed_at, updated_at, labels, closes, part_of, observed_at) "
                                       + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12::text[], $13::text[]::bigint[], "
                                       + "$14::text[]::bigint[], $15) ON CONFLICT (number) DO UPDATE SET title = EXCLUDED.title, "
                                       + "state = EXCLUDED.state, draft = EXCLUDED.draft, base_ref = EXCLUDED.base_ref, "
                                       + "head_ref = EXCLUDED.head_ref, head_repo = EXCLUDED.head_repo, head_sha = EXCLUDED.head_sha, "
                                       + "merged_at = EXCLUDED.merged_at, closed_at = EXCLUDED.closed_at, updated_at = EXCLUDED.updated_at, "
                                       + "labels = EXCLUDED.labels, closes = EXCLUDED.closes, part_of = EXCLUDED.part_of, "
                                       + "observed_at = EXCLUDED.observed_at, "
                                       + "head_committed_at = CASE WHEN pr.head_sha = EXCLUDED.head_sha THEN pr.head_committed_at END";

    /// An absent head date keeps the stored one: a failed or skipped `commits/{sha}` read never erases a known date.
    private static final String DETAIL = "UPDATE pr SET mergeable = $2, mergeable_state = $3, "
                                       + "head_committed_at = coalesce($4, head_committed_at), detail_at = $5 WHERE number = $1";

    private static final String BEHIND = "INSERT INTO pr_mirror (pr, head_sha, base_ref, behind_by, behind_tree_empty, fetched_at) "
                                       + "VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT (pr) DO UPDATE SET head_sha = EXCLUDED.head_sha, "
                                       + "base_ref = EXCLUDED.base_ref, behind_by = EXCLUDED.behind_by, "
                                       + "behind_tree_empty = EXCLUDED.behind_tree_empty, fetched_at = EXCLUDED.fetched_at";

    private static final String CURSOR = "INSERT INTO gh_cursor (name, value) VALUES ($1, $2) ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value";

    @Override
    public Promise<List<Change>> upsert(List<PullRequest> prs, Map<PrNumber, String> mergedBy, Instant observedAt) {
        return committed.tracked(recordedAt -> db.inTransaction(tx -> upsertIn(tx, prs, mergedBy, observedAt, recordedAt)))
                        .flatMap(changes -> feedMark.after(db, changes));
    }

    @Override
    public Promise<Unit> openDetail(PrNumber pr, OpenDetail detail, Instant observedAt) {
        return Sql.execute(db.executor(),
                           DETAIL,
                           pr.value(),
                           Sql.nullable(detail.mergeable()),
                           Sql.nullable(detail.mergeableState()),
                           Sql.nullable(detail.headCommittedAt().map(Sql::utc)),
                           Sql.utc(observedAt));
    }

    @Override
    public Promise<Unit> behind(PrNumber pr, Sha head, BranchName base, BehindFacts facts, Instant fetchedAt) {
        return Sql.execute(db.executor(),
                           BEHIND,
                           pr.value(),
                           head.value(),
                           base.value(),
                           Sql.nullable(facts.behindBy()),
                           Sql.nullable(facts.behindTreeEmpty()),
                           Sql.utc(fetchedAt));
    }

    @Override
    public Promise<List<PullRequest>> all() {
        return Sql.query(db.executor(),
                         "SELECT " + PrRows.COLUMNS + " FROM pr p ORDER BY p.number")
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PrRows::pullRequest));
    }

    @Override
    public Promise<Option<Instant>> highWater() {
        return readCursor(HIGH_WATER);
    }

    @Override
    public Promise<Unit> highWater(Instant at) {
        return writeCursor(HIGH_WATER, at);
    }

    @Override
    public Promise<Option<Instant>> backfillDone() {
        return readCursor(BACKFILL_DONE);
    }

    @Override
    public Promise<Unit> backfillDone(Instant at) {
        return writeCursor(BACKFILL_DONE, at);
    }

    private Promise<Option<Instant>> readCursor(String name) {
        return readCursor(db.executor(), name);
    }

    private static Promise<Option<Instant>> readCursor(QueryExecutor executor, String name) {
        return Sql.query(executor, "SELECT value FROM gh_cursor WHERE name = $1", name).flatMap(resultSet -> Sql.parseFirst(resultSet,
                                                                                                                            PgPrStore::cursor));
    }

    private Promise<Unit> writeCursor(String name, Instant at) {
        return Sql.execute(db.executor(), CURSOR, name, at.toString());
    }

    private Promise<List<Change>> upsertIn(Transaction tx,
                                           List<PullRequest> prs,
                                           Map<PrNumber, String> mergedBy,
                                           Instant observedAt,
                                           Instant recordedAt) {
        return readCursor(tx, BACKFILL_DONE).flatMap(backfillDone -> changesIn(tx, prs, backfillDone, observedAt))
                         .map(changes -> PgChangeStore.recorded(changes, recordedAt))
                         .flatMap(changes -> writeAll(tx, prs, mergedBy, observedAt, changes));
    }

    private Promise<List<Change>> changesIn(Transaction tx,
                                            List<PullRequest> prs,
                                            Option<Instant> backfillDone,
                                            Instant observedAt) {
        return Sql.query(tx,
                         STATES,
                         (Object) PrRows.texts(prs.stream().map(PullRequest::number).toList()))
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgPrStore::stored))
                  .map(before -> changes(before, prs, backfillDone, observedAt));
    }

    /// The change rule's changes and the first-sighting merges, in the rule's order.
    private List<Change> changes(List<Seen> before,
                                 List<PullRequest> prs,
                                 Option<Instant> backfillDone,
                                 Instant observedAt) {
        return Stream.concat(diff.between(snapshot(before),
                                          snapshot(observed(prs)),
                                          observedAt).stream(),
                             Changes.firstMerged(prs,
                                                 numbers(before),
                                                 backfillDone,
                                                 observedAt).stream())
                     .sorted(Changes.ORDER)
                     .toList();
    }

    private static Set<PrNumber> numbers(List<Seen> seen) {
        return seen.stream()
                   .map(Seen::number)
                   .collect(Collectors.toSet());
    }

    private Promise<List<Change>> writeAll(Transaction tx,
                                           List<PullRequest> prs,
                                           Map<PrNumber, String> mergedBy,
                                           Instant observedAt,
                                           List<Change> changes) {
        var listed = listedByNumber(prs);

        return Sql.sequence(prs, pr -> write(tx, pr, observedAt)).flatMap(_ -> PgChangeStore.insert(tx,
                                                                                                    feedMark,
                                                                                                    repo,
                                                                                                    changes,
                                                                                                    change -> payload(listed,
                                                                                                                      mergedBy,
                                                                                                                      change)));
    }

    private static Map<PrNumber, PullRequest> listedByNumber(List<PullRequest> prs) {
        return Changes.lastCopies(prs)
                      .stream()
                      .collect(Collectors.toMap(PullRequest::number,
                                                Function.identity()));
    }

    /// A `PR_STATE` row's facts come from the PR as listed (its last copy), with the `mergedBy` read for it;
    /// every other kind's payload stays NULL.
    private static Option<Map<String, Object>> payload(Map<PrNumber, PullRequest> listed,
                                                       Map<PrNumber, String> mergedBy,
                                                       Change change) {
        return option(listed.get(change.pr())).filter(_ -> change.kind() == ChangeKind.PR_STATE)
                     .map(pr -> Changes.prStatePayload(pr,
                                                       option(mergedBy.get(change.pr()))));
    }

    private static Promise<Unit> write(Transaction tx, PullRequest pr, Instant observedAt) {
        return Sql.execute(tx,
                           UPSERT,
                           pr.number().value(),
                           pr.title(),
                           Rows.lower(pr.state()),
                           pr.draft(),
                           pr.base().value(),
                           pr.headRef().value(),
                           Sql.nullable(pr.headRepo()),
                           pr.head().value(),
                           Sql.nullable(pr.mergedAt().map(Sql::utc)),
                           Sql.nullable(pr.closedAt().map(Sql::utc)),
                           Sql.utc(pr.updatedAt()),
                           Sql.texts(pr.labels().stream()),
                           PrRows.texts(pr.closes()),
                           PrRows.texts(pr.partOf()),
                           Sql.utc(observedAt));
    }

    /// A PR's state and head, as stored or as observed: what the change rule compares.
    record Seen(PrNumber number, PrState state, PrSnapshot.Head head) {}

    private static Result<Seen> stored(PgRow row) {
        return Result.all(PrNumber.prNumber(row.getLong("number")),
                          Rows.constant(PrState.class,
                                        row.getString("state")),
                          Sha.sha(row.getString("head_sha")),
                          BranchName.branchName(row.getString("base_ref")))
                     .map((number, state, head, base) -> new Seen(number,
                                                                  state,
                                                                  new PrSnapshot.Head(head, base)))
                     .mapError(Rows.corrupt(PrRows.TABLE));
    }

    /// The last copy of a PR listed twice decides, as the upsert's last write does.
    private static List<Seen> observed(List<PullRequest> prs) {
        return prs.stream()
                  .map(pr -> new Seen(pr.number(),
                                      pr.state(),
                                      new PrSnapshot.Head(pr.head(),
                                                          pr.base())))
                  .toList();
    }

    private static PrSnapshot snapshot(List<Seen> seen) {
        return new PrSnapshot(seen.stream().collect(Collectors.toMap(Seen::number, Seen::state, (_, last) -> last)),
                              Map.of(),
                              seen.stream().collect(Collectors.toMap(Seen::number, Seen::head, (_, last) -> last)));
    }

    private static Result<Instant> cursor(PgRow row) {
        return Rows.parseInstant(row.getString("value")).mapError(Rows.corrupt("gh_cursor"));
    }
}
