package org.pragmatica.temper.cli.alarm;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.cli.CliError;


/// `github-issue` (config `alarm.sink`): one GitHub issue per condition code and checked host. An open issue is the
/// alarm's for a code when a line of its body, trimmed, is exactly `<!-- temper-alarm:<code>:<host> -->`
/// ([#issueMarker]), anywhere in the body, so text a person appends below it keeps the issue the alarm's; the title
/// `temper alarm: <code> (<host>)` only names a new issue, and the `temper-alarm` label is applied, never relied on (a
/// token that cannot label never causes a duplicate). The open issues and their comments are the alarm's only state,
/// so the checker keeps none.
///
/// Every comment the sink writes ends with a hidden marker, `<!-- temper-alarm:<code>:<kind> -->` (kind `firing` or
/// `cleared`; an issue body carries `opened` above its identity line), and a comment is the checker's own only when its
/// body ends with the marker for this code: no visible text counts, so a human comment that starts like one, or quotes
/// one, is a human comment. Only the comments within `repeat` are read (oldest first, in full, up to
/// [GithubApi#MAX_PAGES] pages):
/// - firing, no open issue: an issue is opened, labelled `temper-alarm`;
/// - firing, open issue whose newest own comment within `repeat` is `cleared` (a close that failed): `Firing again`
///   at once;
/// - firing, open issue otherwise: `Still firing`, only if the issue was opened more than `repeat` ago and has no
///   comment (anyone's) within `repeat`;
/// - clear, open issue: `cleared`, unless the newest own comment within `repeat` is already `cleared`, then the issue
///   is closed. A `cleared` comment older than `repeat` is not seen, so it does get another;
/// - clear, no open issue: nothing;
/// - several open issues, firing or clear: the lowest-numbered is the alarm's, handled as above; the others are
///   duplicates, which the sink never writes to, reads nothing of, and lists on the line for a person to close
///   (`; duplicates for a person to close: #12, #13`). After a clear closed the original, the next check takes the
///   next-lowest as the alarm's, so duplicates left open are handled one per clear check.
///
/// Guarantee per check: every condition is attempted, concurrently and independently, after one read of the open
/// issues; any failure fails the delivery (exit 2) once all were tried, and so does the whole delivery taking longer
/// than `deadline`. Mechanism and its limits: the read is a snapshot, and GitHub's open-issue list lags its writes by
/// seconds, so two checks close together in time, overlapping or one after the other, can both open an issue; the
/// systemd timer's 10-minute spacing keeps checks apart, and the duplicate is then named on every line until a person
/// closes it. A close that fails after its comment leaves the issue open, and the next check within `repeat` closes it
/// without a second `cleared` comment. Accepted risk: a human comment that quotes a marker bare as its very last line
/// would count as the checker's own. Limits: the list can also show a just-closed issue as open, so a check seconds
/// after a close may act on it (comment on a closed issue, or keep it as the original and name the new one a
/// duplicate); the alarm then goes unnotified for at most one check interval, since the next check sees the close and
/// opens or keeps the right issue. The `deadline` cut does not cancel: a write already sent can land on GitHub after
/// the delivery was reported failed (exit 2). Two steps run before any deadline and are bounded only by the unit's
/// `TimeoutStartSec` (a manual run, not at all): resolving this machine's name when `alarm.host` is unset, and
/// measuring a path on a hung mount.
public record GithubIssueSink(GithubApi github, String host, Duration repeat, Duration deadline) implements AlarmSink {
    private static final String OPENED = "opened";
    private static final String FIRING = "firing";
    private static final String CLEARED = "cleared";
    /// The comment marker kinds; no host may be named like one (`AlarmConfig` refuses it), or its issue marker line
    /// would be a kind marker line.
    public static final List<String> KINDS = List.of(OPENED, FIRING, CLEARED);

    public static GithubIssueSink githubIssueSink(GithubApi github, String host, Duration repeat, Duration deadline) {
        return new GithubIssueSink(github, host, repeat, deadline);
    }

