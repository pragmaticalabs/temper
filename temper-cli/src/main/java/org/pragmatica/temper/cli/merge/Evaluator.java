package org.pragmatica.temper.cli.merge;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The gate's reads for one (repo, PR, SHA): `GET fleet/pr-fresh`, then on the Mac G1, G8 and
/// G6-Mac. The reasons are the server's and the Mac's together; `merge-gate` creates a GO when there are none, the
/// queue view prints its advice from them. A G7 reason or a failure from temper stops the reads (nothing the Mac could
/// add would make a GO).
///
/// Every failed Mac step becomes its own fail-closed reason (`source-unavailable git <step>`); a spent budget stops
/// everything with `source-unavailable budget-exceeded`. Recovery: FER, degrade forward to a NO — a failed check can
/// only ever withhold a GO, never grant one.
record Evaluator(MergeApi api, Git git, MergePorts ports, Path home) {
    static final String ACCEPT = "ACCEPT";
    /// The reason word of a D-6 skew on a read (lead guess 5): a READS route is never refused with 412.
    static final String VERSION_SKEW_ON_READ = "version-skew";
    static final String BAD_PAYLOAD = "bad-payload";
    static final String UNKNOWN = "unknown";

    /// What the reads found: the pr-fresh reply (absent when temper did not answer), the base tip G8 fetched and judged
    /// (absent when G8 did not complete), the reasons in production order, and the exit a NO has (77 or 13 for a
    /// refused token or another controller build, else 1).
    record Evaluation(Option<MergeWire.PrFresh> fresh, Option<String> baseTip, List<Reason> reasons, ExitCode exit) {
        static Evaluation evaluation(Option<MergeWire.PrFresh> fresh,
                                     Option<String> baseTip,
                                     List<Reason> reasons,
                                     ExitCode exit) {
            return new Evaluation(fresh, baseTip, reasons, exit);
        }
    }

    /// G1's reasons and what ls-remote showed (absent when ls-remote failed).
    record Head(List<Reason> reasons, Option<HeadCheck.Remote> remote) {}

    /// The Mac's reasons and the judged base tip.
    record Mac(List<Reason> reasons, Option<String> baseTip) {
        Mac with(List<Reason> more) {
            return new Mac(Reason.joined(reasons, more), baseTip);
        }
    }

    static Evaluator evaluator(MergeApi api, Git git, MergePorts ports, Path home) {
        return new Evaluator(api, git, ports, home);
    }

    Promise<Evaluation> evaluated(String repo, long pr, String sha, Budget budget) {
        return budget.bound()
                     .async()
                     .flatMap(bound -> api.prFresh(repo,
                                                   pr,
                                                   some(sha),
                                                   bound))
                     .fold(result -> answered(result, repo, pr, sha, budget));
    }

    private Promise<Evaluation> answered(Result<MergeWire.PrFresh> result,
                                         String repo,
                                         long pr,
                                         String sha,
                                         Budget budget) {
        return result.fold(cause -> Promise.success(unavailable(cause, budget, VERSION_SKEW_ON_READ)),
                           fresh -> checked(fresh, repo, pr, sha, budget));
    }

    /// temper did not answer as its contract says: one `source-unavailable temper <status | unreachable>` reason.
    static Evaluation unavailable(Cause cause, Budget budget, String skewWord) {
        return Evaluation.evaluation(none(), none(), List.of(temperReason(cause, budget, skewWord)), exitOf(cause));
    }

    static Reason temperReason(Cause cause, Budget budget, String skewWord) {
        return budget.isSpent() || cause == GateFault.General.BUDGET_SPENT
               ? Reason.budgetExceeded()
               : Reason.reason(Reason.Gate.G7, "source-unavailable temper " + temperWord(cause, skewWord));
    }

    private static String temperWord(Cause cause, String skewWord) {
        return switch (cause) {
            case CliError.General.UNAUTHORIZED -> "401";
            case CliError.General.FORBIDDEN -> "403";
            case CliError.VersionSkew _ -> skewWord;
            case GateFault.Answered answered -> Integer.toString(answered.status());
            case CliError.BadPayload _ -> BAD_PAYLOAD;
            default -> "unreachable";
        };
    }

