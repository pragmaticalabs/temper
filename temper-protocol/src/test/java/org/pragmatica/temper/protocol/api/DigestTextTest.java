package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.ResultRecordDto.CountsDto;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [DigestText]: the exact lines, one fact each; and a JSON round trip of the [DigestView] it renders, so the CLI
/// renders the same text from what it decodes as the controller does from what it built.
class DigestTextTest {
    private static final Instant AS_OF = Instant.parse("2026-10-16T10:00:00Z");
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";
    private static final CountsDto COUNTS = new CountsDto(120, 117, 2, 0, 1, 120, 9, none(), none(), none());

    @Test
    void render_everySection_oneFactPerLine() {
        var view = DigestView.digestView("1232.1760608790123456",
                                         true,
                                         some(DigestView.Gap.gap(100, 200)),
                                         AS_OF,
                                         List.of(DigestView.Finished.finished(1230,
                                                                              AS_OF.minusSeconds(60),
                                                                              some(54L),
                                                                              EventKind.FINISHED,
                                                                              some("FAILED"),
                                                                              some("tests"),
                                                                              some(SHA),
                                                                              some("main"),
                                                                              some("cto"),
                                                                              some(5),
                                                                              some(COUNTS),
                                                                              List.of("KNOWN(#21)", "KNOWN(#1679)"),
                                                                              List.of(UserPropertyDto.userPropertyDto("-DskipTests=true",
                                                                                                                      some("skipped")),
                                                                                      UserPropertyDto.userPropertyDto("-Dtest=FooTest",
                                                                                                                      some("narrowed")),
                                                                                      UserPropertyDto.userPropertyDto("-DfailIfNoSpecifiedTests=false",
                                                                                                                      none()))),
                                                 DigestView.Finished.finished(1231,
                                                                              AS_OF,
                                                                              some(55L),
                                                                              EventKind.CANCELLED,
                                                                              some("CANCELLED"),
                                                                              none(),
                                                                              some(SHA),
                                                                              none(),
                                                                              some("cto"),
                                                                              some(5),
                                                                              none(),
                                                                              List.of(),
                                                                              List.of())),
                                         List.of(DigestView.Started.started(1232,
                                                                            AS_OF,
                                                                            some(56L),
                                                                            some("gate"),
                                                                            some(SHA),
                                                                            some("main"),
                                                                            some("cto"),
                                                                            some(5))),
                                         List.of(other(1225, EventKind.QUEUED),
                                                 other(1226, EventKind.UNIT_FINISHED),
                                                 other(1227, EventKind.QUEUED),
                                                 other(1228, EventKind.UNIT_FINISHED)),
                                         List.of(DigestView.Queued.queued(1,
                                                                          57,
                                                                          JobState.QUEUED,
                                                                          5,
                                                                          "cto",
                                                                          some(SHA),
                                                                          some("main"),
                                                                          AS_OF.minusSeconds(12 * 60 + 30),
                                                                          none(),
                                                                          none(),
                                                                          List.of()),
                                                 DigestView.Queued.queued(2,
                                                                          58,
                                                                          JobState.UNKNOWN,
                                                                          3,
                                                                          "v2174",
                                                                          some(SHA),
                                                                          none(),
                                                                          AS_OF.minusSeconds(3 * 3600 + 5 * 60),
                                                                          some("waiting"),
                                                                          some(AS_OF.minusSeconds(45)),
                                                                          List.of()),
                                                 DigestView.Queued.queued(3,
                                                                          59,
                                                                          JobState.QUEUED,
                                                                          3,
                                                                          "cto",
                                                                          none(),
                                                                          none(),
                                                                          AS_OF.minusSeconds(60),
                                                                          none(),
                                                                          none(),
                                                                          List.of(DigestView.Blocked.blocked("bb", "port-group", "1/1"),
                                                                                  DigestView.Blocked.blocked("bigboy",
                                                                                                             "no-link",
                                                                                                             "link=DOWN no-facts")))),
                                         7,
                                         List.of(DigestView.Host.host("bb", "UP", some("UP"), 5, 3, 1, 2),
                                                 DigestView.Host.host("bigboy", "DOWN", none(), 4, 0, 0, 0)),
                                         MaintenanceView.maintenanceView(some(MaintenanceRunDto.maintenanceRunDto("pg_dump",
                                                                                                                  AS_OF.minusSeconds(3600 + 6 * 60),
                                                                                                                  AS_OF.minusSeconds(3600 + 5 * 60),
                                                                                                                  false,
                                                                                                                  some("pg_dump rc=1\n  disk full"))),
                                                                         some(AS_OF.minusSeconds(2 * 86_400 + 3 * 3600))));

        assertThat(DigestText.render(view).lines().toList())
            .containsExactly("digest cursor=1232.1760608790123456 more=true asOf=2026-10-16T10:00:00Z",
                             "gap 100..200 deleted by retention",
                             "finished job=54 FAILED(tests) sha=0cf5631 ref=main owner=cto prio=5 testcases=120 failed=2 KNOWN(#21) KNOWN(#1679) tests-skipped=\"-DskipTests=true\" tests-narrowed=\"-Dtest=FooTest\" property=\"-DfailIfNoSpecifiedTests=false\" seq=1230",
                             "cancelled job=55 CANCELLED sha=0cf5631 owner=cto prio=5 seq=1231",
                             "started job=56 unit=gate sha=0cf5631 ref=main owner=cto prio=5 seq=1232",
                             "other 4 events: queued=2 unit-finished=2",
                             "queue 1/7 job=57 prio=5 owner=cto sha=0cf5631 ref=main waiting=12m",
                             "queue 2/7 job=58 UNKNOWN prio=3 owner=v2174 sha=0cf5631 waiting=3h05m port-group=waiting for=45s",
                             "queue 3/7 job=59 prio=3 owner=cto waiting=1m BLOCKED port-group 1/1 on bb, no-link link=DOWN no-facts on bigboy",
                             "queue 4 more not listed",
                             "host bb link=UP state=UP cap=5 reactors=3 foreign=1 active=2",
                             "host bigboy link=DOWN state=- cap=4 reactors=0 foreign=0 active=0",
                             "dump latest FAILED ended=1h05m-ago detail=pg_dump rc=1 disk full",
                             "dump last-good ended=2d03h-ago",
                             "more events waiting: run temper digest again (HTTP: since=1232.1760608790123456)");
        var mapper = TemperJson.mapper();
        var decoded = mapper.writeAsString(view)
                            .flatMap(json -> mapper.readString(json, DigestView.class))
                            .unwrap();

        assertThat(decoded).isEqualTo(view);
    }

