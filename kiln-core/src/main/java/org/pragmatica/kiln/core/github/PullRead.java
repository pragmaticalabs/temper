package org.pragmatica.kiln.core.github;

import java.time.Instant;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.number;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalInstant;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalText;
import static org.pragmatica.kiln.core.github.GitHubJson.sha;
import static org.pragmatica.kiln.core.github.GitHubJson.text;


/// One `GET /repos/{o}/{r}/pulls/{n}` as the merge gate reads it: `state`, `merged_at`,
/// `head.sha`, `head.ref`, `head.repo.full_name` (none for a deleted fork), `base.ref` and `changed_files`; and
/// `merged_by.login`, none when absent. `state` reads `merged` when `merged_at` is set, as GitHub's own
/// `state` is `closed` for a merged PR.
public record PullRead(String state,
                       Option<Instant> mergedAt,
                       Sha head,
                       String headRef,
                       Option<String> headRepo,
                       BranchName base,
                       long changedFiles,
                       Option<String> mergedBy) {
    public static final String OPEN = "open";
    public static final String MERGED = "merged";

    public static Result<PullRead> decode(String json) {
        return GitHubJson.tree(json).flatMap(PullRead::pullRead);
    }

    /// Only `merged_by.login`: the poller's read of a PR it just saw merge needs nothing else.
    public static Result<Option<String>> mergedBy(String json) {
        return GitHubJson.tree(json).flatMap(root -> optionalText(root, "merged_by.login"));
    }

    private static Result<PullRead> pullRead(JsonNode root) {
        return Result.all(text(root, "state"),
                          optionalInstant(root, "merged_at"),
                          sha(root, "head.sha"),
                          text(root, "head.ref"),
                          optionalText(root, "head.repo.full_name"),
                          text(root, "base.ref").flatMap(BranchName::branchName),
                          number(root, "changed_files"),
                          optionalText(root, "merged_by.login"))
                     .map((state, mergedAt, head, headRef, headRepo, base, changed, mergedBy) -> new PullRead(merged(state,
                                                                                                                     mergedAt),
                                                                                                              mergedAt,
                                                                                                              head,
                                                                                                              headRef,
                                                                                                              headRepo,
                                                                                                              base,
                                                                                                              changed,
                                                                                                              mergedBy));
    }

    private static String merged(String state, Option<Instant> mergedAt) {
        return mergedAt.map(_ -> MERGED)
                       .or(state);
    }

    public boolean open() {
        return OPEN.equals(state) && mergedAt.isEmpty();
    }
}
