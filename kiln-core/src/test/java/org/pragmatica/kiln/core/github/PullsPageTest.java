package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.ModelError;
import org.pragmatica.kiln.core.model.PrNumber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.causes;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.kiln.core.Fixtures.resource;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §4.1: one page of `pulls`, decoded leniently. `github/pulls-page.json` is three PRs of the live
/// `pragmaticalabs/pragmatica` answer (captured 2026-10-30, unauthenticated), with every field GitHub sent.
class PullsPageTest {
    private static final String REPO = "pragmaticalabs/pragmatica";
    private static final String SHA = "7ec5f982e041d1a68b596975c9a83cb18eeda7be";

    private static String pr(String overrides) {
        return """
               [{"number": 7, "title": "t", "state": "closed", "draft": false, "base": {"ref": "main"},
                 "head": {"ref": "kiln-wp2", "sha": "%s", "repo": {"full_name": "%s"}},
                 "merged_at": null, "closed_at": "2026-10-30T01:00:00Z", "updated_at": "2026-10-30T02:00:00Z",
                 "labels": [{"name": "kiln"}, {"name": "f1"}], "body": "Fixes #3", %s "extra": {"nested": [1, 2]}}]
               """.formatted(SHA, REPO, overrides);
    }

    /// One PR object of [#pr] with its number and its head ref replaced.
    private static String item(long number, String headRef) {
        var array = pr("").replace("\"number\": 7", "\"number\": " + number)
                          .replace("\"ref\": \"kiln-wp2\"", "\"ref\": \"" + headRef + "\"")
                          .strip();

        return array.substring(1, array.length() - 1);
    }

    private static String page(String... items) {
        return "[" + String.join(",", items) + "]";
    }

    private static PrNumber number(long raw) {
        return ok(PrNumber.prNumber(raw));
    }

    @Test
    void decode_readsTheLivePage() {
        var page = ok(PullsPage.decode(resource("github/pulls-page.json"), REPO)).pulls();

        assertThat(page).extracting(PullRequest::number)
                        .containsExactly(number(2494), number(2500), number(2504));

        var merged = page.getFirst();
        assertThat(merged.state()).isEqualTo(PrState.MERGED);
        assertThat(merged.draft()).isFalse();
        assertThat(merged.base()
                         .value()).isEqualTo("release-1.0.0-rc5");
        assertThat(merged.headRef()
                         .value()).isEqualTo("feat/wobs-w4-cutover-relay");
        assertThat(merged.head()
                         .value()).isEqualTo(SHA);
        assertThat(merged.headRepo()).isEqualTo(some(REPO));
        assertThat(merged.mergedAt()).isEqualTo(some(Instant.parse("2026-10-30T02:12:12Z")));
        assertThat(merged.closedAt()).isEqualTo(some(Instant.parse("2026-10-30T02:12:12Z")));
        assertThat(merged.updatedAt()).isEqualTo(Instant.parse("2026-10-30T02:14:50Z"));
        assertThat(merged.closes()).isEmpty();
        assertThat(merged.partOf()).containsExactly(number(2468));

        var draft = page.get(1);
        assertThat(draft.state()).isEqualTo(PrState.OPEN);
        assertThat(draft.draft()).isTrue();
        assertThat(draft.mergedAt()).isEqualTo(none());
        assertThat(draft.closes()).containsExactly(number(1671));
        assertThat(draft.partOf()).isEmpty();
    }

    /// #2504's body says it does not close #2258, then writes "which closes #2258": GitHub's keyword reading closes it
    /// on merge, and kiln reads the body as GitHub does.
    @Test
    void decode_readsClosingKeywordsAsGitHubDoes_evenInProse() {
        var page = ok(PullsPage.decode(resource("github/pulls-page.json"), REPO)).pulls();

        assertThat(page.get(2)
                       .closes()).containsExactly(number(2258));
        assertThat(page.get(2)
                       .partOf()).containsExactly(number(2258));
    }

    @Test
    void decode_readsClosedWithoutMergeAsClosed_andIgnoresUnknownFields() {
        var pr = ok(PullsPage.decode(pr(""), REPO)).pulls().getFirst();

        assertThat(pr.state()).isEqualTo(PrState.CLOSED);
        assertThat(pr.labels()).containsExactly("kiln", "f1");
        assertThat(pr.closes()).containsExactly(number(3));
        assertThat(pr.sameRepo(REPO)).isTrue();
    }