    static Reason badPayload() {
        return Reason.reason(Reason.Gate.G7, "source-unavailable temper " + BAD_PAYLOAD);
    }

    /// 77 for a refused token, 13 for another controller build, 1 (a NO) for everything else.
    static ExitCode exitOf(Cause cause) {
        return switch (cause) {
            case CliError.General.UNAUTHORIZED, CliError.General.FORBIDDEN -> ExitCode.UNAUTHORIZED;
            case CliError.VersionSkew _ -> ExitCode.VERSION_SKEW;
            default -> ExitCode.FAILED;
        };
    }

    private Promise<Evaluation> checked(MergeWire.PrFresh fresh, String repo, long pr, String sha, Budget budget) {
        var server = fresh.reasons().stream().map(Reason::fromServer).toList();

        return server.stream()
                     .anyMatch(reason -> reason.check() == Reason.Gate.G7)
               ? Promise.success(Evaluation.evaluation(some(fresh), none(), server, ExitCode.FAILED))
               : fresh.pull()
                      .map(pull -> macChecked(fresh, pull, server, repo, pr, sha, budget))
                      .or(() -> Promise.success(Evaluation.evaluation(some(fresh),
                                                                      none(),
                                                                      Reason.joined(server,
                                                                                    List.of(badPayload())),
                                                                      ExitCode.FAILED)));
    }

    private Promise<Evaluation> macChecked(MergeWire.PrFresh fresh,
                                           MergeWire.Pull pull,
                                           List<Reason> server,
                                           String repo,
                                           long pr,
                                           String sha,
                                           Budget budget) {
        return mac(fresh, pull, repo, pr, sha, budget).map(mac -> Evaluation.evaluation(some(fresh),
                                                                                        mac.baseTip(),
                                                                                        Reason.joined(server,
                                                                                                      mac.reasons()),
                                                                                        ExitCode.FAILED))
                  .fold(result -> Promise.success(result.fold(cause -> stopped(fresh, server, cause, budget),
                                                              evaluation -> evaluation)));
    }

    /// The Mac checks stopped (only a spent budget reaches here; any other failure is named by its step).
    private static Evaluation stopped(MergeWire.PrFresh fresh, List<Reason> server, Cause cause, Budget budget) {
        return Evaluation.evaluation(some(fresh),
                                     none(),
                                     Reason.joined(server, List.of(stopReason(cause, budget))),
                                     ExitCode.FAILED);
    }

    private static Reason stopReason(Cause cause, Budget budget) {
        return budget.isSpent() || cause == GateFault.General.BUDGET_SPENT
               ? Reason.budgetExceeded()
               : Reason.gitUnavailable(GateFault.stepOf(cause));
    }

    private Promise<Mac> mac(MergeWire.PrFresh fresh,
                             MergeWire.Pull pull,
                             String repo,
                             long pr,
                             String sha,
                             Budget budget) {
        return head(pull, repo, pr, sha, budget).flatMap(head -> based(head, fresh, pull, repo, pr, sha, budget))
                   .map(mac -> mac.with(BaseCheck.untracked(fresh.baseTip())))
                   .map(mac -> mac.with(reportReasons(fresh, repo, pr, sha)));
    }

    private Promise<Head> head(MergeWire.Pull pull, String repo, long pr, String sha, Budget budget) {
        return HeadCheck.lsRemote(git,
                                  budget,
                                  ports.url(repo),
                                  pr,
                                  pull.headRef(),
                                  pull.base())
                        .map(remote -> new Head(HeadCheck.judged(remote,
                                                                 sha,
                                                                 pull.headRef()),
                                                some(remote)))
                        .fold(result -> recovered(result,
                                                  budget,
                                                  reason -> new Head(List.of(reason),
                                                                     none())));
    }

    /// G8 runs only on a head G1 found at SHA: with the head elsewhere SHA may not even be fetchable, and the NO is
    /// already certain (lead guess, sent 11-03).
    private Promise<Mac> based(Head head,
                               MergeWire.PrFresh fresh,
                               MergeWire.Pull pull,
                               String repo,
                               long pr,
                               String sha,
                               Budget budget) {
        return head.remote()
                   .filter(_ -> head.reasons()
                                    .isEmpty())
                   .map(remote -> g8(remote, fresh, pull, repo, pr, sha, budget))
                   .or(() -> Promise.success(new Mac(head.reasons(),
                                                     none())));
    }

