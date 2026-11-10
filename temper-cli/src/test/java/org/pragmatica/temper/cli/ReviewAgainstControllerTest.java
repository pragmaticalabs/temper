package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.temper.cli.T2Fixtures.REPO;
import static org.assertj.core.api.Assertions.assertThat;


/// `temper pr-author` and `temper review` against stub `fleet/pr-authors` and
/// `fleet/reviews` routes: every refusal code to `refused <kebab> <detail>` (2), `unavailable` to 69, the posted line,
/// and no secret on any line.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ReviewAgainstControllerTest {
    private static final String REVIEWS = "POST /fleet/reviews";
    private static final String AUTHORS_POST = "POST /fleet/pr-authors";
    private static final String AUTHORS_GET = "GET /fleet/pr-authors";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String PAT = "ghp_reviewerSecretPat";

    @TempDir
    Path home;

    @TempDir
    Path files;

    private FakeController controller;
    private CliHarness cli;
    private Path token;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN).verdictToken(T2Fixtures.VERDICT_TOKEN);
        cli = CliHarness.against(home, controller);
        token = T2Fixtures.tokenFile(files.resolve("verdict-token"), T2Fixtures.VERDICT_TOKEN);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private static String posted(boolean already) {
        return "{\"review\":4711,\"repo\":\"" + REPO
             + "\",\"pr\":7,\"sha\":\"" + SHA
             + "\",\"event\":\"APPROVE\",\"as\":\"v-one\","
             + "\"verdict\":55,\"alreadyPosted\":" + already
             + "}";
    }

    private CliHarness.Run review(String... extra) {
        var args = new java.util.ArrayList<>(List.of("review",
                                                     REPO,
                                                     "7",
                                                     SHA,
                                                     "--as",
                                                     "v-one",
                                                     "--token-file",
                                                     token.toString()));

        args.addAll(List.of(extra));

        return cli.run(args.toArray(String[]::new));
    }

    private List<FakeController.Received> reviewCalls() {
        return controller.received()
                         .stream()
                         .filter(call -> call.target()
                                             .endsWith("/fleet/reviews"))
                         .toList();
    }

    @Test
    void review_printsThePostedLine_withExitZero() {
        controller.on(REVIEWS, FakeController.Reply.json(200, posted(false)));
        var run = review();

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly("review 4711 " + REPO
                                             + "#7@" + SHA.substring(0, 12)
                                             + " APPROVE as=v-one verdict=55");
        assertThat(reviewCalls()).singleElement()
                  .satisfies(call -> {
                                 assertThat(call.authorization()).isEqualTo("Bearer " + T2Fixtures.VERDICT_TOKEN);
                                 assertThat(call.body()).contains("\"sha\":\"" + SHA + "\"", "\"as\":\"v-one\"")
                                           .doesNotContain("event", "body");
                             });
    }

    @Test
    void review_appendsAlreadyPosted_whenTheServerSaysSo() {
        controller.on(REVIEWS, FakeController.Reply.json(200, posted(true)));
        var run = review();

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly("review 4711 " + REPO
                                             + "#7@" + SHA.substring(0, 12)
                                             + " APPROVE as=v-one verdict=55 already-posted");
    }

    @Test
    void review_refuses_everyRefusalCode() {
        var codes = Map.of("NO_VERDICT",
                           "no-verdict",
                           "VERIFIER_MISMATCH",
                           "verifier-mismatch",
                           "AUTHOR_UNKNOWN",
                           "author-unknown",
                           "SELF_REVIEW",
                           "self-review",
                           "NOT_OPEN",
                           "not-open",
                           "HEAD_MOVED",
                           "head-moved",
                           "CARRY_NOT_MECHANICAL",
                           "carry-not-mechanical");

        codes.forEach((wire, kebab) -> {
            controller.on(REVIEWS, FakeController.Reply.json(422, "{\"error\":\"" + wire + " some detail\"}"));
            var run = review();

            assertThat(run.exit()).as(wire)
                      .isEqualTo(2);
            assertThat(run.out()).as(wire)
                      .containsExactly("refused " + kebab + " some detail");
        });
    }

    @Test
    void review_refusesGithubRejected_withTheFirstLineOnly() {
        controller.on(REVIEWS,
                      FakeController.Reply.json(422, "{\"error\":\"GITHUB_REJECTED Unprocessable\\nsecond line\"}"));
        var run = review();

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).containsExactly("refused github-rejected Unprocessable");
    }

    @Test
    void review_isUnavailable_withExitSixtyNine_forTheTransientCodes() {
        var codes = Map.of(503, "REVIEWER_CREDENTIAL", 409, "IN_FLIGHT");

        codes.forEach((status, wire) -> {
            controller.on(REVIEWS, FakeController.Reply.json(status, "{\"error\":\"" + wire + "\"}"));
            var run = review();

            assertThat(run.exit()).as(wire)
                      .isEqualTo(69);
            assertThat(run.out()).as(wire)
                      .containsExactly("unavailable " + wire.toLowerCase().replace('_', '-'));
        });
        controller.on(REVIEWS, FakeController.Reply.json(503, "{\"error\":\"RATE_LIMITED until=2026-11-05T10:00:00Z\"}"));
        assertThat(review().out()).containsExactly("unavailable rate-limited until=2026-11-05T10:00:00Z");
        controller.on(REVIEWS, FakeController.Reply.json(503, "{\"error\":\"UNKNOWN github-read\"}"));
        assertThat(review().out()).containsExactly("unavailable unknown github-read");
        controller.on(REVIEWS, FakeController.Reply.json(503, "{\"error\":\"BUSY\"}"));
        assertThat(review().out()).containsExactly("unavailable busy");
        controller.on(REVIEWS, FakeController.Reply.json(503, "{\"error\":\"LOCK_LOST\"}"));
        var lost = review();

        assertThat(lost.exit()).isEqualTo(69);
        assertThat(lost.out()).containsExactly("unavailable lock-lost");
        controller.on(REVIEWS, FakeController.Reply.json(503, "{\"error\":\"CARRY_PROOF_UNAVAILABLE\"}"));
        assertThat(review().out()).containsExactly("unavailable carry-proof-unavailable");
    }

    @Test
    void review_credentialFailure_is69_andPrintsNoSecret() {
        controller.on(REVIEWS, FakeController.Reply.json(503, "{\"error\":\"REVIEWER_CREDENTIAL\"}"));
        var run = review();

        assertThat(run.exit()).isEqualTo(69);
        assertThat(String.join("\n", run.out()) + run.err()).doesNotContain(PAT,
                                                                            T2Fixtures.VERDICT_TOKEN,
                                                                            CliHarness.TOKEN,
                                                                            token.toString());
    }

    @Test
    void review_isUnreachable_withExitSixtyNine_whenTheControllerIsGone() throws IOException {
        controller.close();
        assertThat(review().exit()).isEqualTo(69);
    }

    @Test
    void review_notAVerdictToken_is77() throws IOException {
        var other = token.resolveSibling("other");

        Files.writeString(other, "not-the-verdict-token\n");
        var run = cli.run("review", REPO, "7", SHA, "--as", "v-one", "--token-file", other.toString());

        assertThat(run.exit()).isEqualTo(77);
        assertThat(reviewCalls()).hasSize(1);
    }

    @Test
    void review_sendsTheBodyFile_withCrlfAsLf_andUsageErrorsMakeNoCall() throws IOException {
        controller.on(REVIEWS, FakeController.Reply.json(200, posted(false)));
        var body = files.resolve("body.md");

        Files.writeString(body, "line1\r\nline2\r\n");
        assertThat(review("--body-file", body.toString()).exit()).isZero();
        assertThat(reviewCalls()).singleElement()
                  .satisfies(call -> assertThat(call.body()).contains("\"body\":\"line1\\nline2\\n\""));
        Files.write(body, new byte[32 * 1024 + 1]);
        assertThat(review("--body-file", body.toString()).exit()).isEqualTo(64);
        Files.write(body, new byte[]{(byte) 0xC3, 0x28});
        assertThat(review("--body-file", body.toString()).exit()).isEqualTo(64);
        assertThat(review("--body-file",
                          files.resolve("missing").toString()).exit()).isEqualTo(64);
        assertThat(cli.run("review", REPO, "7", SHA, "--as", "bad name", "--token-file", token.toString()).exit()).isEqualTo(64);
        assertThat(cli.run("review", REPO, "7", SHA, "--as", "v-one").exit()).isEqualTo(64);
        assertThat(reviewCalls()).hasSize(1);
    }

    @Test
    void prAuthorAdd_postsTheLanes_andPrintsTheSet() {
        controller.on(AUTHORS_POST,
                      FakeController.Reply.json(201,
                                                "{\"repo\":\"" + REPO
                                               + "\",\"pr\":7,\"authors\":[{\"lane\":\"gh-wp4a\",\"addedBy\":\"ghwp4a\","
                                               + "\"addedAt\":\"2026-11-05T09:00:00Z\"},{\"lane\":\"gh-wp4b\",\"addedBy\":\"ghwp4a\","
                                               + "\"addedAt\":\"2026-11-05T09:00:00Z\"}]}"));
        var run = cli.run("pr-author", REPO, "7", "add", "gh-wp4a", "gh-wp4b", "--token-file", token.toString());

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly(REPO + "#7 gh-wp4a added_by=ghwp4a at=2026-11-05T09:00:00Z",
                                              REPO + "#7 gh-wp4b added_by=ghwp4a at=2026-11-05T09:00:00Z");
        assertThat(controller.received()).singleElement()
                  .satisfies(call -> assertThat(call.body()).contains("\"lanes\":[\"gh-wp4a\",\"gh-wp4b\"]"));
    }

    @Test
    void prAuthorList_readsWithTheOrdinaryToken() {
        controller.on(AUTHORS_GET,
                      FakeController.Reply.json(200,
                                                "{\"repo\":\"" + REPO
                                               + "\",\"pr\":7,\"authors\":[{\"lane\":\"gh-wp4a\",\"addedBy\":\"x\","
                                               + "\"addedAt\":\"2026-11-05T09:00:00Z\"}]}"));
        var run = cli.run("pr-author", REPO, "7");

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly(REPO + "#7 gh-wp4a added_by=x at=2026-11-05T09:00:00Z");
        assertThat(controller.received()).singleElement()
                  .satisfies(call -> {
                                 assertThat(call.target()).contains("repo=")
                                           .contains("pr=7");
                                 assertThat(call.authorization()).isEqualTo("Bearer " + CliHarness.TOKEN);
                             });
    }

    @Test
    void prAuthor_usageErrors_makeNoCall() {
        assertThat(cli.run("pr-author", REPO, "7", "add", "--token-file", token.toString()).exit()).isEqualTo(64);
        assertThat(cli.run("pr-author", REPO, "7", "add", "bad lane", "--token-file", token.toString()).exit()).isEqualTo(64);
        assertThat(cli.run("pr-author", REPO, "7", "add", "gh-wp4a").exit()).isEqualTo(64);
        assertThat(cli.run("pr-author", REPO, "7", "remove", "gh-wp4a").exit()).isEqualTo(64);
        assertThat(cli.run("pr-author", REPO, "0").exit()).isEqualTo(64);
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void prAuthorAdd_refusedWrite_is2() {
        controller.on(AUTHORS_POST, FakeController.Reply.json(422, "{\"error\":\"AUTHOR_SET_FULL\"}"));
        var run = cli.run("pr-author", REPO, "7", "add", "gh-wp4a", "--token-file", token.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).containsExactly("refused author-set-full");
    }
}
