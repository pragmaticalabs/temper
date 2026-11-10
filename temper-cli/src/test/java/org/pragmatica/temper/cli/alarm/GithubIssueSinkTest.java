package org.pragmatica.temper.cli.alarm;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Cause;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.cli.transport.HttpTransport;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// The `github-issue` sink against a fake GitHub: the issues are the alarm's only state, one per code and host.
@Timeout(20)
class GithubIssueSinkTest {
    private static final String TOKEN = "ghp_s3cretAlarmToken";
    private static final String REPO = "pragmaticalabs/temper";
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final Duration REPEAT = Duration.ofHours(6);
    private static final String STALE_TITLE = "temper alarm: dump-stale (bigboy)";
    /// About 192 KiB of an item's other fields (labels, user, URLs), less what the fake already sends.
    private static final int PAD_BYTES = 196_000;
    /// U+1F600: a surrogate pair, which JSON escapes as two six-byte unicode escapes.
    private static final String WORST_CHARACTER = new String(Character.toChars(0x1F600));

    private FakeGithub github;
    private GithubIssueSink sink;

    @BeforeEach
    void start() throws IOException {
        github = FakeGithub.start(TOKEN, REPO);
        sink = sinkAt(URI.create(github.url()), TOKEN);
    }

    @AfterEach
    void stop() {
        github.close();
    }

    private static GithubIssueSink sinkAt(URI base, String token) {
        return GithubIssueSink.githubIssueSink(GithubApi.githubApi(HttpTransport.httpClient(), base, REPO, token),
                                               "bigboy",
                                               REPEAT,
                                               Duration.ofSeconds(60));
    }

    private List<Delivered> delivered(Condition... conditions) {
        return sink.deliver(NOW, List.of(conditions))
                   .await()
                   .fold(cause -> fail(cause.message()), delivered -> delivered);
    }

    private Cause refused(GithubIssueSink target, Condition... conditions) {
        return target.deliver(NOW, List.of(conditions))
                     .await()
                     .fold(cause -> cause, delivered -> fail("delivered " + delivered));
    }

    private static Condition stale() {
        return Condition.firing(AlarmCode.DUMP_STALE, "no good pg_dump recorded");
    }

    @Test
    void firing_withoutAnOpenIssue_opensOneLabelled() {
        var delivered = delivered(stale());

        assertThat(github.issues()).hasSize(1);
        var issue = github.issues().getFirst();
        assertThat(issue.title).isEqualTo(STALE_TITLE);
        assertThat(issue.labels).containsExactly("temper-alarm");
        assertThat(github.writes()).containsExactly("POST /repos/pragmaticalabs/temper/issues");
        assertThat(github.received().getLast().body()).contains("\"labels\":[\"temper-alarm\"]", "no good pg_dump recorded");
        assertThat(delivered).containsExactly(Delivered.delivered(stale(), "#" + issue.number + " opened"));
    }

