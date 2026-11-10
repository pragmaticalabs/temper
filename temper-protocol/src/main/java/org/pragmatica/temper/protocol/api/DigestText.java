package org.pragmatica.temper.protocol.api;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.ResultRecordDto.CountsDto;


/// The text form of a [DigestView], pure: one fact per line, `key=value` tokens, no decoration, meant to sit in an LLM
/// agent's context (about 20 lines at the default `max`, bounded at [#QUEUE_LINES]). The controller answers it for `format=text` and the CLI
/// prints it for `temper digest`, so both read the same.
///
/// ```
/// digest cursor=1232.1760608790123456 more=true asOf=2026-10-16T10:00:00Z
/// gap 100..200 deleted by retention
/// finished job=54 FAILED(tests) sha=0cf5631 ref=main owner=cto prio=5 testcases=120 failed=2 KNOWN(#1679) seq=1230
/// started job=56 unit=gate sha=0cf5631 ref=main owner=cto prio=5 seq=1232
/// other 4 events: queued=2 unit-finished=2
/// queue 1/7 job=57 prio=5 owner=cto sha=0cf5631 ref=main waiting=12m
/// queue 2/7 job=58 UNKNOWN prio=3 owner=v2174 sha=0cf5631 waiting=3m port-group=waiting for=2m
/// queue 3/7 job=59 prio=3 owner=cto waiting=1m BLOCKED port-group 1/1 on bb
/// queue 5 more not listed
/// host bb link=UP state=UP cap=5 reactors=3 foreign=1 active=2
/// host cloudbb-3 link=CONNECTING state=LOST cap=3 reactors=0 foreign=0 active=0 LOST(link-down since=2026-10-16T09:40:00Z) retire-at=2026-11-13T00:00:00Z shared-cpu
/// dump latest FAILED ended=1h05m-ago detail=pg_dump rc=1
/// dump last-good ended=25h00m-ago
/// more events waiting: run temper digest again (HTTP: since=1232.1760608790123456)
/// ```
///
/// A finished line carries the record's `KNOWN(#n)` annotations after the counts, as the event line does (ruling R-M2-14),
/// then the job's user properties and narrowing options as [UserPropertyDto#token] (`tests-skipped="-DskipTests=true"`,
/// value always quoted and escaped, m4-design §5.1).
///
/// The `other` events are counted per kind, not listed (the JSON lists them): they are what a job passes through
/// between `queued` and `finished`, and the line keeps the window visibly whole. A job's state is printed only when it
/// is not QUEUED. Ages are taken against `asOf`. A blocked job ends its queue line with `BLOCKED` and its reasons,
/// `<reason> <detail> on <host>` joined by `, ` (still one line).
public sealed interface DigestText {
    /// Queued jobs listed in the text form. With it the text is at most `11 + max + hosts` lines: the header, a gap,
    /// `max` event lines (finished, started and the `other` count together never exceed the events shown), 5 queue
    /// lines and a count of the rest, one line per host, two dump lines and the more-waiting line; at the default
    /// `max` of 10 that is 21 plus one per host.
    int QUEUE_LINES = 5;

    static String render(DigestView view) {
        return Stream.of(Stream.of(header(view)),
                         view.gap().map(DigestText::gap).stream(),
                         view.finished().stream().map(DigestText::finished),
                         view.started().stream().map(DigestText::started),
                         others(view.other()).stream(),
                         queue(view),
                         view.hosts().stream().map(DigestText::host),
                         dumps(view.maintenance(),
                               view.asOf()),
                         moreWaiting(view).stream())
                     .flatMap(lines -> lines)
                     .collect(Collectors.joining("\n"));
    }

    private static String header(DigestView view) {
        return "digest cursor=" + view.cursor() + " more=" + view.more() + " asOf=" + view.asOf();
    }

    private static String gap(DigestView.Gap gap) {
        return "gap " + gap.from() + ".." + gap.to() + " deleted by retention";
    }

    private static String finished(DigestView.Finished event) {
        return line(Stream.of(Stream.of(event.kind().wire(),
                                        job(event.job()),
                                        verdict(event.verdict(), event.reason())),
                              sha(event.sha()).stream(),
                              token("ref", event.ref()).stream(),
                              token("owner", event.owner()).stream(),
                              token("prio", event.prio()).stream(),
                              event.counts().map(DigestText::counts).stream(),
                              event.known().stream(),
                              event.userProperties().stream().map(UserPropertyDto::token),
                              Stream.of("seq=" + event.seq())));
    }

    private static String started(DigestView.Started event) {
        return line(Stream.of(Stream.of("started", job(event.job())),
                              token("unit", event.unit()).stream(),
                              sha(event.sha()).stream(),
                              token("ref", event.ref()).stream(),
                              token("owner", event.owner()).stream(),
                              token("prio", event.prio()).stream(),
                              Stream.of("seq=" + event.seq())));
    }

    /// `other N events: kind=count …`, kinds in the order they first occur.
    private static Option<String> others(List<DigestView.Other> other) {
        return other.isEmpty()
               ? Option.none()
               : Option.some("other " + other.size() + " events: " + perKind(other));
    }

