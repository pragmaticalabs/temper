package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.badField;
import static org.pragmatica.kiln.core.github.GitHubJson.bool;
import static org.pragmatica.kiln.core.github.GitHubJson.each;
import static org.pragmatica.kiln.core.github.GitHubJson.instant;
import static org.pragmatica.kiln.core.github.GitHubJson.list;
import static org.pragmatica.kiln.core.github.GitHubJson.number;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalInstant;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalText;
import static org.pragmatica.kiln.core.github.GitHubJson.text;
import static org.pragmatica.lang.Result.success;


/// Decodes one page of `GET /repos/{o}/{r}/pulls` (spec §4.1). Lenient: unknown fields are ignored. A PR whose required
/// field is missing fails the page, naming the field: a page is never read as shorter than GitHub sent it. A PR whose
/// base or head ref is no [BranchName] (kiln is stricter than git: `+main`, `HEAD`) is not a page failure: it is left
/// out of [Page#pulls] and listed in [Page#skipped] with its number and the reason (lead ruling 7, 2026-10-30), so the
/// page is still never silently shorter. A non-array or broken body fails the page. Only a refused ref skips: every
/// structural failure stays page-fatal, since a schema change hits every PR, and skipping would empty the list and read
/// as 0 open PRs (which the spec forbids), while a failed page keeps the old rows with their age. `repo` is the
/// configured `owner/name` the bodies' closing references are read against ([ClosingRefs#parse]).
public sealed interface PullsPage {
    Pattern BLANKS = Pattern.compile("(?U)[\\s\\p{Cc}]+");

    /// The decoded PRs, and the PRs left out because a ref is no [BranchName], both in page order.
    record Page(List<PullRequest> pulls, List<Skipped> skipped) {
        public Page {
            pulls = List.copyOf(pulls);
            skipped = List.copyOf(skipped);
        }

        static Page page(List<Page> pages) {
            return new Page(pages.stream().flatMap(page -> page.pulls()
                                                               .stream()).toList(),
                            pages.stream().flatMap(page -> page.skipped()
                                                               .stream()).toList());
        }
    }

    /// A PR left out of the page: its number and why, on one line (`skipped #N: <reason>` in the github source state),
    /// and its `updated_at` when that parses, so an incremental poll can advance past a page whose PRs are all skipped.
    /// Its other fields are not read (lead ruling: a skipped PR is listed by number and reason only).
    record Skipped(PrNumber number, String reason, Option<Instant> updatedAt) {
        public static Skipped skipped(PrNumber number, String reason, Option<Instant> updatedAt) {
            return new Skipped(number, reason, updatedAt);
        }
    }

    static Result<Page> decode(String json, String repo) {
        return GitHubJson.tree(json)
                         .flatMap(root -> each(root,
                                               GitHubJson.ROOT,
                                               node -> item(node, repo)))
                         .map(Page::page);
    }

    /// The number and both refs must be present for any PR; only a ref that is no [BranchName] skips it.
    private static Result<Page> item(JsonNode node, String repo) {
        return Result.all(number(node, "number").flatMap(PrNumber::prNumber),
                          text(node, "base.ref"),
                          text(node, "head.ref"))
                     .flatMap((number, base, head) -> pulledOrSkipped(node, repo, number, base, head));
    }

    /// FER: a ref kiln refuses degrades the PR forward to a visible [Skipped] entry, never a failed page; the refusals'
    /// messages are its reason. Every other bad field still fails the page.
    private static Result<Page> pulledOrSkipped(JsonNode node, String repo, PrNumber number, String base, String head) {
        var refusals = Stream.of(BranchName.branchName(base),
                                 BranchName.branchName(head))
                             .flatMap(PullsPage::refusal)
                             .toList();

        return refusals.isEmpty()
               ? pullRequest(node, repo).map(PullsPage::pulled)
               : success(skippedPage(number, refusals, updatedAt(node)));
    }

    /// Design-out: a skipped PR's `updated_at` only lets the poll advance; one that does not parse is absent, and the
    /// poll then keeps its mark, never failing the page for a PR it does not store.
    private static Option<Instant> updatedAt(JsonNode node) {
        return instant(node, "updated_at").option();
    }

    private static Stream<String> refusal(Result<BranchName> ref) {
        return ref.fold(cause -> Stream.of(cause.message()),
                        _ -> Stream.empty());
    }

    /// The reason on one line: a refused name can hold a line break (U+0085) or a control character.
    private static Page skippedPage(PrNumber number, List<String> refusals, Option<Instant> updatedAt) {
        return new Page(List.of(),
                        List.of(Skipped.skipped(number,
                                                BLANKS.matcher(String.join("; ", refusals)).replaceAll(" ").strip(),
                                                updatedAt)));
    }

    private static Page pulled(PullRequest pull) {
        return new Page(List.of(pull), List.of());
    }

    private static Result<PullRequest> pullRequest(JsonNode node, String repo) {
        return Result.all(number(node, "number").flatMap(PrNumber::prNumber),
                          text(node, "title"),
                          state(node),
                          bool(node, "draft"),
                          branch(node, "base.ref"),
                          branch(node, "head.ref"),
                          optionalText(node, "head.repo.full_name"),
                          text(node, "head.sha").flatMap(Sha::sha),
                          optionalInstant(node, "merged_at"),
                          optionalInstant(node, "closed_at"),
                          instant(node, "updated_at"),
                          list(node,
                               "labels",
                               label -> text(label, "name")),
                          optionalText(node, "body").map(body -> refs(body, repo)))
                     .map(PullsPage::assembled);
    }

    /// `merged_at` set reads [PrState#MERGED] whatever `state` says (GitHub answers `closed` for a merged PR).
    private static Result<PrState> state(JsonNode node) {
        return Result.all(text(node, "state"), optionalInstant(node, "merged_at")).flatMap(PullsPage::state);
    }

    private static Result<PrState> state(String state, Option<Instant> mergedAt) {
        return mergedAt.isPresent()
               ? success(PrState.MERGED)
               : openOrClosed(state);
    }

    private static Result<PrState> openOrClosed(String state) {
        return switch (state) {
            case "open" -> success(PrState.OPEN);
            case "closed" -> success(PrState.CLOSED);
            default -> badField("state").result();
        };
    }

    private static Result<BranchName> branch(JsonNode node, String path) {
        return text(node, path).flatMap(BranchName::branchName);
    }

    private static ClosingRefs refs(Option<String> body, String repo) {
        return ClosingRefs.parse(body.or(""), repo);
    }

    private static PullRequest assembled(PrNumber number,
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
                                         ClosingRefs refs) {
        return new PullRequest(number,
                               title,
                               state,
                               draft,
                               base,
                               headRef,
                               headRepo,
                               head,
                               mergedAt,
                               closedAt,
                               updatedAt,
                               labels,
                               refs.closes(),
                               refs.partOf());
    }

    record unused() implements PullsPage {}
}
