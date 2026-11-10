package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.store.api.Committed;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.NewReview;
import org.pragmatica.temper.store.api.PrAuthor;
import org.pragmatica.temper.store.api.ReviewListing;
import org.pragmatica.temper.store.api.ReviewRow;
import org.pragmatica.temper.store.api.ReviewStore;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.option;


/// [ReviewStore] over Postgres. The per-PR lock is a session-level advisory lock on a dedicated session ([LockSessions]),
/// held across the caller's GitHub call (§5.3); every row write is a statement or a transaction of its own on the main
/// pool and so is committed before the caller goes on.
record PgReviewStore(Db db) implements ReviewStore {
    /// The first key of the per-(repo, pr) lock; the T2 lock is 70700002.
    static final int REVIEW_LOCK = 70700003;

    private static final String ADD_AUTHOR = "INSERT INTO pr_authors (repo, pr, lane, added_by) VALUES ($1, $2::bigint, $3, $4) ON CONFLICT DO NOTHING";

    private static final String AUTHORS = "SELECT lane, added_by, added_at FROM pr_authors WHERE repo = $1 AND pr = $2::bigint ORDER BY lane";

    private static final String LISTING = "SELECT github_review_id, state, sha::text AS sha, sending_at FROM reviews "
                                        + "WHERE repo = $1 AND pr = $2::bigint ORDER BY sending_at, id";

    private static final String COLUMNS = "id, repo, pr, sha::text AS sha, event, state, verdict_id, declared_as, attempts, github_review_id, carry_base_tip::text AS carry_base_tip";

    private static final String REVIEW = "SELECT " + COLUMNS
                                       + " FROM reviews WHERE repo = $1 AND pr = $2::bigint AND sha = $3 AND event = $4";

    private static final String SENDING = "INSERT INTO reviews (repo, pr, sha, event, state, verdict_id, declared_as, carry_base_tip, carry_base_ref, proof, body_sha256) "
                                        + "VALUES ($1, $2::bigint, $3, $4, 'sending', $5::bigint, $6, $7::text, $8::text, $9, $10) "
                                        + "ON CONFLICT (repo, pr, sha, event) DO UPDATE SET state = 'sending', attempts = reviews.attempts + 1, "
                                        + "verdict_id = EXCLUDED.verdict_id, declared_as = EXCLUDED.declared_as, carry_base_tip = EXCLUDED.carry_base_tip, "
                                        + "carry_base_ref = EXCLUDED.carry_base_ref, proof = EXCLUDED.proof, body_sha256 = EXCLUDED.body_sha256, "
                                        + "sending_at = now(), failure = NULL, http_status = NULL RETURNING " + COLUMNS;

    private static final String POSTED = "UPDATE reviews SET state = 'posted', github_review_id = $2::bigint, posted_at = now(), http_status = 200, "
                                       + "ratelimit_remaining = $3::int, failure = NULL WHERE id = $1::bigint RETURNING " + COLUMNS;

    private static final String FAILED = "UPDATE reviews SET state = 'failed', failure = $2, http_status = $3::int, ratelimit_remaining = $4::int "
                                       + "WHERE id = $1::bigint RETURNING " + COLUMNS;

    @Override
    public Promise<List<PrAuthor>> addAuthors(String repo, long pr, List<String> lanes, String addedBy) {
        return db.inTransaction(tx -> Sql.sequence(lanes,
                                                   lane -> Sql.execute(tx, ADD_AUTHOR, repo, pr, lane, addedBy))
                                         .flatMap(_ -> listed(tx, repo, pr)));
    }

    @Override
    public Promise<List<PrAuthor>> authors(String repo, long pr) {
        return listed(db.executor(), repo, pr);
    }

    private static Promise<List<PrAuthor>> listed(QueryExecutor executor, String repo, long pr) {
        return Sql.query(executor, AUTHORS, repo, pr).map(resultSet -> Sql.rows(resultSet)
                                                                          .stream()
                                                                          .map(row -> PrAuthor.prAuthor(row.getString("lane"),
                                                                                                        row.getString("added_by"),
                                                                                                        row.getInstant("added_at")))
                                                                          .toList());
    }

    @Override
    public <T> Promise<Option<T>> tryWithPrLock(String repo, long pr, Supplier<Promise<T>> work) {
        return db.locks()
                 .tryWithLock(REVIEW_LOCK, repo + "#" + pr, work);
    }

    @Override
    public Promise<List<ReviewListing>> reviews(String repo, long pr) {
        return Sql.query(db.executor(),
                         LISTING,
                         repo,
                         pr)
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(row -> ReviewListing.reviewListing(option(row.getLong("github_review_id")),
                                                                               row.getString("state"),
                                                                               row.getString("sha"),
                                                                               row.getInstant("sending_at")))
                                       .toList());
    }

    @Override
    public Promise<Option<ReviewRow>> review(String repo, long pr, String sha, String event) {
        return Sql.query(db.executor(),
                         REVIEW,
                         repo,
                         pr,
                         sha,
                         event)
                  .map(resultSet -> Sql.first(resultSet).map(PgReviewStore::reviewRow));
    }

    @Override
    public Promise<ReviewRow> sending(NewReview review) {
        return Sql.query(db.executor(),
                         SENDING,
                         review.repo(),
                         review.pr(),
                         review.sha(),
                         review.event(),
                         review.verdictId(),
                         review.as(),
                         Sql.nullable(review.carry().map(NewReview.Carry::baseTip)),
                         Sql.nullable(review.carry().map(NewReview.Carry::baseRef)),
                         review.proof(),
                         review.bodySha256())
                  .flatMap(PgReviewStore::onlyRow);
    }

    @Override
    public Promise<Committed<ReviewRow>> posted(long id,
                                                long githubReviewId,
                                                Option<Integer> remaining,
                                                Fn1<List<NewFleetEvent>, ReviewRow> events) {
        return db.inTransaction(tx -> Sql.query(tx,
                                                POSTED,
                                                id,
                                                githubReviewId,
                                                Sql.nullable(remaining))
                                         .flatMap(PgReviewStore::onlyRow)
                                         .flatMap(row -> PgFleetStore.insertAll(tx,
                                                                                events.apply(row))
                                                                     .map(seqs -> Committed.committed(row, seqs))));
    }

    @Override
    public Promise<ReviewRow> failed(long id, String failure, Option<Integer> status, Option<Integer> remaining) {
        return Sql.query(db.executor(),
                         FAILED,
                         id,
                         failure,
                         Sql.nullable(status),
                         Sql.nullable(remaining))
                  .flatMap(PgReviewStore::onlyRow);
    }

    private static Promise<ReviewRow> onlyRow(org.pragmatica.postgres.PgResultSet resultSet) {
        return Sql.first(resultSet)
                  .map(PgReviewStore::reviewRow)
                  .async(StoreError.General.NO_ROW_RETURNED);
    }

    private static ReviewRow reviewRow(PgRow row) {
        return ReviewRow.reviewRow(row.getLong("id"),
                                   row.getString("repo"),
                                   row.getLong("pr"),
                                   row.getString("sha"),
                                   row.getString("event"),
                                   row.getString("state"),
                                   row.getLong("verdict_id"),
                                   row.getString("declared_as"),
                                   row.getInt("attempts"),
                                   option(row.getLong("github_review_id")),
                                   option(row.getString("carry_base_tip")));
    }
}
