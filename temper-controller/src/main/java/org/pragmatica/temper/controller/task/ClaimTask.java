package org.pragmatica.temper.controller.task;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.submit.Mirror;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.store.api.ClaimGrant;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.TaskDetail;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.TaskStore;

import static org.pragmatica.lang.Option.none;


/// `claim <id>` and `claim --stage S` (m3-design §6.1, T2): mint a lease token, resolve the read point a task without a
/// head starts from (the base tip in the mirror, fetched now, §6.5), and claim under `SKIP LOCKED` and the READY fence.
///
/// - Directed: an unknown task is `TaskNotFound` (404), one that is not READY `not-ready` (409). A task that is not the
///   head of its stage is still claimed, and the head it passed over is recorded on the `leased` event.
/// - Head: the first READY task of the stage in dispatcher order (§8); none READY answers none (204). A head another
///   claim takes first is passed and the next head tried, at most [#HEAD_ATTEMPTS] times [G]: each lost race means
///   another worker got a task, and after that many the answer is none rather than an unbounded loop.
///
/// The token is answered once and never stored (§6.4); the store keeps its sha256.
@FunctionalInterface
public interface ClaimTask {
    int HEAD_ATTEMPTS = 8;

    record Request(Option<TaskId> task, Option<Stage> stage, String holder) {
        public static Request directed(TaskId task, String holder) {
            return new Request(Option.some(task), none(), holder);
        }

        public static Request head(Stage stage, String holder) {
            return new Request(none(), Option.some(stage), holder);
        }
    }

    /// The claimed task, its context, the token (only here) and where its repository clones from.
    record Claimed(TaskRow row, TaskDetail detail, String token, String cloneUrl) {
        public static Claimed claimed(TaskRow row, TaskDetail detail, String token, String cloneUrl) {
            return new Claimed(row, detail, token, cloneUrl);
        }

        /// Never prints the token.
        @Override
        public String toString() {
            return "Claimed[task=" + row.id() + ", epoch=" + row.state()
                                                                .epoch() + "]";
        }
    }

    /// A minted lease token and the sha256 the store keeps.
    record Minted(String token, String hash) {
        public static Minted minted(String token, String hash) {
            return new Minted(token, hash);
        }

        @Override
        public String toString() {
            return "Minted[***]";
        }
    }

    /// The candidate a claim goes for, with the head it passes over when directed.
    record Candidate(TaskRow row, Option<TaskId> passedOver) {
        static Candidate candidate(TaskRow row, Option<TaskId> passedOver) {
            return new Candidate(row, passedOver);
        }
    }

    Promise<Option<Claimed>> execute(Request request);

    /// Mints lease tokens (the adapter's strong random source and sha256).
    @FunctionalInterface
    interface Mint {
        Result<Minted> mint();
    }

    /// The steps and settings a claim needs.
    record Steps(TaskStore tasks, Mirror mirror, Mint mint, String cloneUrl, Supplier<Instant> clock) {
        public static Steps steps(TaskStore tasks, Mirror mirror, Mint mint, String cloneUrl, Supplier<Instant> clock) {
            return new Steps(tasks, mirror, mint, cloneUrl, clock);
        }
    }

    static ClaimTask claimTask(Steps steps) {
        return request -> answer(steps, request);
    }

    private static Promise<Option<Claimed>> answer(Steps steps, Request request) {
        return request.task()
                      .map(id -> directed(steps,
                                          id,
                                          request.holder()))
                      .map(ClaimTask::present)
                      .or(() -> headOf(steps, request, HEAD_ATTEMPTS));
    }

    private static Promise<Option<Claimed>> present(Promise<Claimed> claimed) {
        return claimed.map(Option::some);
    }

    // ---- directed ----------------------------------------------------------------------------------------------------
    private static Promise<Claimed> directed(Steps steps, TaskId id, String holder) {
        return steps.tasks()
                    .task(id)
                    .flatMap(row -> row.async(StoreError.TaskNotFound.FACTORY.apply(id)))
                    .flatMap(row -> passedOver(steps, row))
                    .flatMap(candidate -> claimed(steps, candidate, holder));
    }

    /// The head of the task's stage, when it is another task.
    private static Promise<Candidate> passedOver(Steps steps, TaskRow row) {
        return steps.tasks()
                    .ready(Option.some(row.state().stage()),
                           1)
                    .map(heads -> Candidate.candidate(row,
                                                      otherHead(heads,
                                                                row.id())));
    }

    private static Option<TaskId> otherHead(List<TaskRow> heads, TaskId id) {
        return Option.from(heads.stream().map(TaskRow::id).filter(head -> !head.equals(id)).findFirst());
    }

    // ---- head --------------------------------------------------------------------------------------------------------
    private static Promise<Option<Claimed>> headOf(Steps steps, Request request, int attempts) {
        return attempts == 0
               ? Promise.success(none())
               : steps.tasks()
                      .ready(request.stage(),
                             1)
                      .flatMap(heads -> firstHead(steps, request, heads, attempts));
    }

    private static Promise<Option<Claimed>> firstHead(Steps steps, Request request, List<TaskRow> heads, int attempts) {
        return heads.isEmpty()
               ? Promise.success(none())
               : claimed(steps,
                         Candidate.candidate(heads.getFirst(), none()),
                         request.holder()).fold(outcome -> headOutcome(steps, request, outcome, attempts));
    }

    private static Promise<Option<Claimed>> headOutcome(Steps steps,
                                                        Request request,
                                                        Result<Claimed> outcome,
                                                        int attempts) {
        return outcome.fold(cause -> lostRace(steps, request, cause, attempts),
                            claimed -> Promise.success(Option.some(claimed)));
    }

    /// FER: a head another claim took first (`not-ready`) is passed and the next head tried; any other failure is the
    /// answer. Guarantee: a lost race never fails a head claim while another head is READY, within [#HEAD_ATTEMPTS].
    private static Promise<Option<Claimed>> lostRace(Steps steps, Request request, Cause cause, int attempts) {
        return cause == TaskError.General.NOT_READY
               ? headOf(steps, request, attempts - 1)
               : cause.promise();
    }

    // ---- claim -------------------------------------------------------------------------------------------------------
    private static Promise<Claimed> claimed(Steps steps, Candidate candidate, String holder) {
        return baseTip(steps, candidate.row()).flatMap(tip -> granted(steps, candidate, holder, tip));
    }

    /// A task without a head starts from the tip of its base (§6.5), fetched now; a task with one never needs it.
    private static Promise<Option<Sha>> baseTip(Steps steps, TaskRow row) {
        return row.state()
                  .head()
                  .isPresent()
               ? Promise.success(none())
               : steps.mirror()
                      .branchTip(row.baseRef());
    }

    private static Promise<Claimed> granted(Steps steps, Candidate candidate, String holder, Option<Sha> tip) {
        return steps.mint()
                    .mint()
                    .async()
                    .flatMap(minted -> leased(steps, candidate, holder, tip, minted));
    }

    private static Promise<Claimed> leased(Steps steps,
                                           Candidate candidate,
                                           String holder,
                                           Option<Sha> tip,
                                           Minted minted) {
        return steps.tasks()
                    .claim(candidate.row().id(),
                           ClaimGrant.claimGrant(minted.hash(),
                                                 holder,
                                                 tip,
                                                 candidate.passedOver()),
                           steps.clock().get())
                    .map(claimed -> Claimed.claimed(claimed.row(),
                                                    claimed.detail(),
                                                    minted.token(),
                                                    steps.cloneUrl()));
    }
}
