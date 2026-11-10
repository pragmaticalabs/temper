package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.each;
import static org.pragmatica.kiln.core.github.GitHubJson.instant;
import static org.pragmatica.kiln.core.github.GitHubJson.number;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalText;
import static org.pragmatica.kiln.core.github.GitHubJson.text;


/// One commit status at a PR head (`GET /commits/{sha}/statuses`, spec §4.4): `id, context, state, description,
/// updated_at`.
public record CommitStatus(long id, String context, String state, Option<String> description, Instant updatedAt) {
    /// One page of statuses. Lenient: unknown fields are ignored.
    public static Result<List<CommitStatus>> decode(String json) {
        return GitHubJson.tree(json).flatMap(root -> each(root, GitHubJson.ROOT, CommitStatus::commitStatus));
    }

    private static Result<CommitStatus> commitStatus(JsonNode node) {
        return Result.all(number(node, "id"),
                          text(node, "context"),
                          text(node, "state"),
                          optionalText(node, "description"),
                          instant(node, "updated_at")).map(CommitStatus::new);
    }
}
