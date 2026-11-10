package org.pragmatica.temper.controller.submit;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Resolves the submitted source (spec §2.2): a ref through `ls-remote`, a bare SHA through a mirror fetch plus
/// reachability. A SHA is reachable when a branch head contains it or, only when none does, a same-repo pull-request
/// head (`refs/pull/<N>/head` judged by [PullOrigins], m1-design M1-3, R-M2-7). A ref outside `refs/heads` and
/// `refs/tags` (`refs/pull/7/head` given in full) resolves through `ls-remote` and must then pass the same
/// reachability, so a fork PR can never enter by naming its ref. A miss is `Refusal(source)`, a SHA only on fork PR
/// heads `Refusal(fork-pr)`, an origin that cannot be established `Refusal(pr-origin-unverified)` (fail closed,
/// [PullJudgement]);
/// `bundle` and `patch` are `Refusal(unsupported)` in M0. A rerun's
/// inherited SHA (m2-design §5.2) is the parent's resolved one, taken as is [G]: no ref, so never superseded.
@FunctionalInterface
public interface SourceResolver {
    Promise<PushedSource> resolve(SourceRequest request);

    static SourceResolver sourceResolver(GitRemote remote, Mirror mirror, PullOrigins pulls) {
        return request -> resolve(remote, mirror, pulls, request);
    }

    private static Promise<PushedSource> resolve(GitRemote remote,
                                                 Mirror mirror,
                                                 PullOrigins pulls,
                                                 SourceRequest request) {
        return switch (request) {
            case SourceRequest.ByRef byRef -> remote.lsRemote(byRef.ref()).flatMap(sha -> byRef(mirror,
                                                                                                pulls,
                                                                                                byRef.ref(),
                                                                                                sha));
            case SourceRequest.BySha bySha -> reachable(mirror, pulls, bySha.sha()).map(sha -> PushedSource.pushedSource(none(),
                                                                                                                         sha));
            case SourceRequest.Unsupported unsupported -> Refusal.refusal(RefusalCode.UNSUPPORTED,
                                                                          "source " + unsupported.kind() + " is not in M0").promise();
            case SourceRequest.Inherited inherited -> inheritedSha(inherited);
        };
    }

    /// A parent without a resolved SHA was refused before resolution, so its rerun is refused by the preconditions first
    /// (`REFUSED` is not `FAILED(tests)`); this refusal is the backstop should a caller skip them.
    private static Promise<PushedSource> inheritedSha(SourceRequest.Inherited inherited) {
        return inherited.sha()
                        .map(sha -> PushedSource.pushedSource(none(),
                                                              sha))
                        .async(Refusal.refusal(RefusalCode.RERUN,
                                               "job " + inherited.parent() + " has no resolved sha"));
    }

    private static Promise<PushedSource> byRef(Mirror mirror, PullOrigins pulls, Ref ref, Sha sha) {
        return branchOrTag(ref)
               ? Promise.success(PushedSource.pushedSource(some(ref), sha))
               : reachable(mirror, pulls, sha).map(_ -> PushedSource.pushedSource(some(ref), sha));
    }

    /// A short name resolves only to `refs/heads/R` or `refs/tags/R` (`LsRemote`).
    private static boolean branchOrTag(Ref ref) {
        var name = ref.value();

        return ! name.startsWith("refs/") || name.startsWith("refs/heads/") || name.startsWith("refs/tags/");
    }

    /// The API path is taken only when no branch head contains `sha`, the rare case.
    private static Promise<Sha> reachable(Mirror mirror, PullOrigins pulls, Sha sha) {
        return mirror.fetch()
                     .flatMap(_ -> mirror.containedInBranch(sha))
                     .flatMap(inBranch -> branchOrPullHead(mirror, pulls, sha, inBranch));
    }

    private static Promise<Sha> branchOrPullHead(Mirror mirror, PullOrigins pulls, Sha sha, boolean inBranch) {
        return inBranch
               ? Promise.success(sha)
               : mirror.pullHeadsContaining(sha)
                       .flatMap(heads -> sameRepoPullHead(pulls, sha, heads));
    }

    private static Promise<Sha> sameRepoPullHead(PullOrigins pulls, Sha sha, List<PullNumber> heads) {
        return heads.isEmpty()
               ? Refusal.refusal(RefusalCode.SOURCE,
                                 "sha " + sha + " is not contained in any pushed branch or pull-request head").promise()
               : PullJudgement.pullJudgement(pulls, heads).flatMap(judgement -> judgement.verdict(sha)
                                                                                         .async());
    }
}
