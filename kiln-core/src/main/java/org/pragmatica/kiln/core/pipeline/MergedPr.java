package org.pragmatica.kiln.core.pipeline;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.github.GitHubJson;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.bool;
import static org.pragmatica.kiln.core.github.GitHubJson.instant;
import static org.pragmatica.kiln.core.github.GitHubJson.list;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalList;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalText;
import static org.pragmatica.kiln.core.github.GitHubJson.text;
import static org.pragmatica.lang.Option.none;


/// One merged PR as the pipeline baseline reads it (#102), from one GraphQL `search` node: its commits in PR order (each
/// with its parent count, headline and committer date) and its timeline of merge-queue entries and removals
/// (`AddedToMergeQueueEvent`, `RemovedFromMergeQueueEvent` with `reason`) and force pushes (`HeadRefForcePushedEvent`).
/// The removal's `beforeCommit` is not read: on live data it is the merge queue's own squash commit (one parent, the
/// base), never a PR commit, so it says nothing about the PR's head. `truncated` says the PR has
/// more commits than the one page read ([#COMMITS]). `timelineTruncated` says its timeline has more events than the one
/// page read ([#TIMELINE]): its removals would be read short, so it is never measured ([PrMeasure#excluded]). A merged PR never changes,
/// so its node is stored verbatim and decoded again by [#one].
public record MergedPr(long number,
                       String title,
                       Instant mergedAt,
                       String headRef,
                       List<Commit> commits,
                       boolean truncated,
                       List<QueueEvent> queue,
                       boolean timelineTruncated) {
    /// The commits one node carries; GitHub's connection page limit.
    public static final int COMMITS = 250;
    /// The timeline events one node carries (`timelineItems(first: 100)`).
    public static final int TIMELINE = 100;

    public MergedPr {
        commits = List.copyOf(commits);
        queue = List.copyOf(queue);
    }

    public MergedPr(long number,
                    String title,
                    Instant mergedAt,
                    String headRef,
                    List<Commit> commits,
                    boolean truncated,
                    List<QueueEvent> queue) {
        this(number, title, mergedAt, headRef, commits, truncated, queue, false);
    }

    /// One commit of the PR: `parents` >= 2 is a merge. `committed` is the committer date, set when the commit object was
    /// made (a rebase or an amend sets it anew), so a commit cannot reach the PR before it.
    public record Commit(String oid, String headline, long parents, Instant committed) {
        public boolean merge() {
            return parents >= 2;
        }
    }

    /// One merge-queue event of the PR's timeline.
    public sealed interface QueueEvent {
        Instant at();

        record Added(Instant at) implements QueueEvent {}

        record Removed(Instant at, String reason) implements QueueEvent {}

        /// The head branch was force-pushed: its head was rewritten.
        record ForcePushed(Instant at) implements QueueEvent {}
    }

    /// A decoded node and its JSON text, which the store keeps.
    public record Listed(MergedPr pr, String node) {}

    /// One `search` page: the PRs, and the cursor of the next page when there is one.
    public record Page(List<Listed> prs, Option<String> after) {
        public Page {
            prs = List.copyOf(prs);
        }
    }

    /// One `search` answer. A 200 carrying `errors[]` fails as [PipelineError.GraphQl], so a refused query never reads
    /// as an empty list.
    public static Result<Page> page(String json) {
        return GitHubJson.tree(json)
                         .flatMap(MergedPr::refusedOrData)
                         .flatMap(MergedPr::searchPage);
    }

    /// One stored node.
    public static Result<MergedPr> one(String node) {
        return GitHubJson.tree(node).flatMap(MergedPr::mergedPr);
    }

    private static Result<JsonNode> refusedOrData(JsonNode root) {
        return optionalList(root, "errors", MergedPr::errorMessage).flatMap(errors -> refused(root, errors));
    }

    private static Result<JsonNode> refused(JsonNode root, List<String> errors) {
        return Option.from(errors.stream().findFirst())
                     .map(first -> PipelineError.GraphQl.FACTORY.apply(first).<JsonNode> result())
                     .or(Result.success(root));
    }

    private static Result<String> errorMessage(JsonNode node) {
        return optionalText(node, "message").map(message -> message.or("(no message)"));
    }

    private static Result<Page> searchPage(JsonNode root) {
        return Result.all(list(root, "data.search.nodes", MergedPr::listed),
                          bool(root, "data.search.pageInfo.hasNextPage"),
                          optionalText(root, "data.search.pageInfo.endCursor")).map((prs, more, cursor) -> new Page(prs,
                                                                                                                    more
                                                                                                                    ? cursor
                                                                                                                    : none()));
    }

    private static Result<Listed> listed(JsonNode node) {
        return mergedPr(node).map(pr -> new Listed(pr, node.toString()));
    }

    static Result<MergedPr> mergedPr(JsonNode node) {
        return Result.all(GitHubJson.number(node, "number"),
                          text(node, "title"),
                          instant(node, "mergedAt"),
                          text(node, "headRefName"),
                          list(node, "commits.nodes", MergedPr::commit),
                          GitHubJson.number(node, "commits.totalCount"),
                          list(node, "timelineItems.nodes", MergedPr::queueEvent))
                     .map((number, title, mergedAt, headRef, commits, total, events) -> new MergedPr(number,
                                                                                                     title,
                                                                                                     mergedAt,
                                                                                                     headRef,
                                                                                                     commits,
                                                                                                     total > commits.size(),
                                                                                                     queued(events),
                                                                                                     moreTimeline(node)));
    }

    /// `timelineItems.pageInfo.hasNextPage`; a node stored before the query asked for it reads as whole.
    private static boolean moreTimeline(JsonNode node) {
        var more = GitHubJson.at(node, "timelineItems.pageInfo.hasNextPage");

        return more.isBoolean() && more.booleanValue();
    }

    private static Result<Commit> commit(JsonNode node) {
        return Result.all(text(node, "commit.oid"),
                          text(node, "commit.messageHeadline"),
                          GitHubJson.number(node, "commit.parents.totalCount"),
                          instant(node, "commit.committedDate"))
                     .map(Commit::new);
    }

    /// A timeline item of another type reads as none; the query asks only for the queue events and force pushes.
    private static Result<Option<QueueEvent>> queueEvent(JsonNode node) {
        return text(node, "__typename").flatMap(type -> typed(node, type));
    }

    private static Result<Option<QueueEvent>> typed(JsonNode node, String type) {
        return switch (type) {
            case "AddedToMergeQueueEvent" -> instant(node, "createdAt").map(at -> Option.<QueueEvent> some(new QueueEvent.Added(at)));
            case "RemovedFromMergeQueueEvent" -> removed(node).map(Option::some);
            case "HeadRefForcePushedEvent" -> instant(node, "createdAt").map(at -> Option.<QueueEvent> some(new QueueEvent.ForcePushed(at)));
            default -> Result.success(Option.<QueueEvent> none());
        };
    }

    private static Result<QueueEvent> removed(JsonNode node) {
        return Result.all(instant(node, "createdAt"), text(node, "reason")).map(QueueEvent.Removed::new);
    }

    private static List<QueueEvent> queued(List<Option<QueueEvent>> events) {
        return events.stream()
                     .flatMap(Option::stream)
                     .toList();
    }
}