    private static String perKind(List<DigestView.Other> other) {
        return other.stream()
                    .collect(Collectors.groupingBy(event -> event.kind()
                                                                 .wire(),
                                                   LinkedHashMap::new,
                                                   Collectors.counting()))
                    .entrySet()
                    .stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(" "));
    }

    /// At most [#QUEUE_LINES] jobs, then one line counting the rest (the JSON lists up to 100, `queued` counts all).
    private static Stream<String> queue(DigestView view) {
        return view.queue()
                   .isEmpty()
               ? Stream.of("queue empty")
               : Stream.concat(view.queue()
                                   .stream()
                                   .limit(QUEUE_LINES)
                                   .map(queued -> queued(queued,
                                                         view.queued(),
                                                         view.asOf())),
                               notListed(view).stream());
    }

    private static Option<String> notListed(DigestView view) {
        var listed = Math.min(QUEUE_LINES,
                              view.queue().size());

        return view.queued() > listed
               ? Option.some("queue " + (view.queued() - listed) + " more not listed")
               : Option.none();
    }

    /// The last line when the digest stopped at `max`: the next read continues after this cursor.
    private static Option<String> moreWaiting(DigestView view) {
        return view.more()
               ? Option.some("more events waiting: run temper digest again (HTTP: since=" + view.cursor() + ")")
               : Option.none();
    }

    private static String queued(DigestView.Queued queued, int total, Instant asOf) {
        return line(Stream.of(Stream.of("queue " + queued.pos() + "/" + total, "job=" + queued.job()),
                              shownState(queued.state()).stream(),
                              Stream.of("prio=" + queued.prio(), "owner=" + queued.owner()),
                              sha(queued.sha()).stream(),
                              token("ref", queued.ref()).stream(),
                              Stream.of("waiting=" + age(queued.waitingSince(), asOf)),
                              token("port-group", queued.portGroup()).stream(),
                              queued.portGroupSince().map(since -> "for=" + age(since, asOf)).stream(),
                              blocked(queued.blocked()).stream()));
    }

    private static Option<String> blocked(List<DigestView.Blocked> blocked) {
        return blocked.isEmpty()
               ? Option.none()
               : Option.some("BLOCKED " + blocked.stream().map(DigestText::reason).collect(Collectors.joining(", ")));
    }

    private static String reason(DigestView.Blocked blocked) {
        return blocked.reason() + " " + blocked.detail() + " on " + blocked.host();
    }

    private static Option<String> shownState(JobState state) {
        return state == JobState.QUEUED
               ? Option.none()
               : Option.some(state.name());
    }

    private static String host(DigestView.Host host) {
        return line(Stream.of(Stream.of("host " + host.name()
                                       + " link=" + host.link()
                                       + " state=" + host.state()
                                                         .or("-")
                                       + " cap=" + host.cap()
                                       + " reactors=" + host.reactors()
                                       + " foreign=" + host.foreign()
                                       + " active=" + host.active()),
                              host.lost().map(DigestText::lost).stream(),
                              retirement(host).stream(),
                              host.sharedCpu()
                              ? Stream.of("shared-cpu")
                              : Stream.empty()));
    }

    private static String lost(HostLossDto lost) {
        return "LOST(" + lost.reason() + " since=" + lost.at() + ")";
    }

    /// `retired since=<instant>` once `retireAt` has passed (the digest's skip reason and detail), `retire-at=<instant>`
    /// before.
    private static Option<String> retirement(DigestView.Host host) {
        return host.retireAt()
                   .map(at -> host.retired()
                              ? "retired since=" + at
                              : "retire-at=" + at);
    }

    private static Stream<String> dumps(MaintenanceView maintenance, Instant asOf) {
        return maintenance.latestDump()
                          .map(latest -> Stream.of(latestDump(latest, asOf),
                                                   lastGood(maintenance.lastGoodDumpEndedAt(),
                                                            asOf)))
                          .or(() -> Stream.of("dump none"));
    }

    private static String latestDump(MaintenanceRunDto run, Instant asOf) {
        return line(Stream.of(Stream.of("dump latest",
                                        run.ok()
                                        ? "ok"
                                        : "FAILED",
                                        "ended=" + age(run.endedAt(), asOf) + "-ago"),
                              run.detail().map(detail -> "detail=" + oneLine(detail)).stream()));
    }

    private static String lastGood(Option<Instant> endedAt, Instant asOf) {
        return endedAt.map(ended -> "dump last-good ended=" + age(ended, asOf) + "-ago")
                      .or("dump last-good none");
    }

    private static String verdict(Option<String> verdict, Option<String> reason) {
        return verdict.or("-") + reason.map(text -> "(" + text + ")")
                                       .or("");
    }

    private static String counts(CountsDto counts) {
        return "testcases=" + counts.testcases() + " failed=" + counts.failed();
    }

    private static String job(Option<Long> job) {
        return "job=" + job.map(String::valueOf)
                           .or("-");
    }

    private static Option<String> sha(Option<String> sha) {
        return sha.map(full -> "sha=" + full.substring(0,
                                                       Math.min(7, full.length())));
    }

    private static Option<String> token(String key, Option<?> value) {
        return value.map(present -> key + "=" + present);
    }

    private static String line(Stream<Stream<String>> parts) {
        return parts.flatMap(part -> part)
                    .collect(Collectors.joining(" "));
    }

    private static String oneLine(String text) {
        return String.join(" ",
                           text.strip().split("\\s*\\R\\s*"));
    }

    /// `45s`, `12m`, `3h05m`, `2d04h`; a time after `asOf` (clock skew) is `0s`.
    private static String age(Instant since, Instant asOf) {
        var seconds = Math.max(0,
                               Duration.between(since, asOf).toSeconds());

        return seconds < 60
               ? seconds + "s"
               : seconds < 3600
                 ? seconds / 60 + "m"
                 : seconds < 86_400
                   ? seconds / 3600 + "h" + twoDigits(seconds % 3600 / 60) + "m"
                   : seconds / 86_400 + "d" + twoDigits(seconds % 86_400 / 3600) + "h";
    }

    private static String twoDigits(long value) {
        return value < 10
               ? "0" + value
               : String.valueOf(value);
    }

    record unused() implements DigestText {}
}
