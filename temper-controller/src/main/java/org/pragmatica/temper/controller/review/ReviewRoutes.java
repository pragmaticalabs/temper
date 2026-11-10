package org.pragmatica.temper.controller.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.controller.fleet.FleetRoutes;
import org.pragmatica.temper.controller.fleet.FleetWriter;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.store.api.Committed;
import org.pragmatica.temper.store.api.NewReview;
import org.pragmatica.temper.store.api.PrAuthor;
import org.pragmatica.temper.store.api.ReviewListing;
import org.pragmatica.temper.store.api.ReviewRow;
import org.pragmatica.temper.store.api.ReviewStore;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.T2Store;
import org.pragmatica.temper.store.api.VerdictRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.temper.controller.http.Route.readRoute;
import static org.pragmatica.temper.controller.http.Route.scopedRoute;


/// The routes, each behind the fleet stream's `NOT_READY` gate:
///
/// - `POST fleet/pr-authors` (VERDICT) appends lanes to a PR's author set (201 the whole set); `GET fleet/pr-authors?repo=&pr=` (READ).
/// - `GET fleet/reviews?repo=&pr=` (READ): a JSON array of the PR's review rows, only `githubReviewId` (null until posted), `state`, `sha`,
///   `sendingAt`; an unknown PR answers 200 with an empty list.
/// - `POST fleet/reviews` (VERDICT): the nine steps of §5.3 under the per-(repo, pr) session lock, held across the GitHub
///   call. 200 `{review, …, alreadyPosted}`; 422 `NO_VERDICT`, `VERIFIER_MISMATCH`, `AUTHOR_UNKNOWN`, `SELF_REVIEW`,
///   `NOT_OPEN`, `HEAD_MOVED`, `BASE_MOVED`, `CARRY_NOT_MECHANICAL <reason>`, `GITHUB_REJECTED <line>`; 409 `IN_FLIGHT`;
///   503 `REVIEWER_CREDENTIAL`, `RATE_LIMITED until=<ISO>`, `UNKNOWN`, `CARRY_PROOF_UNAVAILABLE <what>`, `BUSY` (every
///   per-PR lock session in use).
///
/// Seam for WP-4b: `afterPosted` runs under the lock once a review is recorded (step 9's re-read of the latest verdict
/// and the superseded-APPROVE dismissal); WP-4a passes a no-op. The PAT is never here: [ReviewGitHub#post] reads it.
public record ReviewRoutes(Responses responses,
                           JsonMapper mapper,
                           ReviewStore store,
                           T2Store t2,
                           FleetWriter writer,
                           Option<ReviewGitHub> github,
                           Fn1<Option<CarryProof>, String> proofs,
                           String version,
                           Supplier<Instant> clock,
                           BooleanSupplier ready,
                           Fn1<Promise<Unit>, ReviewRow> afterPosted) {
    private static final Logger log = LoggerFactory.getLogger(ReviewRoutes.class);

    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/(?!\\.{1,2}$)[A-Za-z0-9_.-]{1,100}$");

    private static final Pattern SHA40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern LANE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern BY = Pattern.compile("^[!-~]{1,64}$");
    private static final int MAX_BODY_BYTES = 32 * 1024;
    private static final int MAX_LANES = 50;
    private static final String APPROVE = "APPROVE";
    private static final String CHANGES = "REQUEST_CHANGES";
    private static final String SELF_REVIEW = "SELF_REVIEW";
    private static final String VERIFIER_MISMATCH = "VERIFIER_MISMATCH";

    record ReviewBody(String repo, long pr, String sha, String as, Option<String> body) {}

    record AuthorsBody(String repo, long pr, List<String> lanes, String addedBy) {}

    record Call(String repo, long pr, String sha, String as, String body) {}

    record Key(String repo, long pr) {}

    record Authors(String repo, long pr, List<AuthorRow> authors) {}

    record ListedReview(Long githubReviewId, String state, String sha, Instant sendingAt) {}

    record AuthorRow(String lane, String addedBy, Instant addedAt) {}

    record Reply(long review,
                 String repo,
                 long pr,
                 String sha,
                 String event,
                 String as,
                 long verdict,
                 boolean alreadyPosted) {}

    /// What the step-5 proof established, for the row and the header.
    record Basis(VerdictRow verdict, Option<NewReview.Carry> carry, String proof) {}

    /// The reply with the facts the journal line needs and the client does not.
    record Done(Reply reply, Option<Integer> remaining, String proof) {}

    public static ReviewRoutes reviewRoutes(Responses responses,
                                            JsonMapper mapper,
                                            ReviewStore store,
                                            T2Store t2,
                                            FleetWriter writer,
                                            Option<ReviewGitHub> github,
                                            Fn1<Option<CarryProof>, String> proofs,
                                            String version,
                                            Supplier<Instant> clock,
                                            BooleanSupplier ready) {
        return new ReviewRoutes(responses,
                                mapper,
                                store,
                                t2,
                                writer,
                                github,
                                proofs,
                                version,
                                clock,
                                ready,
                                _ -> Promise.unitPromise());
    }

    public List<Route> routes() {
        return List.of(scopedRoute(HttpMethod.POST, "fleet/pr-authors", Route.Scope.VERDICT, gated(this::addAuthors)),
                       readRoute("fleet/pr-authors", gated(this::authors)),
                       readRoute("fleet/reviews", gated(this::reviews)),
                       scopedRoute(HttpMethod.POST, "fleet/reviews", Route.Scope.VERDICT, gated(this::review)));
    }

    private Fn1<Promise<ApiResponse>, ApiRequest> gated(Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return FleetRoutes.gated(responses, ready, handler);
    }

    // ---- author sets (§5.2) ------------------------------------------------------------------------------------
    private Promise<ApiResponse> addAuthors(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                AuthorsBody.class)
                     .flatMap(ReviewRoutes::present)
                     .flatMap(ReviewRoutes::lanes)
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(body -> store.addAuthors(body.repo(),
                                                       body.pr(),
                                                       body.lanes(),
                                                       body.addedBy())
                                           .map(set -> authorsOf(body.repo(),
                                                                 body.pr(),
                                                                 set)))
                     .map(set -> responses.json(HttpStatus.CREATED, set))
                     .mapError(ReviewRoutes::classify);
    }

    private static Result<AuthorsBody> lanes(AuthorsBody body) {
        return Result.all(matching("repo",
                                   body.repo(),
                                   REPO),
                          positive("pr",
                                   body.pr()),
                          option(body.lanes()).filter(lanes -> !lanes.isEmpty() && lanes.size() <= MAX_LANES)
                                .toResult(cause("lanes must hold 1 to " + MAX_LANES + " names"))
                                .flatMap(ReviewRoutes::eachLane),
                          matching("addedBy",
                                   body.addedBy(),
                                   BY))
                     .map(AuthorsBody::new);
    }

    private static Result<List<String>> eachLane(List<String> lanes) {
        return Result.allOf(lanes.stream().map(lane -> matching("lane", lane, LANE)).toList());
    }

    private static Result<Key> key(ApiRequest request) {
        return Result.all(matching("repo",
                                   request.queryParam("repo").or(""),
                                   REPO),
                          request.queryParam("pr")
                                 .toResult(cause("pr required"))
                                 .flatMap(raw -> Result.lift(_ -> cause("pr must be a positive integer"),
                                                             () -> Long.parseLong(raw)))
                                 .flatMap(pr -> positive("pr", pr)))
                     .map(Key::new);
    }

    private Promise<ApiResponse> authors(ApiRequest request) {
        return key(request).mapError(ApiError.BadRequest::badRequest)
                  .async()
                  .flatMap(key -> store.authors(key.repo(),
                                                key.pr())
                                       .map(set -> authorsOf(key.repo(),
                                                             key.pr(),
                                                             set)))
                  .map(responses::ok)
                  .mapError(ReviewRoutes::classify);
    }

    private Promise<ApiResponse> reviews(ApiRequest request) {
        return key(request).mapError(ApiError.BadRequest::badRequest)
                  .async()
                  .flatMap(key -> store.reviews(key.repo(),
                                                key.pr())
                                       .map(ReviewRoutes::listingOf))
                  .map(responses::ok)
                  .mapError(ReviewRoutes::classify);
    }

    private static List<ListedReview> listingOf(List<ReviewListing> rows) {
        return rows.stream()
                   .map(row -> new ListedReview(row.githubReviewId().or((Long) null),
                                                row.state(),
                                                row.sha(),
                                                iso(row.sendingAt())))
                   .toList();
    }

    private static Authors authorsOf(String repo, long pr, List<PrAuthor> set) {
        return new Authors(repo,
                           pr,
                           set.stream()
                              .map(row -> new AuthorRow(row.lane(),
                                                        row.addedBy(),
                                                        iso(row.addedAt())))
                              .toList());
    }

    // ---- reviews (§5.3) ----------------------------------------------------------------------------------------
    private Promise<ApiResponse> review(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                ReviewBody.class)
                     .flatMap(ReviewRoutes::present)
                     .flatMap(ReviewRoutes::call)
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(this::reviewed)
                     .map(done -> responses.ok(done.reply()))
                     .mapError(ReviewRoutes::classify);
    }

    private static Result<Call> call(ReviewBody body) {
        return Result.all(matching("repo",
                                   body.repo(),
                                   REPO),
                          positive("pr",
                                   body.pr()),
                          matching("sha",
                                   lower(body.sha()),
                                   SHA40),
                          matching("as",
                                   body.as(),
                                   LANE),
                          text(body.body()))
                     .map(Call::new);
    }

    /// UTF-8 up to 32 KiB, CRLF as LF (§5.2).
    private static Result<String> text(Option<String> body) {
        var normal = body.or("").replace("\r\n", "\n");

        return Verify.ensure(normal,
                             candidate -> candidate.getBytes(StandardCharsets.UTF_8).length <= MAX_BODY_BYTES,
                             cause("body must be at most 32 KiB"));
    }

    private Promise<Done> reviewed(Call call) {
        return github.async(ApiError.Transient.FACTORY.apply("REVIEWER_CREDENTIAL not-configured"))
                     .flatMap(gh -> store.tryWithPrLock(call.repo(),
                                                        call.pr(),
                                                        () -> locked(gh, call))
                                         .flatMap(held -> held.async(ApiError.Conflict.FACTORY.apply("IN_FLIGHT"))))
                     .withResult(result -> journal(call, result));
    }

    /// Under the lock: steps 1-9.
    private Promise<Done> locked(ReviewGitHub gh, Call call) {
        return t2.latestVerdict(call.repo(),
                                call.pr(),
                                call.sha())
                 .flatMap(found -> found.async(ApiError.Unprocessable.FACTORY.apply("NO_VERDICT")))
                 .flatMap(verdict -> declaredBy(call, verdict))
                 .flatMap(verdict -> notSelf(call).map(_ -> verdict))
                 .flatMap(verdict -> gh.pull(call.repo(),
                                             call.pr())
                                       .flatMap(pull -> standing(pull, call))
                                       .map(_ -> verdict))
                 .flatMap(verdict -> proven(gh, call, verdict))
                 .flatMap(basis -> existing(gh, call, basis));
    }

    /// Step 2: only the lane that declared the verdict posts it.
    private Promise<VerdictRow> declaredBy(Call call, VerdictRow verdict) {
        return verdict.by()
                      .equals(call.as())
               ? Promise.success(verdict)
               : refusedAndAnnounced(call, VERIFIER_MISMATCH);
    }

    /// Step 3: an author set that is empty, or holds `as`, refuses.
    private Promise<Unit> notSelf(Call call) {
        return store.authors(call.repo(),
                             call.pr())
                    .flatMap(set -> set.isEmpty()
                                    ? ApiError.Unprocessable.FACTORY.apply("AUTHOR_UNKNOWN").<Unit> promise()
                                    : set.stream()
                                         .anyMatch(author -> author.lane()
                                                                   .equalsIgnoreCase(call.as()))
                                      ? this.<Unit> refusedAndAnnounced(call, SELF_REVIEW)
                                      : Promise.unitPromise());
    }

    /// `review-refused` (only for the two codes that suggest a mis-wired brief), then the refusal; FER: an event that
    /// cannot be appended does not change the answer.
    private <T> Promise<T> refusedAndAnnounced(Call call, String code) {
        return writer.append(ReviewEvents.refused(clock.get(),
                                                  call.repo(),
                                                  call.pr(),
                                                  code,
                                                  call.as()))
                     .fold(_ -> ApiError.Unprocessable.FACTORY.apply(code).<T> promise());
    }

    /// Step 4: the PR is open and its head is the SHA.
    private static Promise<Unit> standing(ReviewGitHub.PullView pull, Call call) {
        return ! pull.open()
               ? ApiError.General.NOT_OPEN.promise()
               : !pull.headSha()
                      .equals(call.sha())
                 ? ApiError.Unprocessable.FACTORY.apply("HEAD_MOVED").promise()
                 : Promise.unitPromise();
    }

    /// Step 5: a carried verdict row is proven mechanical on the server's own mirror.
    private Promise<Basis> proven(ReviewGitHub gh, Call call, VerdictRow verdict) {
        return verdict.carriedFrom()
                      .map(from -> proofs.apply(call.repo())
                                         .async(ApiError.Transient.FACTORY.apply("CARRY_PROOF_UNAVAILABLE no-mirror"))
                                         .flatMap(proof -> proof.prove(gh,
                                                                       call.repo(),
                                                                       call.pr(),
                                                                       call.sha(),
                                                                       from,
                                                                       verdict.base()))
                                         .map(proven -> new Basis(verdict,
                                                                  Option.some(new NewReview.Carry(proven.baseTip(),
                                                                                                  proven.baseRef())),
                                                                  "carry-proven")))
                      .or(() -> Promise.success(new Basis(verdict,
                                                          Option.none(),
                                                          "fresh")));
    }

    private static String eventOf(VerdictRow verdict) {
        return "ACCEPT".equals(verdict.verdict())
               ? APPROVE
               : CHANGES;
    }

    /// Step 7: the row of (repo, pr, sha, event), if any.
    private Promise<Done> existing(ReviewGitHub gh, Call call, Basis basis) {
        var event = eventOf(basis.verdict());

        return store.review(call.repo(),
                            call.pr(),
                            call.sha(),
                            event)
                    .flatMap(found -> found.map(row -> resolved(gh, call, basis, event, row))
                                           .or(() -> post(gh, call, basis, event)));
    }

    private Promise<Done> resolved(ReviewGitHub gh, Call call, Basis basis, String event, ReviewRow row) {
        return switch (row.state()) {
            case "posted" -> gh.reviews(call.repo(), call.pr()).flatMap(list -> stillActive(list, row, event)
                                                                                ? Promise.success(done(call,
                                                                                                       basis,
                                                                                                       event,
                                                                                                       row.githubReviewId()
                                                                                                          .or(0L),
                                                                                                       true,
                                                                                                       Option.none()))
                                                                                : post(gh, call, basis, event));
            case "sending" -> gh.reviews(call.repo(), call.pr()).fold(result -> result.fold(_ -> post(gh,
                                                                                                      call,
                                                                                                      basis,
                                                                                                      event),
                                                                                            list -> adopted(gh,
                                                                                                            call,
                                                                                                            basis,
                                                                                                            event,
                                                                                                            row,
                                                                                                            list)));
            default -> post(gh, call, basis, event);
        };
    }

    private static boolean stillActive(List<ReviewGitHub.ReviewView> list, ReviewRow row, String event) {
        return list.stream()
                   .anyMatch(review -> Option.some(review.id()).equals(row.githubReviewId()) && review.state()
                                                                                                      .equals(stateOf(event)));
    }

    private static String stateOf(String event) {
        return APPROVE.equals(event)
               ? "APPROVED"
               : "CHANGES_REQUESTED";
    }

    /// A `sending` row: a review of the reviewer on this SHA in the state the event makes, never a dismissed one.
    private Promise<Done> adopted(ReviewGitHub gh,
                                  Call call,
                                  Basis basis,
                                  String event,
                                  ReviewRow row,
                                  List<ReviewGitHub.ReviewView> list) {
        return list.stream()
                   .filter(review -> review.login()
                                           .filter(gh.reviewerLogin()::equalsIgnoreCase)
                                           .isPresent())
                   .filter(review -> review.commitId()
                                           .filter(call.sha()::equals)
                                           .isPresent())
                   .filter(review -> review.state()
                                           .equals(stateOf(event)))
                   .findFirst()
                   .map(review -> recorded(call,
                                           basis,
                                           event,
                                           row,
                                           new ReviewGitHub.Posted(review.id(),
                                                                   Option.none()),
                                           true))
                   .orElseGet(() -> post(gh, call, basis, event));
    }

    // ---- steps 8 and 9 -------------------------------------------------------------------------------------------------
    private Promise<Done> post(ReviewGitHub gh, Call call, Basis basis, String event) {
        var verdict = basis.verdict();
        var text = header(call, basis, event) + call.body();

        return store.sending(NewReview.newReview(call.repo(),
                                                 call.pr(),
                                                 call.sha(),
                                                 event,
                                                 verdict.id(),
                                                 call.as(),
                                                 basis.carry(),
                                                 basis.proof(),
                                                 sha256(call.body())))
                    .flatMap(row -> gh.post(call.repo(),
                                            call.pr(),
                                            call.sha(),
                                            event,
                                            text)
                                      .fold(result -> result.fold(cause -> failed(row, cause),
                                                                  posted -> recorded(call,
                                                                                     basis,
                                                                                     event,
                                                                                     row,
                                                                                     posted,
                                                                                     false))));
    }

    /// The fixed header of the review body (§5.3 step 8): the verdict line, `by`, the report digest, the verdict id, the
    /// carried base tip, the version; then a blank line, then the body file.
    private String header(Call call, Basis basis, String event) {
        var verdict = basis.verdict();

        return "temper review " + verdict.verdict()
             + " -> " + event
             + "\n"
             + "by=" + call.as()
             + "\n"
             + "report_sha256=" + verdict.reportSha256()
             + "\n"
             + "verdict_id=" + verdict.id()
             + "\n" + basis.carry()
                           .map(carry -> "carry_base_tip=" + carry.baseTip() + "\n")
                           .or("")
             + "temper=" + version
             + "\n\n";
    }

    /// 422 / credential / rate limit: the row is `failed`; a POST whose outcome is unknown leaves it `sending`.
    private Promise<Done> failed(ReviewRow row, Cause cause) {
        return switch (cause) {
            case ReviewError.Unknown _ -> cause.promise();
            case ReviewError.Rejected _, ReviewError.Credential _, ReviewError.RateLimited _ -> store.failed(row.id(),
                                                                                                             codeOf(cause),
                                                                                                             Option.some(statusOf(cause)),
                                                                                                             Option.none()).fold(_ -> cause.promise());
            default -> cause.promise();
        };
    }

    private static String codeOf(Cause cause) {
        var wire = classify(cause).message();

        return wire.length() > 480
               ? wire.substring(0, 480)
               : wire;
    }

    private static int statusOf(Cause cause) {
        return Responses.status(classify(cause)).code();
    }

    /// The row `posted` and the `review-posted` event in one transaction; then WP-4b's hook under the same lock.
    private Promise<Done> recorded(Call call,
                                   Basis basis,
                                   String event,
                                   ReviewRow row,
                                   ReviewGitHub.Posted posted,
                                   boolean already) {
        return writer.transaction(() -> store.posted(row.id(),
                                                     posted.id(),
                                                     posted.remaining(),
                                                     recorded -> ReviewEvents.posted(clock.get(),
                                                                                     recorded)),
                                  Committed::seqs)
                     .map(Committed::value)
                     .flatMap(saved -> afterPosted.apply(saved)
                                                  .fold(_ -> Promise.success(saved)))
                     .map(saved -> done(call,
                                        basis,
                                        event,
                                        posted.id(),
                                        already,
                                        posted.remaining()));
    }

    private static Done done(Call call,
                             Basis basis,
                             String event,
                             long review,
                             boolean already,
                             Option<Integer> remaining) {
        return new Done(new Reply(review,
                                  call.repo(),
                                  call.pr(),
                                  call.sha(),
                                  event,
                                  call.as(),
                                  basis.verdict().id(),
                                  already),
                        remaining,
                        basis.proof());
    }

    /// The journal line (§5.4): repo, pr, sha, as, event, verdict, GitHub review id, status, `x-ratelimit-remaining`, the
    /// step-5 outcome. Never the PAT, a header or the body (only its digest is stored).
    private void journal(Call call, Result<Done> result) {
        result.onSuccess(done -> log.info("review {}#{}@{} as={} event={} verdict={} github={} status=200 remaining={} proof={}{}",
                                          call.repo(),
                                          call.pr(),
                                          call.sha().substring(0, 12),
                                          call.as(),
                                          done.reply().event(),
                                          done.reply().verdict(),
                                          done.reply().review(),
                                          done.remaining().map(Object::toString).or("-"),
                                          done.proof(),
                                          done.reply().alreadyPosted()
                                          ? " already-posted"
                                          : ""))
              .onFailure(cause -> log.info("review {}#{}@{} as={} refused status={} {}",
                                           call.repo(),
                                           call.pr(),
                                           call.sha().substring(0, 12),
                                           call.as(),
                                           Responses.status(classify(cause)).code(),
                                           classify(cause).message()));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------------
    /// The wire code of a failure: the GitHub-side kinds become the contract's 422/503 codes.
    static Cause classify(Cause cause) {
        return switch (cause) {
            case ApiError error -> error;
            case ReviewError.Rejected rejected -> ApiError.Unprocessable.FACTORY.apply("GITHUB_REJECTED " + rejected.firstLine());
            case ReviewError.Credential _ -> ApiError.Transient.FACTORY.apply("REVIEWER_CREDENTIAL");
            case ReviewError.RateLimited limited -> ApiError.Transient.FACTORY.apply("RATE_LIMITED until=" + iso(limited.until()));
            case ReviewError.Unknown _ -> ApiError.Transient.FACTORY.apply("UNKNOWN");
            case ReviewError.ReadFailed _ -> ApiError.Transient.FACTORY.apply("UNKNOWN github-read");
            // No lock session free: a capacity refusal, answered at once (§5.4 `BUSY`, 503: `unavailable busy`, exit 69).
            case StoreError.PrLock.BUSY -> ApiError.Transient.FACTORY.apply("BUSY");
            // The lock was lost while the work ran (§5.4 `LOCK_LOST`, 503: `unavailable lock-lost`, exit 69): whether the
            // POST happened is re-read by the retry's step 7.
            case StoreError.PrLock.LOST -> ApiError.Transient.FACTORY.apply("LOCK_LOST");
            default -> cause;
        };
    }

    private static String sha256(String text) {
        return Result.lift(_ -> cause("no SHA-256"),
                           () -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))))
                     .fold(_ -> "", hex -> hex);
    }

    private static Instant iso(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS);
    }

    private static String lower(String raw) {
        return option(raw).map(value -> value.toLowerCase(Locale.ROOT))
                     .or("");
    }

    private static Result<String> matching(String name, String raw, Pattern pattern) {
        return Verify.ensure(option(raw).or(""), Verify.Is::matches, pattern, cause("invalid " + name));
    }

    private static Result<Long> positive(String name, long value) {
        return Verify.ensure(value, Verify.Is::positive, cause(name + " must be a positive integer"));
    }

    private static <T> Result<T> present(T body) {
        return option(body).toResult(cause("body required"));
    }
}
