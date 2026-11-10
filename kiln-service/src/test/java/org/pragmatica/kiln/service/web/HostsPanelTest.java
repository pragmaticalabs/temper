package org.pragmatica.kiln.service.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.core.temper.HostsRead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.PERIODS;
import static org.pragmatica.kiln.service.web.WebFixtures.ago;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The display, rows S1.18 (panel shows the rows), S1.19 (last rows marked stale with
/// their age, kiln flags nothing) and S1.21 (stale after 3 periods).
class HostsPanelTest {
    private static final HostsRead.HostRow ROW = new HostsRead.HostRow("bigboy",
                                                                       "ok",
                                                                       some(ago(70)),
                                                                       some(NOW.plus(Duration.ofDays(3)).plusSeconds(600)),
                                                                       some(4),
                                                                       List.of(new HostsRead.AlarmRow("queue-wait", some("t-7"), some(75), none(), none()),
                                                                               new HostsRead.AlarmRow("host-retiring", none(), none(), some(3), none()),
                                                                               new HostsRead.AlarmRow("clock-skew", none(), none(), none(), some(31))));

    private static DashboardView view(SourceState state, Instant success, long replyAgo) {
        var base = WebFixtures.fresh();
        var statuses = base.sources().statuses().stream().filter(status -> ! Source.HOSTS.equals(status.source())).toList();
        var sources = new Sources(java.util.stream.Stream.concat(statuses.stream(),
                                                                 java.util.stream.Stream.of(new SourceStatus(Source.HOSTS, state, some(success))))
                                                         .toList(),
                                  PERIODS);

        return new DashboardView(sources, base.bases(), base.hosts(), base.budget(), base.skipped(), base.passes(), base.prs(), base.runs(),
                                 base.counts(), base.listed(), base.mac(), base.nonGit(), base.digest(), base.work(),
                                 some(Observed.observed(new HostsRead.Answered(List.of(ROW)), ago(replyAgo))));
    }

    /// S1.18 (panel half): the rows, one per host, with retire in days, skew and each alarm in the contract's shape.
    @Test
    void panel_showsEachHostWithItsAlarms() {
        var html = HostsPanel.render(view(new SourceState.Ok(ago(30)), ago(30), 30), NOW);

        assertThat(html).contains("<b><bdi>bigboy</bdi></b> <bdi>ok</bdi>")
                        .contains("(in 3 d)")
                        .contains("skew 4s")
                        .contains("<bdi>queue-wait</bdi> <bdi>t-7</bdi> 75m<")
                        .contains("<bdi>host-retiring</bdi> 3d<")
                        .contains("<bdi>clock-skew</bdi> 31s<")
                        .doesNotContain("STALE")
                        .doesNotContain("stale");
    }

    /// S1.19 (panel half): temper down keeps the last rows, marked stale with their age, and the state with its `since`.
    @Test
    void panel_keepsTheLastRows_markedStaleWithAge_whenTemperIsDown() {
        var html = HostsPanel.render(view(new SourceState.TemperDown("HTTP 404", ago(60)), ago(300), 300), NOW);

        assertThat(html).contains("<b><bdi>bigboy</bdi></b> <bdi>ok</bdi>")
                        .contains("TEMPER-DOWN(<bdi>HTTP 404</bdi>) since")
                        .contains("rows observed 5m ago")
                        .contains("class=\"hosts stale\"")
                        .contains("STALE");
        assertThat(Html.marked(html)).isTrue();
    }

    /// Every temper-supplied string is escaped: no raw `<script` reaches the page.
    @Test
    void panel_escapesHostileText_inHostStatusKindAndTicket() {
        var evil = "<script>alert(1)</script>\"&'";
        var row = new HostsRead.HostRow(evil, evil, none(), none(), none(),
                                        List.of(new HostsRead.AlarmRow(evil, some(evil), none(), none(), none())));
        var base = view(new SourceState.Ok(ago(30)), ago(30), 30);
        var hostile = new DashboardView(base.sources(), base.bases(), base.hosts(), base.budget(), base.skipped(), base.passes(),
                                        base.prs(), base.runs(), base.counts(), base.listed(), base.mac(), base.nonGit(), base.digest(),
                                        base.work(), some(Observed.observed(new HostsRead.Answered(List.of(row)), ago(30))));
        var html = HostsPanel.render(hostile, NOW);

        assertThat(html).doesNotContain("<script")
                        .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html.split("&lt;script&gt;", -1).length - 1).isEqualTo(4);
    }

    @Test
    void panel_readsNever_beforeTheFirstReply() {
        var base = WebFixtures.fresh();
        var html = HostsPanel.render(base, NOW);

        assertThat(html).contains("as of never").contains("no fleet alarms read yet");
    }

    /// S1.21: a last success 181 s ago with a 60 s period is stale; 180 s is not (3 periods).
    @Test
    void source_isStale_after181SecondsOfA60SecondPeriod() {
        assertThat(PERIODS.hosts()).isEqualTo(Duration.ofSeconds(60));
        assertThat(sourcesAt(180).stale(Source.HOSTS, NOW)).isFalse();
        assertThat(sourcesAt(181).stale(Source.HOSTS, NOW)).isTrue();
    }

    private static Sources sourcesAt(long secondsAgo) {
        return new Sources(List.of(new SourceStatus(Source.HOSTS, new SourceState.Ok(ago(secondsAgo)), some(ago(secondsAgo)))), PERIODS);
    }
}