    /// A controller older than D1 sends queue entries without `blocked`: a newer CLI reads them as not blocked.
    @Test
    void decode_queuedWithoutBlocked_readsAsNone() {
        var view = DigestView.digestView("9.-",
                                         false,
                                         none(),
                                         AS_OF,
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         List.of(DigestView.Queued.queued(1, 57, JobState.QUEUED, 5, "cto", none(), none(), AS_OF, none(), none(),
                                                                          List.of())),
                                         1,
                                         List.of(),
                                         MaintenanceView.maintenanceView(none(), none()));
        var mapper = TemperJson.mapper();
        var json = mapper.writeAsString(view).unwrap();
        var old = json.replace(",\"blocked\":[]", "");

        assertThat(old).doesNotContain("blocked");
        var decoded = mapper.readString(old, DigestView.class).unwrap();

        assertThat(decoded.queue().getFirst().blocked()).isEmpty();
        assertThat(decoded).isEqualTo(view);
    }

    /// Verify4 blocker 1 (the verifier's DigestTextUserPropertyLineTest, recreated): a stored value holding a newline,
    /// a quote, ESC or a bidi override (a legacy recipe, before R-EFFECT refused them) stays on its finished line,
    /// escaped and quoted, and forges no `finished` line of its own.
    @Test
    void render_userPropertyValue_cannotBreakOrForgeALine() {
        var forged = "-Dtest=Foo\nfinished job=99 PASSED seq=7";
        var view = DigestView.digestView("9.-",
                                         false,
                                         none(),
                                         AS_OF,
                                         List.of(DigestView.Finished.finished(8,
                                                                              AS_OF,
                                                                              some(54L),
                                                                              EventKind.FINISHED,
                                                                              some("PASSED"),
                                                                              none(),
                                                                              none(),
                                                                              none(),
                                                                              none(),
                                                                              none(),
                                                                              none(),
                                                                              List.of(),
                                                                              List.of(UserPropertyDto.userPropertyDto(forged, some("narrowed")),
                                                                                      UserPropertyDto.userPropertyDto("-Dgroups=a\"b\u001b[2J\u202ec\\",
                                                                                                                      none())))),
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         0,
                                         List.of(),
                                         MaintenanceView.maintenanceView(none(), none()));

        var lines = DigestText.render(view).lines().toList();

        assertThat(lines).hasSize(4);
        assertThat(lines.stream().filter(line -> line.startsWith("finished"))).containsExactly(
            "finished job=54 PASSED tests-narrowed=\"-Dtest=Foo\\nfinished job=99 PASSED seq=7\" property=\"-Dgroups=a\\\"b\\u001b[2J\\u202ec\\\\\" seq=8");
    }

    @Test
    void render_nothingNew_emptyQueue_noDump() {
        var view = DigestView.digestView("9.-",
                                         false,
                                         none(),
                                         AS_OF,
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         0,
                                         List.of(),
                                         MaintenanceView.maintenanceView(none(), none()));

        assertThat(DigestText.render(view).lines().toList()).containsExactly("digest cursor=9.- more=false asOf=2026-10-16T10:00:00Z",
                                                                             "queue empty",
                                                                             "dump none");
    }