    /// An issue opened within the repeat interval is its own notice: no comment, no second issue.
    @Test
    void firing_withAnIssueOpenedWithinTheInterval_writesNothing() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(5)));

        var delivered = delivered(stale());

        assertThat(github.writes()).isEmpty();
        assertThat(github.issues()).hasSize(1);
        assertThat(delivered.getFirst().action()).isEqualTo("#" + issue.number + " open, repeat not due");
    }

    @Test
    void firing_withAnOldIssueCommentedWithinTheInterval_writesNothing() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(2)));
        github.comment(issue, "Still firing", NOW.minus(Duration.ofHours(1)));

        delivered(stale());

        assertThat(github.writes()).isEmpty();
        assertThat(issue.comments).hasSize(1);
    }

    /// Opened 7 h ago, last commented 6 h 1 min ago: the repeat is due, one comment.
    @Test
    void firing_afterTheInterval_commentsOnce() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(7)));
        github.comment(issue, "Still firing", NOW.minus(Duration.ofHours(6)).minusSeconds(60));

        var delivered = delivered(stale());

        assertThat(github.writes()).containsExactly("POST /repos/pragmaticalabs/temper/issues/" + issue.number + "/comments");
        assertThat(issue.comments).hasSize(2);
        assertThat(issue.comments.getLast().body()).contains("Still firing at 2026-10-13T10:00:00Z", "no good pg_dump recorded");
        assertThat(issue.state).isEqualTo("open");
        assertThat(delivered.getFirst().action()).isEqualTo("#" + issue.number + " commented");
    }

    @Test
    void clear_withAnOpenIssue_commentsClearedThenCloses() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(1)));

        var delivered = delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(github.writes()).containsExactly("POST /repos/pragmaticalabs/temper/issues/" + issue.number + "/comments",
                                                    "PATCH /repos/pragmaticalabs/temper/issues/" + issue.number);
        assertThat(issue.comments.getLast().body()).contains("cleared at 2026-10-13T10:00:00Z");
        assertThat(issue.state).isEqualTo("closed");
        assertThat(delivered.getFirst().action()).isEqualTo("#" + issue.number + " closed");
    }

    @Test
    void clear_withoutAnOpenIssue_writesNothing() {
        github.issue("temper alarm: dump-stale (cloudbb-1)", NOW.minus(Duration.ofDays(1)));

        var delivered = delivered(Condition.clear(AlarmCode.DUMP_STALE), Condition.clear(AlarmCode.DISK_LOW));

        assertThat(github.writes()).isEmpty();
        assertThat(delivered).extracting(Delivered::action).containsExactly("no open issue", "no open issue");
    }

    /// Another host's issue is not this condition's. The label is not needed to find an issue: an unlabelled one with
    /// this code's and host's marker line is this condition's, so no duplicate is opened.
    @Test
    void firing_ignoresOtherHostsIssues_butUsesAnUnlabelledOneWithTheTitle() {
        github.issue("temper alarm: dump-stale (cloudbb-1)", NOW.minus(Duration.ofDays(1)));
        var unlabelled = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(1)), "bug");

        var delivered = delivered(stale());

        assertThat(github.writes()).isEmpty();
        assertThat(github.issues()).hasSize(2);
        assertThat(delivered.getFirst().action()).isEqualTo("#" + unlabelled.number + " open, repeat not due");
    }

    /// A token that cannot label: the first run opens one issue and exits 2; later runs find it by its marker line, so three
    /// runs leave exactly one issue.
    @Test
    void tokenWithoutLabelRights_threeRuns_leaveOneIssue() {
        github.dropLabels();

        var first = refused(sink, stale());
        var second = delivered(stale());
        var third = delivered(stale());

        assertThat(CliError.exitCodeOf(first)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
        assertThat(github.issues()).hasSize(1);
        assertThat(second.getFirst().action()).endsWith("open, repeat not due");
        assertThat(third.getFirst().action()).endsWith("open, repeat not due");
        assertThat(github.writes()).containsExactly("POST /repos/pragmaticalabs/temper/issues");
    }

    /// GitHub label names are case-insensitive; so is the check.
    @Test
    void createdLabel_inAnotherCase_isAccepted() {
        github.labelAs("Temper-Alarm");

        var delivered = delivered(stale());

        assertThat(delivered.getFirst().action()).endsWith("opened");
    }

    /// One read of the open issues, then each condition on its own issue.
    @Test
    void severalConditions_eachOnItsOwnIssue() {
        var disk = github.issue("temper alarm: disk-low (bigboy)", NOW.minus(Duration.ofDays(1)));

        delivered(stale(), Condition.clear(AlarmCode.DISK_LOW), Condition.clear(AlarmCode.WORKER_LOST));

        assertThat(github.writes()).containsExactlyInAnyOrder("POST /repos/pragmaticalabs/temper/issues",
                                                              "POST /repos/pragmaticalabs/temper/issues/" + disk.number + "/comments",
                                                              "PATCH /repos/pragmaticalabs/temper/issues/" + disk.number);
        assertThat(github.received().stream().filter(request -> request.target().startsWith("/repos/pragmaticalabs/temper/issues?state=open"))).hasSize(1);
    }

    @Test
    void everyRequest_carriesTheTokenAsBearer_withTheGithubHeaders() {
        github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(1)));

        delivered(stale(), Condition.clear(AlarmCode.DISK_LOW));

        assertThat(github.received()).isNotEmpty()
                                     .allSatisfy(request -> assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN));
        assertThat(github.received().getFirst().target())
            .isEqualTo("/repos/pragmaticalabs/temper/issues?state=open&per_page=20");
    }

    /// A rejected token (401) fails the delivery with exit 2, and neither the failure nor its one-line rendering
    /// carries the token.
    @Test
    void rejectedToken_failsWithExit2_withoutTheToken() {
        var cause = refused(sinkAt(URI.create(github.url()), "ghp_wrongTokenValue"), stale());

        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
        assertThat(Console.oneLine(cause)).contains("HTTP 401", "GET /repos/pragmaticalabs/temper/issues")
                                          .doesNotContain("ghp_wrongTokenValue");
        assertThat(cause.toString()).doesNotContain("ghp_wrongTokenValue");
        assertThat(github.issues()).isEmpty();
    }

    @Test
    void unreachableGithub_failsWithExit2_withoutTheToken() throws IOException {
        var cause = refused(sinkAt(URI.create("http://127.0.0.1:" + closedPort()), TOKEN), stale());

        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
        assertThat(Console.oneLine(cause)).contains("Alarm sink request GET /repos/pragmaticalabs/temper/issues failed")
                                          .doesNotContain(TOKEN);
        assertThat(cause.toString()).doesNotContain(TOKEN);
    }

    /// Every condition is tried even when one fails; the delivery then fails as a whole.
    @Test
    void oneFailingWrite_stillTriesTheOthers_thenFails() {
        var broken = github.issue("temper alarm: disk-low (bigboy)", NOW.minus(Duration.ofDays(1)));
        github.breakIssue(broken.number);

        var cause = refused(sink, Condition.clear(AlarmCode.DISK_LOW), stale());

        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
        assertThat(cause.stream().map(Console::oneLine)).anySatisfy(line -> assertThat(line).contains("HTTP 500"));
        assertThat(broken.state).isEqualTo("open");
        assertThat(github.issues()).extracting(issue -> issue.title).contains(STALE_TITLE);
    }

    /// The recent comments are read 10 per page, from the repeat cutoff.
    @Test
    void firing_repeatCheck_readsTheCommentsSinceTheCutoff_10PerPage() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(2)));

        delivered(stale());

        assertThat(github.received()).anySatisfy(request -> assertThat(request.target())
            .isEqualTo("/repos/pragmaticalabs/temper/issues/" + issue.number + "/comments?per_page=10&since=2026-10-13T04%3A00%3A00Z"));
    }

    /// Over 100 comments within the interval (oldest first): the `cleared` comment on the second page is found, so the
    /// retried close writes no second one.
    @Test
    void clear_withOver100RecentComments_findsItsNewestClearedComment() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(1)));
        for (var n = 0; n < 149; n++) {
            github.comment(issue, "a human remark " + n, NOW.minus(Duration.ofHours(5)));
        }
        github.comment(issue, "cleared at 2026-10-13T09:50:00Z\n\n" + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared"), NOW.minus(Duration.ofMinutes(10)));

        delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(issue.comments).hasSize(150);
        assertThat(issue.state).isEqualTo("closed");
        assertThat(github.received().stream().filter(request -> request.target().contains("/comments?per_page=10&"))).hasSize(15);
    }

    /// A close failed, and the condition fires again: the newest own comment says `cleared`, which is no reminder, so
    /// `Firing again` is posted at once, even inside the repeat interval.
    @Test
    void firing_afterAClearedCommentWhoseCloseFailed_commentsFiringAgainAtOnce() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(2)));
        github.comment(issue, "cleared at 2026-10-13T09:00:00Z\n\n" + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared"), NOW.minus(Duration.ofHours(1)));

        var delivered = delivered(stale());

        assertThat(issue.comments).hasSize(2);
        assertThat(issue.comments.getLast().body())
            .isEqualTo("Firing again at 2026-10-13T10:00:00Z: no good pg_dump recorded\n\n"
                       + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "firing"));
        assertThat(delivered.getFirst().action()).isEqualTo("#" + issue.number + " commented, firing again");

        delivered(stale());

        assertThat(issue.comments).hasSize(2);
    }

    /// A human comment after the checker's `cleared` does not hide it: the newest OWN comment decides.
    @Test
    void firing_afterClearedThenAHumanComment_stillFiresAgain() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(2)));
        github.comment(issue, "cleared at 2026-10-13T09:00:00Z\n\n" + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared"), NOW.minus(Duration.ofHours(1)));
        github.comment(issue, "looking into it", NOW.minus(Duration.ofMinutes(30)));

        delivered(stale());

        assertThat(issue.comments.getLast().body()).startsWith("Firing again at ");
    }

    /// A token that may not set labels gets an unlabelled issue, which no later check could find: exit 2, named.
    @Test
    void openedWithoutTheLabel_failsWithExit2() {
        github.dropLabels();

        var cause = refused(sink, stale());

        assertThat(cause.stream()).singleElement().isInstanceOf(CliError.SinkFailed.class);
        assertThat(Console.oneLine(cause.stream().findFirst().orElseThrow())).contains("was opened without the temper-alarm label");
        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
    }

    /// A next link to another host would carry the token there; a relative one is not under the base either.
    @Test
    void nextLink_outsideTheApiBase_offHostOrRelative_failsWithExit2() {
        for (var link : List.of("http://elsewhere.example/repos/pragmaticalabs/temper/issues?page=2",
                                "/repos/pragmaticalabs/temper/issues?page=2")) {
            github.nextLink(link);

            var cause = refused(sink, stale());

            assertThat(CliError.exitCodeOf(cause)).as(link).isEqualTo(ExitCode.ALARM_UNDELIVERED);
            assertThat(Console.oneLine(cause)).as(link).contains("next page outside the API base");
        }
        assertThat(github.writes()).isEmpty();
    }

    /// The whole delivery is bounded: past the deadline it is undelivered (exit 2), whatever GitHub still does.
    @Test
    void slowGithub_pastTheDeadline_failsWithExit2() {
        github.delay(2000);
        var slow = GithubIssueSink.githubIssueSink(GithubApi.githubApi(HttpTransport.httpClient(), URI.create(github.url()), REPO, TOKEN),
                                                   "bigboy",
                                                   REPEAT,
                                                   Duration.ofMillis(300));

        var cause = refused(slow, stale());

        assertThat(cause).isInstanceOf(CliError.SinkFailed.class);
        assertThat(Console.oneLine(cause)).contains("Alarm sink request delivery within 300 ms failed");
        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
    }

    /// A close that fails after its `cleared` comment: the next check closes the issue without a second comment.
    @Test
    void failedClose_nextCheckCloses_withoutASecondClearedComment() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(1)));
        github.breakClose(issue.number);

        var cause = refused(sink, Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
        assertThat(issue.state).isEqualTo("open");
        assertThat(issue.comments).hasSize(1);

        github.breakClose(0);
        var delivered = delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(issue.state).isEqualTo("closed");
        assertThat(issue.comments).extracting(FakeGithub.Comment::body)
                                  .containsExactly("cleared at 2026-10-13T10:00:00Z\n\n" + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared"));
        assertThat(delivered.getFirst().action()).isEqualTo("#" + issue.number + " closed");
    }

    /// GitHub's open-issue list lags its writes, so two checks close together can each open an issue. Firing: the
    /// lowest-numbered is reminded as the only one would be; the others are listed for a person to close, and no call
    /// of any kind touches them.
    @Test
    void firing_withDuplicates_remindsTheOriginal_listsTheOthers_neverTouchesThem() {
        var original = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(7)));
        var second = github.issue(STALE_TITLE, NOW);
        var third = github.issue(STALE_TITLE, NOW);

        var delivered = delivered(stale());

        assertThat(original.comments.getLast().body()).startsWith("Still firing at ");
        assertThat(delivered.getFirst().action()).isEqualTo("#" + original.number + " commented; duplicates for a person to close: #"
                                                            + second.number + ", #" + third.number);
        assertUntouched(second, third);
    }

    /// Clear with duplicates: only the original gets `cleared` and is closed; the others are listed and untouched. The
    /// next clear check takes the next-lowest as the alarm's.
    @Test
    void clear_withDuplicates_closesTheOriginalOnly_listsTheOthers_neverTouchesThem() {
        var original = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(1)));
        var second = github.issue(STALE_TITLE, NOW);
        var third = github.issue(STALE_TITLE, NOW);

        var delivered = delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(original.state).isEqualTo("closed");
        assertThat(delivered.getFirst().action()).isEqualTo("#" + original.number + " closed; duplicates for a person to close: #"
                                                            + second.number + ", #" + third.number);
        assertUntouched(second, third);

        var next = delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(second.state).isEqualTo("closed");
        assertThat(next.getFirst().action()).isEqualTo("#" + second.number + " closed; duplicates for a person to close: #"
                                                       + third.number);
        assertUntouched(third);
    }

    /// No request of any kind (read or write) names these issues, and they are as they were: open, no comments.
    private void assertUntouched(FakeGithub.Issue... issues) {
        for (var issue : issues) {
            assertThat(github.received()).as("#" + issue.number)
                                         .noneMatch(request -> request.target().matches(".*/issues/" + issue.number + "(/.*)?(\\?.*)?$"));
            assertThat(issue.state).isEqualTo("open");
            assertThat(issue.comments).isEmpty();
        }
    }

    /// Firing again on the original (its newest own comment is `cleared`) while a duplicate exists: the original gets
    /// `Firing again` at once, and the duplicate is listed, untouched.
    @Test
    void firing_againOnTheOriginal_withADuplicate_firesAgain_listsTheDuplicate() {
        var original = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(2)));
        github.comment(original, "cleared at x\n\n" + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared"), NOW.minus(Duration.ofHours(1)));
        var duplicate = github.issue(STALE_TITLE, NOW);

        var delivered = delivered(stale());

        assertThat(original.comments.getLast().body()).startsWith("Firing again at ");
        assertThat(delivered.getFirst().action()).isEqualTo("#" + original.number + " commented, firing again; duplicates for a person to close: #"
                                                            + duplicate.number);
        assertUntouched(duplicate);
    }

    /// A failing close on the original (lowest-numbered) issue fails the delivery (exit 2), as with one issue; its
    /// duplicates wait for the next check.
    @Test
    void clear_originalCloseFails_failsWithExit2() {
        var first = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(1)));
        var second = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(1)));
        github.breakClose(first.number);

        var cause = refused(sink, Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
        assertThat(first.state).isEqualTo("open");
        assertThat(second.state).isEqualTo("open");
    }

    /// The marker line is the identity: an open issue with the exact title but no marker, no body, or another host's
    /// marker is never commented on or closed, firing or clear; firing opens a new, marked issue instead.
    @Test
    void issueWithTheTitle_butWithoutThisMarker_isNeverWritten() {
        var unmarked = github.issueWithBody(STALE_TITLE, "a person's own issue", NOW.minus(Duration.ofDays(2)));
        var bodiless = github.issueWithBody(STALE_TITLE, null, NOW.minus(Duration.ofDays(2)));
        var otherHost = github.issueWithBody(STALE_TITLE, "x\n\n<!-- temper-alarm:dump-stale:cloudbb-1 -->", NOW.minus(Duration.ofDays(2)));

        var fired = delivered(stale());
        var cleared = delivered(Condition.clear(AlarmCode.DUMP_STALE));

        var opened = github.issues().getLast();
        assertThat(fired.getFirst().action()).isEqualTo("#" + opened.number + " opened");
        assertThat(opened.body).endsWith("\n" + sink.issueMarker(AlarmCode.DUMP_STALE));
        assertThat(cleared.getFirst().action()).isEqualTo("#" + opened.number + " closed");
        assertThat(List.of(unmarked, bodiless, otherHost)).allSatisfy(issue -> assertThat(issue.comments).isEmpty())
                                                          .extracting(issue -> issue.state)
                                                          .containsOnly("open");
        assertThat(github.writes()).noneMatch(write -> write.contains("/issues/" + unmarked.number)
                                                       || write.contains("/issues/" + bodiless.number)
                                                       || write.contains("/issues/" + otherHost.number));
    }

    /// Clear with only an unmarked issue of the exact title: nothing is written.
    @Test
    void clear_withOnlyAnUnmarkedIssueOfTheTitle_writesNothing() {
        github.issueWithBody(STALE_TITLE, "a person's own issue", NOW.minus(Duration.ofDays(1)));

        var delivered = delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(github.writes()).isEmpty();
        assertThat(delivered.getFirst().action()).isEqualTo("no open issue");
    }

    /// The marker counts on a line of its own anywhere in the body, trimmed: a person's note appended below it keeps the
    /// issue the alarm's.
    @Test
    void markerLine_followedByAHumanNote_stillCounts() {
        var issue = github.issueWithBody(STALE_TITLE, "Firing since x\n\n   " + sink.issueMarker(AlarmCode.DUMP_STALE)
                                                      + "  \n\nnote: disk swapped on Monday", NOW.minus(Duration.ofHours(1)));

        var delivered = delivered(stale());

        assertThat(github.writes()).isEmpty();
        assertThat(delivered.getFirst().action()).isEqualTo("#" + issue.number + " open, repeat not due");
    }

    /// The marker quoted inside a longer line does not count: firing opens a marked issue of its own.
    @Test
    void markerInsideALongerLine_doesNotCount() {
        var quoting = github.issueWithBody(STALE_TITLE, "> see " + sink.issueMarker(AlarmCode.DUMP_STALE) + " above",
                                           NOW.minus(Duration.ofHours(1)));

        var delivered = delivered(stale());

        assertThat(delivered.getFirst().action()).endsWith("opened");
        assertThat(quoting.comments).isEmpty();
    }

    /// GitHub lists newest first; the original is still the lowest-numbered issue, whatever the list's order.
    @Test
    void firing_withDuplicatesListedNewestFirst_keepsTheLowestNumberedAsTheOriginal() {
        var original = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(5)));
        var newer = github.issue(STALE_TITLE, NOW);
        var newest = github.issue(STALE_TITLE, NOW);

        var delivered = delivered(stale());

        assertThat(delivered.getFirst().action()).isEqualTo("#" + original.number + " open, repeat not due; duplicates for a person to close: #"
                                                            + newer.number + ", #" + newest.number);
        assertUntouched(newer, newest);
    }

    /// The marker followed by text on the same line is not the marker line: firing opens a marked issue of its own.
    @Test
    void markerThenTextOnTheSameLine_doesNotCount() {
        var noted = github.issueWithBody(STALE_TITLE, "Firing since x\n\n" + sink.issueMarker(AlarmCode.DUMP_STALE) + " keep open",
                                         NOW.minus(Duration.ofHours(1)));

        var delivered = delivered(stale());

        assertThat(delivered.getFirst().action()).endsWith("opened");
        assertUntouched(noted);
    }

    /// An own comment whose body ends with a newline after the marker is still own: no second `cleared`.
    @Test
    void clear_ownClearedCommentWithATrailingNewline_isOwn() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(1)));
        github.comment(issue, "cleared at x\n\n" + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared") + "\n", NOW.minus(Duration.ofHours(1)));

        delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(issue.comments).hasSize(1);
        assertThat(issue.state).isEqualTo("closed");
    }

    /// A last line with the marker followed by text is not own: the marker must end the body.
    @Test
    void clear_markerThenTextOnTheLastLine_isNotOwn() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(1)));
        github.comment(issue, GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared") + " not mine", NOW.minus(Duration.ofHours(1)));

        delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(issue.comments).hasSize(2);
        assertThat(issue.state).isEqualTo("closed");
    }

    /// A clear after a firing reminder still gets its own `cleared` comment.
    @Test
    void clear_afterARecentReminder_stillCommentsCleared() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(1)));
        github.comment(issue, "Still firing at x: y", NOW.minus(Duration.ofHours(1)));

        delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(issue.comments).extracting(FakeGithub.Comment::body)
                                  .containsExactly("Still firing at x: y", "cleared at 2026-10-13T10:00:00Z\n\n" + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared"));
        assertThat(issue.state).isEqualTo("closed");
    }

    @Test
    void undecodableIssueList_failsWithExit2() {
        github.listAnswers("{\"not\":\"a list\"}");

        var cause = refused(sink, stale());

        assertThat(cause).isInstanceOf(CliError.SinkFailed.class);
        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
        assertThat(github.writes()).isEmpty();
    }

    /// A body over 1 MiB is not read into memory: the read fails (exit 2).
    @Test
    void oversizedAnswer_failsWithExit2() {
        github.listAnswers("[" + " ".repeat(GithubApi.MAX_ISSUE_PAGE_BODY) + "]");

        var cause = refused(sink, stale());

        assertThat(cause).isInstanceOf(CliError.SinkFailed.class);
        assertThat(Console.oneLine(cause)).contains("answer body over " + GithubApi.MAX_ISSUE_PAGE_BODY + " bytes");
        assertThat(github.writes()).isEmpty();
    }

    /// The open issues are read across `Link: rel="next"` pages: an issue on the last page is found, not duplicated.
    @Test
    void openIssues_followTheNextLinks() {
        github.pageSize(2);
        for (var host = 1; host <= 4; host++) {
            github.issue("temper alarm: dump-stale (other-" + host + ")", NOW.minus(Duration.ofDays(1)));
        }
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(1)));

        var delivered = delivered(stale());

        assertThat(github.writes()).isEmpty();
        assertThat(delivered.getFirst().action()).isEqualTo("#" + issue.number + " open, repeat not due");
        assertThat(github.received().stream().filter(request -> request.target().contains("issues?state=open"))).hasSize(3);
    }

    /// A page of exactly 20 issues at their worst (GitHub's largest body, every character a surrogate pair escaped at
    /// 12 bytes, plus about 192 KiB of other fields): 20 x 960 KiB fits the 19 MiB page cap, one MiB less does not.
    @Test
    void openIssues_aPageOfWorstCaseItems_isRead() {
        github.padItems(PAD_BYTES);
        var worst = WORST_CHARACTER.repeat(GithubApi.MAX_BODY_CHARS);
        for (var n = 0; n < GithubApi.ISSUES_PER_PAGE; n++) {
            github.issueWithBody("someone's issue " + n, worst, NOW.minus(Duration.ofDays(1)));
        }

        var delivered = delivered(stale());

        assertThat(delivered.getFirst().action()).endsWith("opened");
    }

    /// The same for comments: a page of exactly 10 worst-case comments (10 x 960 KiB) fits the 10 MiB page cap, one MiB
    /// less does not.
    @Test
    void commentsSince_aPageOfWorstCaseItems_isRead() {
        github.padItems(PAD_BYTES);
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(2)));
        var worst = WORST_CHARACTER.repeat(GithubApi.MAX_BODY_CHARS);
        for (var n = 0; n < GithubApi.COMMENTS_PER_PAGE; n++) {
            github.comment(issue, worst, NOW.minus(Duration.ofHours(1)));
        }

        var delivered = delivered(stale());

        assertThat(delivered.getFirst().action()).isEqualTo("#" + issue.number + " open, repeat not due");
    }

    /// 500 issues beside 500 pull requests (1000 items, 50 pages of 20) are read in full: the page bound lets
    /// [GithubApi#MAX_ISSUES] be reached.
    @Test
    void openIssues_500IssuesBeside500PullRequests_areReadInFull() {
        for (var n = 1; n <= 500; n++) {
            github.issue("temper alarm: dump-stale (other-" + n + ")", NOW.minus(Duration.ofDays(1)));
            github.pullRequest("some change " + n, NOW.minus(Duration.ofDays(1)));
        }

        var delivered = delivered(stale());

        assertThat(delivered.getFirst().action()).endsWith("opened");
    }

    /// More than 500 open issues fail the read rather than risk a duplicate issue.
    @Test
    void openIssues_over500_failWithExit2() {
        for (var host = 1; host <= 501; host++) {
            github.issue("temper alarm: dump-stale (other-" + host + ")", NOW.minus(Duration.ofDays(1)));
        }

        var cause = refused(sink, stale());

        assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
        assertThat(Console.oneLine(cause)).contains("more than 500 open issues");
        assertThat(github.writes()).isEmpty();
    }

    /// Pull requests do not count toward the 500: 500 issues and 50 pull requests are read in full.
    @Test
    void openIssues_pullRequestsDoNotCountTowardTheCap() {
        for (var host = 1; host <= 500; host++) {
            github.issue("temper alarm: dump-stale (other-" + host + ")", NOW.minus(Duration.ofDays(1)));
        }
        for (var pr = 1; pr <= 50; pr++) {
            github.pullRequest("some change " + pr, NOW.minus(Duration.ofDays(1)));
        }

        var delivered = delivered(stale());

        assertThat(delivered.getFirst().action()).endsWith("opened");
    }

    /// More pages than the page bound (pull requests filling them) fail the read.
    @Test
    void openIssues_overTheIssuePageBound_failWithExit2() {
        github.pageSize(1);
        for (var pr = 1; pr <= GithubApi.MAX_ISSUE_PAGES + 1; pr++) {
            github.pullRequest("some change " + pr, NOW.minus(Duration.ofDays(1)));
        }

        var cause = refused(sink, stale());

        assertThat(Console.oneLine(cause)).contains("more than " + GithubApi.MAX_ISSUE_PAGES + " pages of open issues");
        assertThat(github.writes()).isEmpty();
    }

    /// A pull request with the exact alarm title is not the alarm's: it gets no comment and no close, and the sink
    /// opens its own issue.
    @Test
    void pullRequestWithTheAlarmTitle_isNeverWritten() {
        var pr = github.pullRequest(STALE_TITLE, NOW.minus(Duration.ofDays(1)));

        var fired = delivered(stale());
        var cleared = delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(fired.getFirst().action()).isNotEqualTo("#" + pr.number + " open, repeat not due").endsWith("opened");
        assertThat(github.writes()).noneMatch(write -> write.contains("/issues/" + pr.number));
        assertThat(pr.comments).isEmpty();
        assertThat(pr.state).isEqualTo("open");
        assertThat(cleared.getFirst().action()).doesNotContain("#" + pr.number);
    }

    /// Near misses of the title carry no marker line for this code and host (another host's at most): none is found.
    @Test
    void titleMatch_isExact() {
        github.issue("TEMPER ALARM: dump-stale (bigboy)", NOW.minus(Duration.ofHours(1)));
        github.issue(" temper alarm: dump-stale (bigboy) ", NOW.minus(Duration.ofHours(1)));
        github.issue("temper alarm: dump-stale (bigboy2)", NOW.minus(Duration.ofHours(1)));

        var delivered = delivered(stale());

        assertThat(delivered.getFirst().action()).endsWith("opened");
        assertThat(github.writes()).containsExactly("POST /repos/pragmaticalabs/temper/issues");
    }

    /// Own comments are known by their hidden marker only: a human comment that starts like the checker's `cleared`
    /// is a human comment, so a firing condition is not "firing again" (the recent comment counts as a notice).
    @Test
    void humanClearedLookalike_isNotOwn() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(2)));
        github.comment(issue, "cleared at 09:00, I think", NOW.minus(Duration.ofHours(1)));

        var delivered = delivered(stale());

        assertThat(issue.comments).hasSize(1);
        assertThat(delivered.getFirst().action()).endsWith("open, repeat not due");
    }

    /// A human comment quoting the checker's `cleared` comment, marker included, then adding text: the marker is not
    /// at its end, so it is not own.
    @Test
    void humanQuoteOfAnAlarmComment_isNotOwn() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(2)));
        github.comment(issue, "> cleared at 2026-10-13T09:00:00Z\n> " + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared") + "\n\nno, it is not", NOW.minus(Duration.ofHours(1)));

        var delivered = delivered(stale());

        assertThat(issue.comments).hasSize(1);
        assertThat(delivered.getFirst().action()).endsWith("open, repeat not due");
    }

    /// Another code's `cleared` marker is not this code's.
    @Test
    void anotherCodesMarker_isNotOwn() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofHours(2)));
        github.comment(issue, "cleared at x\n\n" + GithubIssueSink.marker(AlarmCode.DISK_LOW, "cleared"), NOW.minus(Duration.ofHours(1)));

        delivered(stale());

        assertThat(issue.comments).hasSize(1);
    }

    /// Every body the sink writes ends with its marker: the issue body with its identity line (after `opened`), a
    /// reminder, a `cleared` comment.
    @Test
    void everyWrittenBody_endsWithItsMarker() {
        var old = github.issue("temper alarm: disk-low (bigboy)", NOW.minus(Duration.ofDays(1)));
        var cleared = github.issue("temper alarm: worker-lost (bigboy)", NOW.minus(Duration.ofDays(1)));

        delivered(stale(), Condition.firing(AlarmCode.DISK_LOW, "low"), Condition.clear(AlarmCode.WORKER_LOST));

        assertThat(github.received().stream().filter(request -> request.target().equals("/repos/pragmaticalabs/temper/issues")
                                                                && request.method().equals("POST")))
            .singleElement()
            .satisfies(request -> assertThat(request.body()).contains(GithubIssueSink.marker(AlarmCode.DUMP_STALE, "opened")
                                                                          + "\\n" + sink.issueMarker(AlarmCode.DUMP_STALE) + "\""));
        assertThat(old.comments.getLast().body()).endsWith(GithubIssueSink.marker(AlarmCode.DISK_LOW, "firing"));
        assertThat(cleared.comments.getLast().body()).endsWith(GithubIssueSink.marker(AlarmCode.WORKER_LOST, "cleared"));
    }

    /// Only comments within `repeat` are read: a `cleared` comment older than that is not seen, so a retried close
    /// gets another (as the class doc says).
    @Test
    void clear_withAnOwnClearedCommentOlderThanRepeat_commentsAgain() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(1)));
        github.comment(issue, "cleared at x\n\n" + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared"), NOW.minus(REPEAT).minusSeconds(60));

        delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(issue.comments).hasSize(2);
        assertThat(issue.state).isEqualTo("closed");
    }

    /// The own `cleared` comment is on the first of two pages, with over 100 human comments after it: every page is
    /// kept, so it is found and no second `cleared` is written.
    @Test
    void clear_ownClearedCommentOnAnEarlierPage_isFound() {
        var issue = github.issue(STALE_TITLE, NOW.minus(Duration.ofDays(1)));
        github.comment(issue, "cleared at x\n\n" + GithubIssueSink.marker(AlarmCode.DUMP_STALE, "cleared"), NOW.minus(Duration.ofHours(5)));
        for (var n = 0; n < 120; n++) {
            github.comment(issue, "a human remark " + n, NOW.minus(Duration.ofHours(4)));
        }

        delivered(Condition.clear(AlarmCode.DUMP_STALE));

        assertThat(issue.comments).hasSize(121);
        assertThat(issue.state).isEqualTo("closed");
    }

    /// The deadline bounds the whole delivery, not only its first request: the list answers at once, the later writes
    /// are slow, and the delivery still fails at the deadline.
    @Test
    void slowLaterRequests_pastTheDeadline_failWithExit2() {
        github.delayAfter(1, 2000);
        var slow = GithubIssueSink.githubIssueSink(GithubApi.githubApi(HttpTransport.httpClient(), URI.create(github.url()), REPO, TOKEN),
                                                   "bigboy",
                                                   REPEAT,
                                                   Duration.ofMillis(500));

        var cause = refused(slow, stale());

        assertThat(cause).isInstanceOf(CliError.SinkFailed.class);
        assertThat(Console.oneLine(cause)).contains("delivery within 500 ms");
    }

    @Test
    void api_toString_leavesTheTokenOut() {
        assertThat(sink.toString()).doesNotContain(TOKEN).contains(REPO);
    }

    private static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
