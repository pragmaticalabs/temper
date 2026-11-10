package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.pragmatica.kiln.core.github.BranchRead;
import org.pragmatica.kiln.core.github.CheckRun;
import org.pragmatica.kiln.core.github.CommitStatus;
import org.pragmatica.kiln.core.github.Counted;
import org.pragmatica.kiln.core.github.PullRead;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.Rulesets;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.api.Views;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.config.KilnConfigLoader;
import org.pragmatica.kiln.service.http.ApiError;
import org.pragmatica.kiln.service.http.ApiRequest;
import org.pragmatica.kiln.service.http.ApiResponse;
import org.pragmatica.kiln.service.http.Route;
import org.pragmatica.kiln.service.store.BaseTip;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// `GET /api/v1/pr-fresh?repo=&pr=[&sha=]`: the merge gate's fresh reads of one PR head, judged
/// here for G2-G5 and G10. Loopback only, no auth, as every kiln route. It runs on the HTTP handler thread and **writes
/// nothing** (R2-7): its reads are [FreshReads] (no `If-None-Match`, no ETag stored), and its one store access is the
/// SELECT of `base_tip` (S1, `tips`). Reads in order R1 `pulls/{pr}`, R2 `branches/{base}`, R3
/// `rules/branches/{base}`, R4 check runs, R5 the head's workflow runs, R6 statuses, R7 the PR files (at most
/// [#FILE_PAGES] pages), R8 and R9 the list again, then S1. Without `sha`, R1 only, and only `pull` is answered (WP-C ruling 2).
///
/// The whole call is bounded by `deadline`. Any non-200, decode failure, refused spend or the deadline answers 503
/// `{"error":"UNAVAILABLE","detail":…}`; a bad parameter 400, an unconfigured repo 404, each `{"error":…}`.
public record PrFresh(GitHubHttp http,
                      RateBudget budget,
                      Fn2<Promise<Option<BaseTip>>, String, BranchName> tips,
                      List<KilnConfig.Repo> repos,
                      Supplier<Instant> clock,
                      Duration deadline) {
    public static final String PATH = "/api/v1/pr-fresh";
    public static final Duration DEADLINE = Duration.ofSeconds(30);
    /// R7 reads at most this many pages of 100 files (the controller judges a short read, G9).
    static final int FILE_PAGES = 30;
    static final int ALL_PAGES = Integer.MAX_VALUE;
    static final int UNAVAILABLE = 503;
    static final int NOT_FOUND = 404;
    static final int BAD_REQUEST = 400;

    public PrFresh {
        repos = List.copyOf(repos);
    }

    public static PrFresh prFresh(GitHubHttp http,
                                  RateBudget budget,
                                  Fn2<Promise<Option<BaseTip>>, String, BranchName> tips,
                                  List<KilnConfig.Repo> repos,
                                  Supplier<Instant> clock) {
        return new PrFresh(http, budget, tips, repos, clock, DEADLINE);
    }

    /// A repository the request names that kiln does not read.
    enum Refusal implements Cause {
        NOT_CONFIGURED("repo not configured");
        private final String message;
        Refusal(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    record Query(KilnConfig.Repo repo, PrNumber pr, Option<Sha> sha) {}

    record ErrorJson(String error) {}

    record UnavailableJson(String error, String detail) {}

    public Route route() {
        return Route.get(PATH, this::answer);
    }

    public Promise<ApiResponse> answer(ApiRequest request) {
        return query(request, repos).async()
                    .flatMap(this::bounded)
                    .fold(PrFresh::answered);
    }

    // parameters
    static Result<Query> query(ApiRequest request, List<KilnConfig.Repo> repos) {
        return Result.all(repo(request.param("repo"),
                               repos),
                          pr(request.param("pr")),
                          sha(request.param("sha")))
                     .map(Query::new);
    }

    private static Result<KilnConfig.Repo> repo(Option<String> raw, List<KilnConfig.Repo> repos) {
        return raw.toResult(ApiError.badRequest("repo", "required"))
                  .filter(ApiError.badRequest("repo", "must be owner/name"),
                          KilnConfigLoader.REPO.asMatchPredicate())
                  .flatMap(name -> configured(name, repos));
    }

    private static Result<KilnConfig.Repo> configured(String name, List<KilnConfig.Repo> repos) {
        return Option.from(repos.stream().filter(repo -> repo.name()
                                                             .equals(name)).findFirst()).toResult(Refusal.NOT_CONFIGURED);
    }

    private static Result<PrNumber> pr(Option<String> raw) {
        return raw.toResult(ApiError.badRequest("pr", "required"))
                  .flatMap(Number::parseLong)
                  .flatMap(PrNumber::prNumber)
                  .mapError(cause -> ApiError.badRequest("pr",
                                                         cause.message()));
    }

    /// Lower-cased first; anything but 40 hex is refused.
    private static Result<Option<Sha>> sha(Option<String> raw) {
        return raw.map(text -> Sha.sha(text.toLowerCase(Locale.ROOT))
                                  .map(Option::some)
                                  .mapError(cause -> ApiError.badRequest("sha",
                                                                         cause.message())))
                  .or(Result.success(Option.<Sha> none()));
    }

    // the call
    /// The deadline bounds every read and S1; a [CoreError.Timeout] is the deadline's 503. A failed call closes its
    /// [FreshReads] before the reply is completed, so the reads still chained behind the 503 issue no request and spend
    /// no [RateBudget] (one read in flight at the deadline completes, unseen).
    private Promise<Object> bounded(Query query) {
        var reads = FreshReads.freshReads(http, budget, clock);

        return fresh(reads, query).timeout(TimeSpan.fromDuration(deadline))
                    .withFailure(_ -> reads.close())
                    .mapError(PrFresh::deadlined);
    }

    private static Cause deadlined(Cause cause) {
        return switch (cause) {
            case CoreError.Timeout timeout -> FreshError.Deadline.FACTORY.apply(timeout);
            default -> cause;
        };
    }

    /// Condition: with `sha` the full read, without it R1 only.
    private Promise<Object> fresh(FreshReads reads, Query query) {
        var asOf = clock.get();

        return query.sha()
                    .map(sha -> gated(reads, query, sha, asOf))
                    .or(() -> pullOnly(reads, query, asOf));
    }

    private Promise<Object> pullOnly(FreshReads reads, Query query, Instant asOf) {
        return pull(reads, query).map(pull -> new FreshReply.PullOnlyJson(asOf.toString(),
                                                                          query.repo().name(),
                                                                          query.pr().value(),
                                                                          FreshReply.PullJson.pullJson(pull),
                                                                          reads.sent().get()));
    }

    private Promise<Object> gated(FreshReads reads, Query query, Sha sha, Instant asOf) {
        return gathered(reads, query, sha).map(read -> FreshReply.reply(asOf,
                                                                        query.repo().name(),
                                                                        query.pr(),
                                                                        sha,
                                                                        read,
                                                                        reads.sent().get()));
    }

    /// R1-R9 then S1, one after another.
    private Promise<FreshReply.Gathered> gathered(FreshReads reads, Query query, Sha sha) {
        return pull(reads, query).mapWith(pull -> listRead(reads,
                                                           query,
                                                           pull.base()),
                                          Pulled::new)
                   .mapWith(_ -> checkReads(reads, query, sha),
                            Checked::new)
                   .mapWith(_ -> fileRead(reads, query),
                            Filed::new)
                   .mapWith(filed -> listRead(reads,
                                              query,
                                              filed.base()),
                            Again::new)
                   .mapWith(again -> baseTip(query,
                                             again.base()),
                            Again::gathered);
    }

    record Pulled(PullRead pull, FreshReply.ListRead first) {}

    record Checked(Pulled pulled, FreshReply.CheckReads checks) {}

    record Filed(Checked checked, FreshReply.FileRead files) {
        BranchName base() {
            return checked.pulled()
                          .pull()
                          .base();
        }
    }

    record Again(Filed filed, FreshReply.ListRead again) {
        BranchName base() {
            return filed.base();
        }

        FreshReply.Gathered gathered(FreshReply.BaseTipJson tip) {
            var pulled = filed.checked().pulled();

            return new FreshReply.Gathered(pulled.pull(),
                                           pulled.first(),
                                           filed.checked().checks(),
                                           filed.files(),
                                           again,
                                           tip);
        }
    }

    // R1
    private Promise<PullRead> pull(FreshReads reads, Query query) {
        return reads.parsed(path(query,
                                 "pulls/" + query.pr().value()),
                            "pulls",
                            PullRead::decode);
    }

    // R2 + R3, R8 + R9
    private Promise<FreshReply.ListRead> listRead(FreshReads reads, Query query, BranchName base) {
        return reads.get(path(query,
                              "branches/" + GitHubPoller.segment(base)))
                    .flatMap(PrFresh::branch)
                    .mapWith(_ -> reads.parsed(path(query,
                                                    "rules/branches/" + GitHubPoller.segment(base)),
                                               "rulesets",
                                               Rulesets::decode),
                             (branch, rules) -> branch.list(rules,
                                                            query.repo().bases().contains(base)));
    }

    /// A decoded branch read and the ETag it was answered with (G10).
    record Branch(BranchRead read, Option<String> etag) {
        FreshReply.ListRead list(List<RequiredCheck> rules, boolean configured) {
            return new FreshReply.ListRead(RequiredChecks.resolve(success(read), success(rules), configured), etag);
        }
    }

    private static Promise<Branch> branch(GhAnswer answer) {
        return BranchRead.decode(answer.body())
                         .mapError(cause -> FreshError.Decode.FACTORY.apply("branches", cause))
                         .map(read -> new Branch(read,
                                                 answer.etag()))
                         .async();
    }

    // R4-R6
    private Promise<FreshReply.CheckReads> checkReads(FreshReads reads, Query query, Sha sha) {
        return reads.pages(path(query,
                                "commits/" + sha.value() + "/check-runs?filter=all&per_page=100"),
                           "check-runs",
                           Counted::checkRuns,
                           ALL_PAGES)
                    .mapWith(_ -> reads.pages(path(query,
                                                   "actions/runs?head_sha=" + sha.value() + "&per_page=100"),
                                              "workflow-runs",
                                              Counted::workflowRuns,
                                              ALL_PAGES),
                             RunPages::new)
                    .mapWith(_ -> reads.pages(path(query,
                                                   "commits/" + sha.value() + "/statuses?per_page=100"),
                                              "statuses",
                                              CommitStatus::decode,
                                              ALL_PAGES),
                             RunPages::checkReads);
    }

    record RunPages(List<Counted<CheckRun>> runs, List<Counted<WorkflowRun>> headRuns) {
        FreshReply.CheckReads checkReads(List<List<CommitStatus>> statuses) {
            return new FreshReply.CheckReads(runs, headRuns, GitHubPoller.joined(statuses));
        }
    }

    // R7
    private Promise<FreshReply.FileRead> fileRead(FreshReads reads, Query query) {
        return reads.pages(path(query,
                                "pulls/" + query.pr().value() + "/files?per_page=100"),
                           "files",
                           Counted::files,
                           FILE_PAGES)
                    .map(GitHubPoller::joined)
                    .map(FreshReply.FileRead::new);
    }

    // S1
    /// Condition: a configured base has its stored tip read; any other base is untracked (G7 `base-untracked`).
    private Promise<FreshReply.BaseTipJson> baseTip(Query query, BranchName base) {
        return query.repo()
                    .bases()
                    .contains(base)
               ? tips.apply(query.repo().name(),
                            base)
                     .map(FreshReply::tracked)
               : Promise.success(FreshReply.untracked());
    }

    private static String path(Query query, String relative) {
        return "/repos/" + query.repo()
                                .name() + "/" + relative;
    }

    // answers
    /// The boundary renders every failure: a refused parameter or repository is the client's answer, and a GitHub,
    /// budget, decode or deadline failure is the 503 the controller reads as G7. Any other failure (the store's S1
    /// read) stays a failure, which the router answers 500 and logs.
    private static Promise<ApiResponse> answered(Result<Object> result) {
        return result.fold(PrFresh::refused, body -> json(200, body));
    }

    static Promise<ApiResponse> refused(Cause cause) {
        return Option.from(cause.stream().filter(PrFresh::rendered).findFirst())
                     .map(PrFresh::rendering)
                     .or(cause::promise);
    }

    private static boolean rendered(Cause cause) {
        return cause instanceof Refusal || cause instanceof ApiError.BadRequest || cause instanceof FreshError;
    }

    private static Promise<ApiResponse> rendering(Cause cause) {
        return switch (cause) {
            case Refusal refusal -> json(NOT_FOUND, new ErrorJson(refusal.message()));
            case FreshError fresh -> json(UNAVAILABLE, new UnavailableJson("UNAVAILABLE", fresh.message()));
            default -> json(BAD_REQUEST, new ErrorJson(cause.message()));
        };
    }

    private static Promise<ApiResponse> json(int status, Object body) {
        return Views.json(body)
                    .map(text -> new ApiResponse(status, ApiResponse.JSON, text))
                    .async();
    }
}
