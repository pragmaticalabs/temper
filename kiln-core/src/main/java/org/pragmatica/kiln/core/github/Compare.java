package org.pragmatica.kiln.core.github;

import java.util.List;

import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.list;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalList;
import static org.pragmatica.kiln.core.github.GitHubJson.sha;
import static org.pragmatica.kiln.core.github.GitHubJson.text;


/// `GET /repos/{o}/{r}/compare/{old}...{new}` as the base move reads it: `status` (`ahead`,
/// `behind`, `diverged`, `identical`) and, per listed commit, its id, its tree (`commit.tree.sha`) and its first parent
/// (`parents[0].sha`, none for a root commit). GitHub lists at most 250 commits; the walk treats a commit it cannot
/// reach as incomplete, never as unchanged.
public record Compare(String status, List<Compare.Commit> commits) {
    /// The status of a compare whose new tip descends from the old one.
    public static final String AHEAD = "ahead";

    public Compare {
        commits = List.copyOf(commits);
    }

    /// One listed commit.
    public record Commit(Sha sha, Sha tree, Option<Sha> parent) {}

    public static Result<Compare> decode(String json) {
        return GitHubJson.tree(json).flatMap(Compare::compare);
    }

    private static Result<Compare> compare(JsonNode root) {
        return Result.all(text(root, "status"), list(root, "commits", Compare::commit)).map(Compare::new);
    }

    private static Result<Commit> commit(JsonNode node) {
        return Result.all(sha(node, "sha"),
                          sha(node, "commit.tree.sha"),
                          optionalList(node, "parents", parent -> sha(parent, "sha"))).map((sha, tree, parents) -> new Commit(sha,
                                                                                                                              tree,
                                                                                                                              Option.from(parents.stream()
                                                                                                                                                 .findFirst())));
    }
}
