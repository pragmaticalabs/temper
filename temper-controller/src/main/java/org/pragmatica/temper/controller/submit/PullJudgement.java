package org.pragmatica.temper.controller.submit;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;


/// The judgement of the pull-request heads containing one SHA (R-M2-7), in the mirror's candidate order (oldest first:
/// fork PRs are usually newer; an older fork can be force-pushed, so neither a fork answer nor a per-PR failure stops
/// the walk). Origins already known cost nothing and are read first. The others are asked one at a time:
///
/// - a same-repo PR accepts and ends the walk;
/// - a fork, or a failure about that PR only ([#perPull]: 404, 410, 422, an unreadable, incomplete or oversized
///   answer), is recorded and the walk goes on;
/// - any other failure (the rate limit 403/429, a 5xx, no answer, a non-github origin) is recorded and ends the walk,
///   to save the API budget; the PRs not reached are unchecked.
///
/// Verdict: a same-repo PR → accepted; else any failure → `pr-origin-unverified` (fail closed; unchecked PRs exist
/// only after a failure); else every candidate was judged a fork → `fork-pr`.
public record PullJudgement(boolean sameRepo,
                            boolean stopped,
                            List<PullNumber> forks,
                            List<String> unverified,
                            List<PullNumber> unchecked) {
    /// Statuses that are about the PR asked, not about the API: gone, hidden, or not a pull request.
    private static final Set<Integer> PER_PULL_STATUSES = Set.of(404, 410, 422);

    public static Promise<PullJudgement> pullJudgement(PullOrigins pulls, List<PullNumber> candidates) {
        return known(pulls, candidates).asked(pulls,
                                              candidates.stream().filter(pull -> pulls.known(pull)
                                                                                      .isEmpty()).toList());
    }

    /// Whether `cause` concerns only the PR asked, so the walk goes on to the next candidate.
    static boolean perPull(Cause cause) {
        return switch (cause) {
            case PullOriginError.Status status -> PER_PULL_STATUSES.contains(status.status());
            case PullOriginError.Unparseable _ -> true;
            case PullOriginError.General general -> general != PullOriginError.General.NOT_GITHUB;
            default -> false;
        };
    }

    private static PullJudgement known(PullOrigins pulls, List<PullNumber> candidates) {
        var known = candidates.stream()
                              .flatMap(pull -> pulls.known(pull)
                                                    .map(origin -> new Known(pull, origin))
                                                    .stream())
                              .toList();

        return new PullJudgement(known.stream().anyMatch(Known::sameRepo),
                                 false,
                                 known.stream().filter(Predicate.not(Known::sameRepo)).map(Known::pull).toList(),
                                 List.of(),
                                 List.of());
    }

    private record Known(PullNumber pull, PullOrigin origin) {
        boolean sameRepo() {
            return origin == PullOrigin.SAME_REPO;
        }
    }

    private PullJudgement judged(PullNumber pull, PullOrigin origin) {
        return origin == PullOrigin.SAME_REPO
               ? new PullJudgement(true, stopped, forks, unverified, unchecked)
               : new PullJudgement(sameRepo, stopped, append(forks, pull), unverified, unchecked);
    }

    /// A failure is not dropped: it is recorded as unverified, which refuses the submit unless a same-repo PR is
    /// found; only a failure that is not [#perPull] ends the walk.
    private PullJudgement judged(PullNumber pull, Result<PullOrigin> origin) {
        return origin.fold(cause -> new PullJudgement(sameRepo,
                                                      !perPull(cause),
                                                      forks,
                                                      append(unverified, "pull request " + pull + ": " + cause.message()),
                                                      unchecked),
                           value -> judged(pull, value));
    }

    private Promise<PullJudgement> asked(PullOrigins pulls, List<PullNumber> rest) {
        return sameRepo || rest.isEmpty()
               ? Promise.success(this)
               : unlessStopped(pulls, rest);
    }

    private Promise<PullJudgement> unlessStopped(PullOrigins pulls, List<PullNumber> rest) {
        return stopped
               ? Promise.success(new PullJudgement(sameRepo, stopped, forks, unverified, rest))
               : next(pulls, rest);
    }

    private Promise<PullJudgement> next(PullOrigins pulls, List<PullNumber> rest) {
        var pull = rest.getFirst();

        return pulls.origin(pull)
                    .fold(origin -> judged(pull, origin).asked(pulls,
                                                               rest.subList(1,
                                                                            rest.size())));
    }

    /// The submit's answer for `sha`.
    public Result<Sha> verdict(Sha sha) {
        return sameRepo
               ? Result.success(sha)
               : refusal(sha).result();
    }

    private Refusal refusal(Sha sha) {
        return unverified.isEmpty()
               ? Refusal.refusal(RefusalCode.FORK_PR,
                                 "sha " + sha
                                + " is contained only in fork pull-request heads " + numbers(forks)
                                + " (spec §13)")
               : Refusal.refusal(RefusalCode.PR_ORIGIN_UNVERIFIED, "sha " + sha + ": " + unverifiedDetail());
    }

    private String unverifiedDetail() {
        return Stream.concat(unverified.stream(),
                             Option.some(unchecked)
                                   .filter(rest -> !rest.isEmpty())
                                   .map(rest -> rest.size()
                                               + " candidate pull request(s) not checked (" + numbers(rest)
                                               + ")")
                                   .stream())
                     .collect(Collectors.joining("; "));
    }

    private static <T> List<T> append(List<T> list, T item) {
        return Stream.concat(list.stream(),
                             Stream.of(item))
                     .toList();
    }

    private static String numbers(List<PullNumber> pulls) {
        return pulls.stream()
                    .map(PullNumber::toString)
                    .collect(Collectors.joining(", "));
    }
}
