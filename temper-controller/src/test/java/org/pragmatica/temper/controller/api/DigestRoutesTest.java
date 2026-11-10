package org.pragmatica.temper.controller.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.digest.Digest;
import org.pragmatica.temper.controller.events.EventLines;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.policy.Fit;
import org.pragmatica.temper.controller.policy.GrantExplain;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.api.DigestText;
import org.pragmatica.temper.protocol.api.DigestView;
import org.pragmatica.temper.protocol.api.HostLossDto;
import org.pragmatica.temper.protocol.api.UserPropertyDto;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.EventCursor;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventWindow;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// D1 through the real [DigestRoutes] and [DigestViews]: a blocked job's reasons reach the JSON (decodable by the
/// current [TemperJson]) and its text line, and every reason has its documented wire name and detail.
class DigestRoutesTest {
    private static final String TOKEN = "s3cret-token";
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final HostName BB = HostName.hostName("bb").unwrap();

    @Test
    void getDigest_blockedJob_reasonInJsonAndText_textWithinItsBound() {
        var router = router(response(List.of(new Digest.Blocked(BB, new Fit.PortGroup(1, 1)))));

        var json = ApiFixture.call(router, HttpMethod.GET, "/api/v1/digest", "", Map.of("Authorization", "Bearer " + TOKEN));
        var text = ApiFixture.call(router, HttpMethod.GET, "/api/v1/digest?format=text", "", Map.of("Authorization", "Bearer " + TOKEN));

        assertThat(json.status().code()).isEqualTo(200);
        assertThat(json.bodyText()).contains("\"blocked\":[{\"host\":\"bb\",\"reason\":\"port-group\",\"detail\":\"1/1\"}]");
        var view = TemperJson.mapper().readString(json.bodyText(), DigestView.class).unwrap();

        assertThat(view.queue().getFirst().blocked()).containsExactly(DigestView.Blocked.blocked("bb", "port-group", "1/1"));
        var lines = text.bodyText().lines().toList();

        assertThat(lines).filteredOn(line -> line.startsWith("queue 1/1 job=54 "))
                         .singleElement()
                         .asString()
                         .endsWith(" BLOCKED port-group 1/1 on bb");
        assertThat(lines.size()).as("11 + max + hosts").isLessThanOrEqualTo(11 + 10 + 0);
        assertThat(DigestText.render(view)).isEqualTo(text.bodyText());
    }

    @Test
    void digestView_everyReason_hasItsWireNameAndDetail() {
        var since = Instant.parse("2026-10-16T09:00:00Z");
        var reasons = List.<GrantExplain.Reason>of(new GrantExplain.Capped(5, 5, 1),
                                                   new Fit.Memory(6144, 4096, 8192),
                                                   new Fit.MemFloor(7168, 1024, 4096, 6144),
                                                   new Fit.DiskFloor(10, 20),
                                                   new Fit.PortGroup(3, 2),
                                                   new GrantExplain.StarvationHold(12, since),
                                                   new GrantExplain.NoLink("DOWN", false),
                                                   new GrantExplain.NoLink("UP", true),
                                                   new GrantExplain.Busy(),
                                                   new GrantExplain.Unknown(Causes.cause("store down\n  timed out")),
                                                   new GrantExplain.NotRead(200),
                                                   new GrantExplain.Retired(since),
                                                   new GrantExplain.Lost("host-key", since),
                                                   new GrantExplain.SharedCpu());

        var view = DigestViews.digestView(response(reasons.stream().map(reason -> new Digest.Blocked(BB, reason)).toList()),
                                          LinkTiming.DEFAULT.startBound(),
                                          Set.of());

        assertThat(view.queue().getFirst().blocked()).containsExactly(DigestView.Blocked.blocked("bb", "cap", "5/5 foreign=1"),
                                                                      DigestView.Blocked.blocked("bb", "memory", "6144+4096>8192MB"),
                                                                      DigestView.Blocked.blocked("bb",
                                                                                                 "mem-floor",
                                                                                                 "free=7168 pending=1024 unit=4096 floor=6144MB"),
                                                                      DigestView.Blocked.blocked("bb", "disk-floor", "free=10 floor=20"),
                                                                      DigestView.Blocked.blocked("bb", "port-group", "3/2"),
                                                                      DigestView.Blocked.blocked("bb",
                                                                                                 "starvation-hold",
                                                                                                 "head=12 since=2026-10-16T09:00:00Z"),
                                                                      DigestView.Blocked.blocked("bb", "no-link", "link=DOWN no-facts"),
                                                                      DigestView.Blocked.blocked("bb", "no-link", "link=UP"),
                                                                      DigestView.Blocked.blocked("bb", "busy", "round-in-flight"),
                                                                      DigestView.Blocked.blocked("bb", "unknown", "active-read-failed"),
                                                                      DigestView.Blocked.blocked("bb", "not-read", "limit=200"),
                                                                      DigestView.Blocked.blocked("bb", "retired", "since=2026-10-16T09:00:00Z"),
                                                                      DigestView.Blocked.blocked("bb",
                                                                                                 "lost",
                                                                                                 "host-key since=2026-10-16T09:00:00Z"),
                                                                      DigestView.Blocked.blocked("bb", "shared-cpu", "dedicated-cpu"));
    }