    /// The configured repo reaches the body's reading: `owner/repo#N` counts only when it names that repo.
    @Test
    void decode_readsAFullReference_onlyToTheConfiguredRepo() {
        var json = pr("").replace("Fixes #3", "Fixes PragmaticaLabs/Pragmatica#4, fixes other/repo#5");

        assertThat(ok(PullsPage.decode(json, REPO)).pulls().getFirst()
                                                   .closes()).containsExactly(number(4));
        assertThat(ok(PullsPage.decode(json, "other/repo")).pulls().getFirst()
                                                           .closes()).containsExactly(number(5));
    }

    /// `merged_at` decides MERGED; GitHub's `state` says `closed` for a merged PR.
    @Test
    void decode_readsMergedAtAsMerged_whateverTheState() {
        var pr = ok(PullsPage.decode(pr("\"merged_at\": \"2026-10-30T01:00:00Z\","), REPO)).pulls().getFirst();

        assertThat(pr.state()).isEqualTo(PrState.MERGED);
    }

    @Test
    void decode_readsAGoneForkAndNoBody_asAbsent() {
        var json = """
                   [{"number": 8, "title": "t", "state": "open", "draft": true, "base": {"ref": "main"},
                     "head": {"ref": "x", "sha": "%s", "repo": null}, "merged_at": null, "closed_at": null,
                     "updated_at": "2026-10-30T02:00:00Z", "labels": [], "body": null}]
                   """.formatted(SHA);
        var pr = ok(PullsPage.decode(json, REPO)).pulls().getFirst();

        assertThat(pr.state()).isEqualTo(PrState.OPEN);
        assertThat(pr.headRepo()).isEqualTo(none());
        assertThat(pr.closes()).isEmpty();
        assertThat(pr.partOf()).isEmpty();
        assertThat(pr.closedAt()).isEqualTo(none());
    }

    @Test
    void decode_readsAnEmptyPage() {
        assertThat(ok(PullsPage.decode("[]", REPO)).pulls()).isEmpty();
    }

    @Test
    void decode_failsNamingAMissingField() {
        var json = pr("").replace("\"sha\": \"" + SHA + "\",", "");

        assertThat(causes(PullsPage.decode(json, REPO))).contains(GitHubError.BadField.FACTORY.apply("head.sha"));
    }

    @Test
    void decode_failsOnAMistypedOptionalField_neverReadingItAsAbsent() {
        assertThat(causes(PullsPage.decode(pr("\"merged_at\": 17,"), REPO))).contains(GitHubError.BadField.FACTORY.apply("merged_at"));
    }

    @Test
    void decode_failsOnAnUnknownState() {
        var json = pr("").replace("\"state\": \"closed\"", "\"state\": \"draft\"");

        assertThat(causes(PullsPage.decode(json, REPO))).contains(GitHubError.BadField.FACTORY.apply("state"));
    }

    @Test
    void decode_failsOnAnUnparseableTime() {
        var json = pr("").replace("\"updated_at\": \"2026-10-30T02:00:00Z\"", "\"updated_at\": \"yesterday\"");

        assertThat(causes(PullsPage.decode(json, REPO))).anySatisfy(cause -> assertThat(cause).isInstanceOfSatisfying(GitHubError.Unparseable.class,
                                                                                                 unparseable -> assertThat(unparseable.what()).isEqualTo("updated_at")));
    }

    @Test
    void decode_failsOnAnInvalidValueObject() {
        var json = pr("").replace(SHA, "abc");

        assertThat(causes(PullsPage.decode(json, REPO))).hasAtLeastOneElementOfType(ModelError.InvalidValue.class);
    }

    /// One bad PR fails the page: a page is never read as shorter than GitHub sent it.
    @Test
    void decode_failsThePage_whenOnePrIsBad() {
        var good = pr("").strip();
        var bad = pr("").replace("\"title\": \"t\",", "");
        var json = good.substring(0, good.length() - 1) + "," + bad.strip()
                                                                   .substring(1);

        assertThat(ok(PullsPage.decode(good, REPO)).pulls()).hasSize(1);
        assertThat(causes(PullsPage.decode(json, REPO))).contains(GitHubError.BadField.FACTORY.apply("title"));
    }

