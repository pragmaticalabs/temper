package org.pragmatica.temper.store.api;

import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// The tables (V16): the author sets and the reviews. The exemptions and the
/// budget samples are WP-4b's and WP-5's.
public interface ReviewStore {
    /// Adds the lanes to the PR's author set (a lane already there, case-insensitively, is kept as it was) and answers
    /// the whole set, by lane.
    Promise<List<PrAuthor>> addAuthors(String repo, long pr, List<String> lanes, String addedBy);
    /// The PR's author set, by lane.
    Promise<List<PrAuthor>> authors(String repo, long pr);
    /// Runs `work` while a dedicated session holds the session-level advisory lock `(70700003, hashtext(repo || '#' ||
    /// pr))` (§5.3), and releases it only after `work`'s promise completed, whatever it answered; `work` is not cut
    /// off (its own calls carry the time bounds). `none` when another session holds the lock: `work` is not run.
    /// Fails with [StoreError.PrLock#BUSY] at once when every lock session is in use, and with
    /// [StoreError.PrLock#LOST] when `work` succeeded but its session lost the lock before releasing it.
    <T> Promise<Option<T>> tryWithPrLock(String repo, long pr, Supplier<Promise<T>> work);
    /// The PR's review rows (all states), by `sending_at` then id; empty for an unknown PR.
    Promise<List<ReviewListing>> reviews(String repo, long pr);
    /// The row of (repo, pr, sha, event), if any.
    Promise<Option<ReviewRow>> review(String repo, long pr, String sha, String event);
    /// Inserts the row as `sending`, or turns the existing row of that key into `sending` with `attempts` + 1 (a
    /// `failed`, a dismissed `posted` or a left-behind `sending` row); committed at once, before the POST.
    Promise<ReviewRow> sending(NewReview review);

    /// Marks the row `posted` with GitHub's review id and inserts the events `events` makes of it, in one transaction
    /// (the fleet writer's contract, as [T2Store#register]).
    Promise<Committed<ReviewRow>> posted(long id,
                                         long githubReviewId,
                                         Option<Integer> remaining,
                                         Fn1<List<NewFleetEvent>, ReviewRow> events);

    /// Marks the row `failed` with the code and the first line of the reason (the PAT is never in it).
    Promise<ReviewRow> failed(long id, String failure, Option<Integer> status, Option<Integer> remaining);
}