    /// m4-design §11: a host's LOST mark, CPU class and `retireAt` reach the digest, `retired` judged at `asOf` (at the
    /// instant itself it is retired, one ms before it is not), and the text line shows them.
    @Test
    void digestView_hostMarks_lostRetiredSharedCpu() {
        var lostAt = Instant.parse("2026-10-16T09:40:00Z");
        var hosts = List.of(new Digest.Host(HostName.hostName("cloudbb-3").unwrap(), "CONNECTING", some("LOST"), 3, 0, 0,
                                            Digest.Marks.marks(some(HostLoss.hostLoss(lostAt, "link-down")), true, some(NOW))),
                            new Digest.Host(HostName.hostName("cloudbb-4").unwrap(), "UP", some("UP"), 3, 0, 1,
                                            Digest.Marks.marks(none(), true, some(NOW.plusMillis(1)))),
                            new Digest.Host(BB, "UP", some("UP"), 5, 1, 2, Digest.Marks.NONE));

        var view = DigestViews.digestView(response(List.of(), hosts), LinkTiming.DEFAULT.startBound(), Set.of());

        assertThat(view.hosts()).containsExactly(DigestView.Host.host("cloudbb-3", "CONNECTING", some("LOST"), 3, 0, 0, 0,
                                                                      some(HostLossDto.hostLossDto(lostAt, "link-down")), true, some(NOW),
                                                                      true),
                                                 DigestView.Host.host("cloudbb-4", "UP", some("UP"), 3, 1, 0, 1, none(), true,
                                                                      some(NOW.plusMillis(1)), false),
                                                 DigestView.Host.host("bb", "UP", some("UP"), 5, 3, 1, 2));
        assertThat(DigestText.render(view).lines().filter(line -> line.startsWith("host ")).toList())
            .containsExactly("host cloudbb-3 link=CONNECTING state=LOST cap=3 reactors=0 foreign=0 active=0 "
                             + "LOST(link-down since=2026-10-16T09:40:00Z) retired since=2026-10-16T10:00:00Z shared-cpu",
                             "host cloudbb-4 link=UP state=UP cap=3 reactors=1 foreign=0 active=1 retire-at=2026-10-16T10:00:00.001Z shared-cpu",
                             "host bb link=UP state=UP cap=5 reactors=3 foreign=1 active=2");
    }

    /// Ruling R-M2-14: a finished or cancelled event's KNOWN annotations reach the digest's Finished view and its text line as the event
    /// line carries them: the same text, in the same order, after the counts.
    @Test
    void digest_finishedEvent_carriesKnownAsTheEventLineDoes() {
        var counts = Counts.counts(16234, 16190, 1, 0, 43, 13755, 2101, none(), none(), none());
        var event = EventRow.eventRow(88,
                                      NOW,
                                      some(JobId.jobId(4711).unwrap()),
                                      EventRow.Kind.FINISHED,
                                      EventPayload.eventPayload(some("0cf563192ad5"), none(), some("gate"), none(), some("FAILED"),
                                                                some("tests"), some(counts), none(), List.of("KNOWN(#21)", "KNOWN(#1679)")));
        var cancelled = EventRow.eventRow(89,
                                          NOW,
                                          some(JobId.jobId(4712).unwrap()),
                                          EventRow.Kind.CANCELLED,
                                          EventPayload.eventPayload(some("0cf563192ad5"), none(), some("gate"), none(), some("CANCELLED"),
                                                                    none(), some(counts), none(), List.of("KNOWN(#7)")));
        var response = new Digest.Response(EventWindow.eventWindow(some(EventCursor.noEvent(0)), 0, 0, none(), List.of(), 10).unwrap(),
                                           List.of(new Digest.JobEvent(event, none()), new Digest.JobEvent(cancelled, none())),
                                           List.of(),
                                           List.of(),
                                           List.of(),
                                           0,
                                           List.of(),
                                           none(),
                                           none(),
                                           NOW);

        var view = DigestViews.digestView(response, LinkTiming.DEFAULT.startBound(), Set.of());
        var eventLine = EventLines.render(event, List.of());
        var digestLine = DigestText.render(view).lines().filter(line -> line.startsWith("finished ")).findFirst().orElseThrow();

        assertThat(view.finished().getFirst().known()).containsExactly("KNOWN(#21)", "KNOWN(#1679)");
        assertThat(view.finished().get(1).known()).as("a cancelled event too").containsExactly("KNOWN(#7)");
        assertThat(DigestText.render(view)).contains("KNOWN(#7) seq=89");
        var cancelledLine = DigestText.render(view).lines().filter(line -> line.startsWith("cancelled ")).findFirst().orElseThrow();

        assertThat(fromCounts(cancelledLine)).as("a cancelled event's digest line ends as its event line does")
                                             .isEqualTo(fromCounts(EventLines.render(cancelled, List.of())));
        assertThat(fromCounts(digestLine)).isEqualTo(fromCounts(eventLine));
        assertThat(eventLine).contains("failed=1 KNOWN(#21) KNOWN(#1679) seq=88");
        assertThat(digestLine).endsWith("failed=1 KNOWN(#21) KNOWN(#1679) seq=88");
    }