    @Override
    public Promise<List<Delivered>> deliver(Instant now, List<Condition> conditions) {
        return github.openIssues()
                     .flatMap(open -> each(open, conditions, now))
                     .timeout(TimeSpan.fromDuration(deadline))
                     .mapError(this::overDeadline);
    }

    /// A delivery cut by `deadline` is undelivered (exit 2), like any other sink failure.
    private Cause overDeadline(Cause cause) {
        return cause instanceof CoreError.Timeout
               ? CliError.SinkFailed.FACTORY.apply("delivery within " + spoken(deadline), cause)
               : cause;
    }

    /// `120 s`, or `300 ms` below a second.
    static String spoken(Duration duration) {
        return duration.compareTo(Duration.ofSeconds(1)) < 0
               ? duration.toMillis() + " ms"
               : duration.toSeconds() + " s";
    }

    /// The issue title for `code` on this host; it names a new issue, and never finds one again.
    public String title(AlarmCode code) {
        return "temper alarm: " + code.wire() + " (" + host + ")";
    }

    /// The hidden body line that makes an open issue the alarm's for `code` on this host.
    public String issueMarker(AlarmCode code) {
        return GithubApi.MARKER_PREFIX + code.wire() + ":" + host + " -->";
    }

    /// Iteration: every condition is tried, then the failures (if any) are collected into one.
    private Promise<List<Delivered>> each(List<GithubApi.Issue> open, List<Condition> conditions, Instant now) {
        return Promise.allOf(attempts(open, conditions, now)).flatMap(GithubIssueSink::collected);
    }

    private List<Promise<Delivered>> attempts(List<GithubApi.Issue> open, List<Condition> conditions, Instant now) {
        return conditions.stream()
                         .map(condition -> delivered(open, condition, now))
                         .toList();
    }

    private static <T> Promise<List<T>> collected(List<Result<T>> results) {
        return Result.allOf(results).async();
    }

    private Promise<Delivered> delivered(List<GithubApi.Issue> open, Condition condition, Instant now) {
        var issues = issuesOf(open, condition.code());

        return switch (condition) {
            case Condition.Firing firing -> issues.isEmpty()
                                            ? opened(firing, now)
                                            : reminded(issues.getFirst(), firing, now).map(reminder -> withDuplicates(reminder,
                                                                                                                      issues));
            case Condition.Clear clear -> issues.isEmpty()
                                          ? Promise.success(Delivered.delivered(clear, "no open issue"))
                                          : closed(issues.getFirst(), clear, now).map(original -> withDuplicates(original,
                                                                                                                 issues));
        };
    }

    /// The open issues whose body has this code's and host's marker line, lowest-numbered first (two or more after
    /// checks close together in time).
    private List<GithubApi.Issue> issuesOf(List<GithubApi.Issue> open, AlarmCode code) {
        var marker = issueMarker(code);

        return open.stream()
                   .filter(issue -> carries(issue, marker))
                   .sorted(Comparator.comparingInt(GithubApi.Issue::number))
                   .toList();
    }

    /// A line of the body, trimmed, is exactly `marker`: one quoted inside a longer line does not count.
    private static boolean carries(GithubApi.Issue issue, String marker) {
        return issue.body()
                    .filter(body -> body.lines()
                                        .map(String::strip)
                                        .anyMatch(marker::equals))
                    .isPresent();
    }

    /// The condition's own issue's line, then the other matches for a person to close: the sink never writes to them.
    private static Delivered withDuplicates(Delivered own, List<GithubApi.Issue> issues) {
        return issues.size() == 1
               ? own
               : Delivered.delivered(own.condition(),
                                     own.action()
                                    + "; duplicates for a person to close: " + numbers(issues.subList(1, issues.size())));
    }

    private static String numbers(List<GithubApi.Issue> duplicates) {
        return duplicates.stream()
                         .map(issue -> "#" + issue.number())
                         .collect(Collectors.joining(", "));
    }

    /// The hidden marker that makes a body the checker's own, for `code` and `kind`.
    public static String marker(AlarmCode code, String kind) {
        return GithubApi.MARKER_PREFIX + code.wire() + ":" + kind + " -->";
    }