    private Promise<Mac> g8(HeadCheck.Remote remote,
                            MergeWire.PrFresh fresh,
                            MergeWire.Pull pull,
                            String repo,
                            long pr,
                            String sha,
                            Budget budget) {
        var cache = BaseCheck.cache(home, repo);

        return BaseCheck.fetched(git,
                                 budget,
                                 cache,
                                 ports.url(repo),
                                 pull.base(),
                                 pr,
                                 BaseCheck.runId(ports.nanos().getAsLong()))
                        .flatMap(fetched -> fetchJudged(remote,
                                                        fetched,
                                                        fresh,
                                                        cache,
                                                        pr,
                                                        sha,
                                                        pull.base(),
                                                        budget))
                        .fold(result -> recovered(result,
                                                  budget,
                                                  reason -> new Mac(List.of(reason),
                                                                    none())));
    }

    private Promise<Mac> fetchJudged(HeadCheck.Remote remote,
                                     BaseCheck.Fetched fetched,
                                     MergeWire.PrFresh fresh,
                                     Path cache,
                                     long pr,
                                     String sha,
                                     String base,
                                     Budget budget) {
        var mismatches = BaseCheck.mismatches(remote, fetched, sha, pr, base);

        return mismatches.isEmpty()
               ? analysed(fetched, fresh, cache, sha, budget)
               : Promise.success(new Mac(mismatches, none()));
    }

    private Promise<Mac> analysed(BaseCheck.Fetched fetched,
                                  MergeWire.PrFresh fresh,
                                  Path cache,
                                  String sha,
                                  Budget budget) {
        return Promise.all(BaseCheck.analysed(git,
                                              budget,
                                              cache,
                                              sha,
                                              fetched.base()),
                           fresh.baseTip()
                                .map(tip -> BaseCheck.rewound(git,
                                                              budget,
                                                              cache,
                                                              tip,
                                                              fetched.base()))
                                .or(() -> Promise.success(List.of())))
                      .map((moved, rewound) -> new Mac(Reason.joined(moved, rewound),
                                                       some(fetched.base())));
    }

    /// G6-Mac runs for an ACCEPT only; the server already named every other verdict.
    private static List<Reason> reportReasons(MergeWire.PrFresh fresh, String repo, long pr, String sha) {
        return fresh.verdict()
                    .filter(verdict -> ACCEPT.equals(verdict.verdict()))
                    .map(verdict -> ReportCheck.judged(verdict, repo, pr, sha))
                    .or(List.of());
    }

    /// FER: a failed git step is its fail-closed reason; a spent budget stops the reads.
    private static <T> Promise<T> recovered(Result<T> result, Budget budget, Fn1<T, Reason> onGitFailure) {
        return result.fold(cause -> gitRecovered(cause, budget, onGitFailure), Promise::success);
    }

    private static <T> Promise<T> gitRecovered(Cause cause, Budget budget, Fn1<T, Reason> onGitFailure) {
        return budget.isSpent() || cause == GateFault.General.BUDGET_SPENT
               ? GateFault.General.BUDGET_SPENT.promise()
               : Promise.success(onGitFailure.apply(Reason.gitUnavailable(GateFault.stepOf(cause))));
    }

    /// `git rev-list --count <head>..<tip>` in the cache (the queue view's `behind_by`); `unknown` when G8 did not
    /// judge a tip or git failed (FER: the field is advice).
    Promise<String> behind(String repo, String head, Option<String> tip, Budget budget) {
        return tip.map(base -> git.output(budget,
                                          "rev-list",
                                          BaseCheck.cache(home, repo),
                                          List.of("rev-list", "--count", head + ".." + base))
                                  .map(String::strip)
                                  .fold(result -> Promise.success(result.or(UNKNOWN))))
                  .or(() -> Promise.success(UNKNOWN));
    }
}