    /// maintainer ruling Q-M4-11c: a finished job's user `-D` properties reach the digest, skip and select ones marked, from the
    /// job's stored recipe; a job without one shows none.
    @Test
    void digest_finishedEvent_showsTheJobsUserProperties() {
        var skipped = EventRow.eventRow(90,
                                        NOW,
                                        some(JobId.jobId(4713).unwrap()),
                                        EventRow.Kind.FINISHED,
                                        EventPayload.eventPayload(some("0cf563192ad5"), none(), some("main"), none(), some("PASSED"),
                                                                  none(), none(), none(), List.of()));
        var plain = EventRow.eventRow(91,
                                      NOW,
                                      some(JobId.jobId(4714).unwrap()),
                                      EventRow.Kind.FINISHED,
                                      EventPayload.eventPayload(some("0cf563192ad5"), none(), some("main"), none(), some("PASSED"),
                                                                none(), none(), none(), List.of()));
        var response = new Digest.Response(EventWindow.eventWindow(some(EventCursor.noEvent(0)), 0, 0, none(), List.of(), 10).unwrap(),
                                           List.of(new Digest.JobEvent(skipped, some(mvnJob(4713, "-DskipTests", "-Dtest=FooTest", "install"))),
                                                   new Digest.JobEvent(plain, some(mvnJob(4714, "install")))),
                                           List.of(),
                                           List.of(),
                                           List.of(),
                                           0,
                                           List.of(),
                                           none(),
                                           none(),
                                           NOW);

        var view = DigestViews.digestView(response, LinkTiming.DEFAULT.startBound(), Set.of());
        var text = DigestText.render(view);

        assertThat(view.finished().getFirst().userProperties())
            .containsExactly(UserPropertyDto.userPropertyDto("-DskipTests=true", some("skipped")),
                             UserPropertyDto.userPropertyDto("-Dtest=FooTest", some("narrowed")));
        assertThat(view.finished().get(1).userProperties()).isEmpty();
        assertThat(text).contains("tests-skipped=\"-DskipTests=true\" tests-narrowed=\"-Dtest=FooTest\" seq=90");
        assertThat(text.lines().filter(line -> line.contains("seq=91")).findFirst().orElseThrow()).doesNotContain("tests-");
    }

    private static JobRow mvnJob(long id, String... args) {
        return JobRow.jobRow(JobId.jobId(id).unwrap(), OwnerTag.ownerTag("v2174").unwrap(), NOW, -20, false, false, false, none(),
                             some(Sha.sha("a".repeat(40)).unwrap()), Recipe.mvn(List.of(args)).unwrap(), none(), JobState.PASSED,
                             none(), none(), false, none(), none(), none(), some("PASSED"), none(), List.of(), List.of());
    }

    /// The tail both lines share: the counts, the KNOWN annotations and `seq`.
    private static String fromCounts(String line) {
        return line.substring(line.indexOf("testcases="));
    }

    private static Digest.Response response(List<Digest.Blocked> blocked) {
        return response(blocked, List.of());
    }

    private static Digest.Response response(List<Digest.Blocked> blocked, List<Digest.Host> hosts) {
        var job = Rows.job(54, JobState.QUEUED, List.of(Rows.unit(11, UnitState.QUEUED)));

        return new Digest.Response(EventWindow.eventWindow(some(EventCursor.noEvent(0)), 0, 0, none(), List.of(), 10).unwrap(),
                                   List.of(),
                                   List.of(),
                                   List.of(),
                                   List.of(new Digest.Queued(1, job, Option.none(), blocked)),
                                   1,
                                   hosts,
                                   none(),
                                   none(),
                                   NOW);
    }

    private static Router router(Digest.Response response) {
        var responses = Responses.responses(TemperJson.mapper());
        Digest digest = _ -> Promise.success(response);
        var routes = DigestRoutes.digestRoutes(responses,
                                               digest,
                                               HostLinks.hostLinks(List.of(), new FakeStores.Jobs()),
                                               LinkTiming.DEFAULT.startBound())
                                 .routes();

        return Router.router(Credentials.credentials(BearerToken.bearerToken(TOKEN).unwrap(), none()), routes, responses);
    }
}