    private static String marked(String text, AlarmCode code, String kind) {
        return text + "\n\n" + marker(code, kind);
    }

    private Promise<Delivered> opened(Condition.Firing firing, Instant now) {
        return github.open(title(firing.code()),
                           marked(firing.detail()
                                 + "\n\nFiring since " + now
                                 + " on " + host
                                 + " (`temper alarm-check`, code `" + firing.code()
                                                                            .wire()
                                 + "`). Closed by the first check that finds it clear.",
                                  firing.code(),
                                  OPENED)
                          + "\n" + issueMarker(firing.code()))
                     .map(issue -> Delivered.delivered(firing,
                                                       "#" + issue.number() + " opened"));
    }

    /// The recent comments decide: a newest own `cleared` comment means the condition fires again after a close that
    /// failed; otherwise the issue's creation and any recent comment count as the last notice.
    private Promise<Delivered> reminded(GithubApi.Issue issue, Condition.Firing firing, Instant now) {
        var cutoff = now.minus(repeat);

        return github.commentsSince(issue.number(),
                                    cutoff)
                     .flatMap(recent -> reminder(issue, firing, now, cutoff, recent));
    }

    private Promise<Delivered> reminder(GithubApi.Issue issue,
                                        Condition.Firing firing,
                                        Instant now,
                                        Instant cutoff,
                                        List<GithubApi.Comment> recent) {
        return newestOwnIsCleared(recent, firing.code())
               ? commented(issue, firing, "Firing again at " + now + ": " + firing.detail(), "commented, firing again")
               : repeated(issue, firing, now, cutoff, recent);
    }

    private Promise<Delivered> repeated(GithubApi.Issue issue,
                                        Condition.Firing firing,
                                        Instant now,
                                        Instant cutoff,
                                        List<GithubApi.Comment> recent) {
        return issue.createdAt()
                    .isBefore(cutoff) && recent.isEmpty()
               ? commented(issue, firing, "Still firing at " + now + ": " + firing.detail(), "commented")
               : Promise.success(Delivered.delivered(firing, "#" + issue.number() + " open, repeat not due"));
    }

    private Promise<Delivered> commented(GithubApi.Issue issue, Condition.Firing firing, String body, String action) {
        return github.comment(issue.number(),
                              marked(body,
                                     firing.code(),
                                     FIRING))
                     .map(_ -> Delivered.delivered(firing,
                                                   "#" + issue.number() + " " + action));
    }

    /// Comment first, then close (see the guarantee above).
    private Promise<Delivered> closed(GithubApi.Issue issue, Condition.Clear clear, Instant now) {
        return github.commentsSince(issue.number(),
                                    now.minus(repeat))
                     .flatMap(recent -> clearedOnce(issue,
                                                    clear.code(),
                                                    now,
                                                    recent))
                     .flatMap(_ -> github.close(issue.number()))
                     .map(_ -> Delivered.delivered(clear,
                                                   "#" + issue.number() + " closed"));
    }

    private Promise<Unit> clearedOnce(GithubApi.Issue issue,
                                      AlarmCode code,
                                      Instant now,
                                      List<GithubApi.Comment> recent) {
        return newestOwnIsCleared(recent, code)
               ? Promise.unitPromise()
               : github.comment(issue.number(), marked("cleared at " + now, code, CLEARED));
    }

    /// The newest of the checker's own comments for `code` (the last, in GitHub's oldest-first order) is `cleared`.
    private static boolean newestOwnIsCleared(List<GithubApi.Comment> recent, AlarmCode code) {
        return Option.from(recent.stream()
                                 .flatMap(comment -> comment.body()
                                                            .stream())
                                 .flatMap(body -> ownKind(body, code).stream())
                                 .reduce((_, newer) -> newer))
                     .filter(CLEARED::equals)
                     .isPresent();
    }

    /// The kind of an own comment: its body ends with the marker for `code`.
    private static Option<String> ownKind(String body, AlarmCode code) {
        var trimmed = body.strip();

        return Option.from(KINDS.stream().filter(kind -> trimmed.endsWith(marker(code, kind))).findFirst());
    }
}
