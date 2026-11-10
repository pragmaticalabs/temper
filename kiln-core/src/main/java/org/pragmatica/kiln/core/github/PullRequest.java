package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;


/// One PR as stored per spec §4.1; the table keeps every PR forever (K-13). `headRepo` is the head repository's full
/// name; GitHub sends none when the fork was deleted.
public record PullRequest(PrNumber number,
                          String title,
                          PrState state,
                          boolean draft,
                          BranchName base,
                          BranchName headRef,
                          Option<String> headRepo,
                          Sha head,
                          Option<Instant> mergedAt,
                          Option<Instant> closedAt,
                          Instant updatedAt,
                          List<String> labels,
                          List<PrNumber> closes,
                          List<PrNumber> partOf) {
    public PullRequest {
        labels = List.copyOf(labels);
        closes = List.copyOf(closes);
        partOf = List.copyOf(partOf);
    }

    /// Whether the head lives in `baseRepo` itself, not a fork (spec §5.3: fork PRs never link a checkout). A PR whose
    /// head repository is gone is not the base repository's. Owner and name compare ignoring case, as GitHub resolves
    /// them: a configured `repo` cased differently from GitHub's answer still names the same repository.
    public boolean sameRepo(String baseRepo) {
        return headRepo.map(baseRepo::equalsIgnoreCase)
                       .or(false);
    }
}
