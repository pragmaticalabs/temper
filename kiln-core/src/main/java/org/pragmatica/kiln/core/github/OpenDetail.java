package org.pragmatica.kiln.core.github;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.at;
import static org.pragmatica.kiln.core.github.GitHubJson.bool;
import static org.pragmatica.kiln.core.github.GitHubJson.instant;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalText;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// What kiln reads per open PR only (spec §4.1): `mergeable` and `mergeable_state` from `pulls/{n}` (shown, never a
/// gate; GitHub may not have computed them yet) and the head commit's date.
public record OpenDetail(Option<Boolean> mergeable, Option<String> mergeableState, Option<Instant> headCommittedAt) {
    /// `mergeable` and `mergeable_state` of one `GET /pulls/{n}`, with the head commit's date read separately. Both are
    /// absent or `null` while GitHub computes them; a value of the wrong type fails the read.
    public static Result<OpenDetail> decode(String pullJson, Option<Instant> headCommittedAt) {
        return GitHubJson.tree(pullJson).flatMap(root -> openDetail(root, headCommittedAt));
    }

    /// The committer date of one `GET /commits/{sha}`: `commit.committer.date`.
    public static Result<Instant> committedAt(String commitJson) {
        return GitHubJson.tree(commitJson).flatMap(root -> instant(root, "commit.committer.date"));
    }

    private static Result<OpenDetail> openDetail(JsonNode root, Option<Instant> headCommittedAt) {
        return Result.all(mergeable(root), optionalText(root, "mergeable_state")).map((mergeable, state) -> new OpenDetail(mergeable,
                                                                                                                           state,
                                                                                                                           headCommittedAt));
    }

    private static Result<Option<Boolean>> mergeable(JsonNode root) {
        var node = at(root, "mergeable");

        return node.isMissingNode() || node.isNull()
               ? success(none())
               : bool(root, "mergeable").map(Option::some);
    }
}
