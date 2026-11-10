package org.pragmatica.temper.cli.merge;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.lang.Option.option;


/// G1: `git ls-remote <url> refs/pull/<PR>/head refs/heads/<headRef> refs/heads/<base>` against
/// the explicit URL. The branch differing from SHA (or absent) is `head-moved`; otherwise `refs/pull` differing is
/// `head-ref-lag`. The base value goes on to G8. ls-remote patterns match ref tails, so the answer is read by exact
/// ref name.
sealed interface HeadCheck {
    /// What ls-remote showed for the three refs; absent when the remote has no such ref.
    record Remote(Option<String> pull, Option<String> branch, Option<String> base) {}

    static Promise<Remote> lsRemote(Git git, Budget budget, String url, long pr, String headRef, String base) {
        return git.output(budget,
                          "ls-remote",
                          List.of("ls-remote",
                                  url,
                                  pullRef(pr),
                                  branchRef(headRef),
                                  branchRef(base)))
                  .map(text -> remote(refs(text),
                                      pr,
                                      headRef,
                                      base));
    }

    static String pullRef(long pr) {
        return "refs/pull/" + pr + "/head";
    }

    static String branchRef(String name) {
        return "refs/heads/" + name;
    }

    private static Map<String, String> refs(String text) {
        return text.lines()
                   .map(line -> line.split("\t", 2))
                   .filter(parts -> parts.length == 2)
                   .collect(Collectors.toMap(parts -> parts[1],
                                             parts -> parts[0],
                                             (first, _) -> first));
    }

    private static Remote remote(Map<String, String> refs, long pr, String headRef, String base) {
        return new Remote(option(refs.get(pullRef(pr))),
                          option(refs.get(branchRef(headRef))),
                          option(refs.get(branchRef(base))));
    }

    /// At most one reason: the branch is checked first, the pull ref only when the branch is at SHA.
    static List<Reason> judged(Remote remote, String sha, String headRef) {
        return remote.branch()
                     .filter(sha::equals)
                     .isPresent()
               ? pullJudged(remote, sha)
               : List.of(Reason.reason(Reason.Gate.G1,
                                       "head-moved " + branchRef(headRef) + " " + remote.branch().or("missing")));
    }

    private static List<Reason> pullJudged(Remote remote, String sha) {
        return remote.pull()
                     .filter(sha::equals)
                     .isPresent()
               ? List.of()
               : List.of(Reason.reason(Reason.Gate.G1, "head-ref-lag"));
    }

    record unused() implements HeadCheck {}
}
