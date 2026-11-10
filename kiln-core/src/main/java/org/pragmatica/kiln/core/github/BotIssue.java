package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.at;
import static org.pragmatica.kiln.core.github.GitHubJson.each;
import static org.pragmatica.kiln.core.github.GitHubJson.instant;
import static org.pragmatica.kiln.core.github.GitHubJson.text;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// One open issue of `GET /issues?state=open&creator=app/github-actions` (spec §4.2): `number, title, html_url,
/// created_at`. GitHub lists pull requests as issues too, marked by a `pull_request` field; those are not issues and are
/// left out. The change feed (CI interface T1 WP2) reads `state=all&since=`, so it also keeps `state` and
/// `updated_at`, both optional so the open-issues read decodes as before.
public record BotIssue(long number,
                       String title,
                       String htmlUrl,
                       Instant createdAt,
                       Option<String> state,
                       Option<Instant> updatedAt) {
    /// `state` of a closed issue.
    public static final String CLOSED = "closed";

    /// An issue of the open-issues read: state and update time unknown.
    public BotIssue(long number, String title, String htmlUrl, Instant createdAt) {
        this(number, title, htmlUrl, createdAt, none(), none());
    }

    /// One page of issues. Lenient: unknown fields are ignored; an issue missing a field fails the page.
    public static Result<List<BotIssue>> decode(String json) {
        return GitHubJson.tree(json)
                         .flatMap(root -> each(root, GitHubJson.ROOT, BotIssue::item))
                         .map(BotIssue::flattened);
    }

    private static Result<List<BotIssue>> item(JsonNode node) {
        return at(node, "pull_request").isMissingNode()
               ? botIssue(node).map(BotIssue::single)
               : success(List.of());
    }

    private static Result<BotIssue> botIssue(JsonNode node) {
        return Result.all(GitHubJson.number(node, "number"),
                          text(node, "title"),
                          text(node, "html_url"),
                          instant(node, "created_at"),
                          GitHubJson.optionalText(node, "state"),
                          GitHubJson.optionalInstant(node, "updated_at"))
                     .map(BotIssue::new);
    }

    private static List<BotIssue> single(BotIssue issue) {
        return List.of(issue);
    }

    private static List<BotIssue> flattened(List<List<BotIssue>> items) {
        return items.stream()
                    .flatMap(List::stream)
                    .toList();
    }

    /// Whether GitHub reported the issue closed.
    public boolean closed() {
        return state.filter(CLOSED::equals)
                    .isPresent();
    }
}