    @Test
    void decode_failsOnAnythingButAJsonArray() {
        assertThat(causes(PullsPage.decode("{\"message\": \"Not Found\"}", REPO))).contains(GitHubError.BadField.FACTORY.apply("$"));
        assertThat(causes(PullsPage.decode("", REPO))).contains(GitHubError.BadField.FACTORY.apply("$"));
        assertThat(causes(PullsPage.decode("[{]", REPO))).anySatisfy(cause -> assertThat(cause).isInstanceOfSatisfying(GitHubError.Unparseable.class,
                                                                                                 unparseable -> assertThat(unparseable.what()).isEqualTo("answer")));
        assertThat(causes(PullsPage.decode(List.of("[]", "x")
                                                .toString(), REPO))).hasAtLeastOneElementOfType(GitHubError.Unparseable.class);
    }

    /// Ruling 7: a PR whose ref is no BranchName (stricter than git) is left out and listed as skipped, never failing
    /// the page; the good PRs around it are decoded.
    @Test
    void decode_skipsAPrWhoseRefIsNoBranchName_andKeepsTheRest() {
        var decoded = ok(PullsPage.decode(page(item(1, "kiln-wp2"), item(2, "+main"), item(3, "feature/x")), REPO));

        assertThat(decoded.pulls()).extracting(PullRequest::number)
                                   .containsExactly(number(1), number(3));
        assertThat(decoded.skipped()).extracting(PullsPage.Skipped::number)
                                     .containsExactly(number(2));
        assertThat(decoded.skipped()
                          .getFirst()
                          .reason()).isEqualTo("Invalid branch: '+main'");
        assertThat(ok(PullsPage.decode(page(item(1, "kiln-wp2"), item(3, "feature/x")), REPO)).skipped()).isEmpty();
    }

    /// `HEAD` as the base, a U+0085 in the head, and both refs bad: each PR is one skipped entry, its reason one line.
    @Test
    void decode_skipsEachPrWithABadRef_once_withAOneLineReason() {
        var headBase = item(4, "kiln-wp2").replace("\"ref\": \"main\"", "\"ref\": \"HEAD\"");
        var bothBad = item(6, "+x").replace("\"ref\": \"main\"", "\"ref\": \"HEAD\"");
        var decoded = ok(PullsPage.decode(page(headBase, item(5, "a\u0085b"), bothBad), REPO));

        assertThat(decoded.pulls()).isEmpty();
        assertThat(decoded.skipped()).extracting(PullsPage.Skipped::number)
                                     .containsExactly(number(4), number(5), number(6));
        assertThat(decoded.skipped()).extracting(PullsPage.Skipped::reason)
                                     .allMatch(reason -> !reason.isBlank() && reason.chars()
                                                                                    .noneMatch(c -> Character.isISOControl(c)
                                                                                                    || Character.isWhitespace(c) && c != ' '))
                                     .anyMatch(reason -> reason.contains("HEAD"));
        assertThat(decoded.skipped()
                          .get(2)
                          .reason()).contains("HEAD")
                                    .contains("+x");
    }

    /// A missing ref is no refused name but a broken answer: it still fails the page, as any missing field does.
    @Test
    void decode_failsThePage_onAMissingRef() {
        var json = page(item(1, "kiln-wp2"), item(2, "kiln-wp2").replace("\"ref\": \"kiln-wp2\", ", ""));

        assertThat(causes(PullsPage.decode(json, REPO))).contains(GitHubError.BadField.FACTORY.apply("head.ref"));
    }

    /// n6: a page whose PRs are all skipped still names each one's `updated_at`, so the incremental poll can advance its
    /// mark; one that does not parse is absent and fails nothing.
    @Test
    void decode_givesASkippedPrItsUpdatedAt_whenItParses() {
        var unparseable = item(3, "+y").replace("\"updated_at\": \"2026-10-30T02:00:00Z\"", "\"updated_at\": \"yesterday\"");
        var decoded = ok(PullsPage.decode(page(item(1, "+main"), item(2, "HEAD"), unparseable), REPO));

        assertThat(decoded.pulls()).isEmpty();
        assertThat(decoded.skipped()).extracting(PullsPage.Skipped::updatedAt)
                                     .containsExactly(some(Instant.parse("2026-10-30T02:00:00Z")),
                                                      some(Instant.parse("2026-10-30T02:00:00Z")),
                                                      none());
    }
}