    @Test
    void render_firstDumpFailed_lastGoodIsNone() {
        var view = DigestView.digestView("9.-",
                                         false,
                                         none(),
                                         AS_OF,
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         0,
                                         List.of(),
                                         MaintenanceView.maintenanceView(some(MaintenanceRunDto.maintenanceRunDto("pg_dump",
                                                                                                                  AS_OF.minusSeconds(50),
                                                                                                                  AS_OF.minusSeconds(40),
                                                                                                                  false,
                                                                                                                  none())),
                                                                         none()));

        assertThat(DigestText.render(view).lines().toList()).endsWith("dump latest FAILED ended=40s-ago", "dump last-good none");
    }

    /// M4 §11: a host line names its loss, its retirement (passed or ahead) and its CPU class.
    @Test
    void render_hostMarks_lostRetiredRetireAtSharedCpu() {
        var lostAt = AS_OF.minusSeconds(90);
        var retireAt = AS_OF.minusSeconds(600);
        var later = AS_OF.plusSeconds(3600);
        var hosts = List.of(DigestView.Host.host("bb",
                                                 "LOST",
                                                 some("DOWN"),
                                                 5,
                                                 0,
                                                 0,
                                                 1,
                                                 some(HostLossDto.hostLossDto(lostAt, "liveness")),
                                                 false,
                                                 none(),
                                                 false),
                            DigestView.Host.host("cloud", "UP", some("UP"), 2, 1, 0, 1, none(), true, some(retireAt), true),
                            DigestView.Host.host("spare", "UP", some("UP"), 2, 0, 0, 0, none(), false, some(later), false));
        var view = DigestView.digestView("9.-",
                                         false,
                                         none(),
                                         AS_OF,
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         0,
                                         hosts,
                                         MaintenanceView.maintenanceView(none(), none()));

        assertThat(DigestText.render(view).lines().filter(line -> line.startsWith("host")))
            .containsExactly("host bb link=LOST state=DOWN cap=5 reactors=0 foreign=0 active=1 LOST(liveness since=" + lostAt + ")",
                             "host cloud link=UP state=UP cap=2 reactors=1 foreign=0 active=1 retired since=" + retireAt + " shared-cpu",
                             "host spare link=UP state=UP cap=2 reactors=0 foreign=0 active=0 retire-at=" + later);
    }

    /// The worst case at the default `max`: a gap, 10 events (9 finished, 1 other), 100 queued jobs (the JSON's whole list), four hosts,
    /// a failed dump and `more`. The text stays at `11 + max + hosts` = 25 lines; the queue shows [DigestText#QUEUE_LINES]
    /// jobs and counts the other 95. Every job is blocked on every host with two reasons: the reasons stay on its line.
    @Test
    void render_worstCase_staysWithinTheDocumentedBound() {
        var finished = LongStream.range(0, 9)
                                 .mapToObj(seq -> DigestView.Finished.finished(seq, AS_OF, some(seq), EventKind.FINISHED, some("PASSED"),
                                                                               none(), some(SHA), some("main"), some("cto"), some(5),
                                                                               some(COUNTS), List.of(), List.of()))
                                 .toList();
        var queue = IntStream.rangeClosed(1, 100)
                             .mapToObj(pos -> DigestView.Queued.queued(pos, 100 + pos, JobState.QUEUED, 5, "cto", some(SHA), some("main"),
                                                                       AS_OF, none(), none(), blockedEverywhere()))
                             .toList();
        var hosts = IntStream.range(0, 4).mapToObj(index -> DigestView.Host.host("h" + index, "UP", some("UP"), 5, 0, 0, 0)).toList();
        var view = DigestView.digestView("10.1",
                                         true,
                                         some(DigestView.Gap.gap(1, 2)),
                                         AS_OF,
                                         finished,
                                         List.of(),
                                         List.of(other(11, EventKind.QUEUED)),
                                         queue,
                                         140,
                                         hosts,
                                         MaintenanceView.maintenanceView(some(MaintenanceRunDto.maintenanceRunDto("pg_dump", AS_OF, AS_OF, false,
                                                                                                                  some("rc=1"))),
                                                                         some(AS_OF)));
        var lines = DigestText.render(view).lines().toList();

        assertThat(lines).hasSizeLessThanOrEqualTo(25);
        assertThat(lines.stream().filter(line -> line.startsWith("queue ")).toList()).hasSize(DigestText.QUEUE_LINES + 1)
                                                                                    .last()
                                                                                    .isEqualTo("queue 135 more not listed");
        assertThat(lines.getLast()).startsWith("more events waiting");
        assertThat(lines.stream().filter(line -> line.startsWith("queue ") && line.contains(" BLOCKED memory 6144+6144>8192MB on h0, "))
                        .toList()).hasSize(DigestText.QUEUE_LINES);
    }

    private static List<DigestView.Blocked> blockedEverywhere() {
        return IntStream.range(0, 4)
                        .boxed()
                        .flatMap(index -> Stream.of(DigestView.Blocked.blocked("h" + index, "memory", "6144+6144>8192MB"),
                                                                     DigestView.Blocked.blocked("h" + index, "disk-floor", "free=1 floor=2")))
                        .toList();
    }

    private static DigestView.Other other(long seq, EventKind kind) {
        return DigestView.Other.other(seq, AS_OF, some(50L), kind, none(), none(), none());
    }
}
