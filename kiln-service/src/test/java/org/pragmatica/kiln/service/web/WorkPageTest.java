package org.pragmatica.kiln.service.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.service.store.WorkBoard;
import org.pragmatica.kiln.service.store.WorkCard;
import org.pragmatica.kiln.service.store.WorkPhase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.web.Golden.assertGolden;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The Work tab ([WorkPage]): one column per phase in [WorkPhase] order, each card in its current phase's column, the
/// round as `r<k>`, both durations as [Html#span] writes them, every recorded text escaped, merged packages older than
/// 7 days hidden with their count, and the ledger's freshness stated.
class WorkPageTest {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Pattern COLUMN = Pattern.compile("<div class=\"column\" data-phase=\"([a-z-]+)\">");

    private static WorkCard card(String id, WorkPhase phase, long submittedAgo, long phaseAgo) {
        return new WorkCard(id, id.split("-", 2)[0], some("title " + id), NOW.minusSeconds(submittedAgo), phase, 2, some("coder" + id.length()),
                            some("verifier"), some("note of " + id), NOW.minusSeconds(phaseAgo), NOW.minusSeconds(phaseAgo));
    }

    private static WorkBoard board() {
        return new WorkBoard(List.of(card("T1-WP1", WorkPhase.DESIGN, 600, 600),
                                     card("T1-WP2", WorkPhase.CODING, 11_520, 2_400),
                                     card("T1-WP3", WorkPhase.VERIFY, 200_000, 45),
                                     card("T1-WP4", WorkPhase.FIXING, 3_600, 3_600),
                                     card("T1-WP5", WorkPhase.ACCEPTED, 7_200, 60),
                                     card("T2-WP-0T", WorkPhase.INTEGRATED, 7_200, 120),
                                     card("T2-WP-0K", WorkPhase.IN_PR, 7_200, 180),
                                     card("T2-WP-A1", WorkPhase.MERGED, 86_400, 3_600),
                                     card("T0-OLD", WorkPhase.MERGED, 1_000_000, 8 * 86_400),
                                     card("T2-WP-P", WorkPhase.PARKED, 7_200, 7_200)));
    }

    /// The text between a column's opening tag and the next column's.
    private static String column(String html, WorkPhase phase) {
        var start = html.indexOf("<div class=\"column\" data-phase=\"" + phase.label() + "\">");
        var next = html.indexOf("<div class=\"column\"", start + 1);

        return html.substring(start, next < 0 ? html.indexOf("</section>", start) : next);
    }

    @Test
    void columns_areEveryPhase_inOrder_parkedLast() {
        var html = WorkPage.render(board(), NOW, false);

        assertThat(COLUMN.matcher(html).results().map(match -> match.group(1)).toList())
            .containsExactly("design", "coding", "verify", "fixing", "accepted", "integrated", "in-pr", "merged", "parked");
    }

    @Test
    void eachCard_landsInItsPhasesColumn() {
        var html = WorkPage.render(board(), NOW, false);

        for (var card : board().cards()) {
            if (WorkPage.hidden(card, NOW)) {
                continue;
            }
            for (var phase : WorkPhase.values()) {
                assertThat(column(html, phase).contains("<b><bdi>" + card.id() + "</bdi></b>")).as(card.id() + " in " + phase).isEqualTo(phase == card.phase());
            }
        }
        assertThat(column(html, WorkPhase.MERGED)).contains("<span class=\"count\">1</span>");
        assertThat(column(html, WorkPhase.DESIGN)).contains("<span class=\"count\">1</span>");
    }

    @Test
    void card_showsRound_people_bothDurations_andTheNote() {
        var html = WorkPage.card(card("T1-WP2", WorkPhase.CODING, 11_520, 2_400), NOW);

        assertThat(html).isEqualTo("<div class=\"card\"><p><b><bdi>T1-WP2</bdi></b> <bdi>title T1-WP2</bdi></p>"
                                   + "<p><span class=\"round\">r2</span> · coder <bdi>coder6</bdi> · verifier <bdi>verifier</bdi></p>"
                                   + "<p><small>since submitted 3h 12m · in coding 40m</small></p>"
                                   + "<p><bdi>note of T1-WP2</bdi></p></div>");
    }

    /// A card with only the required facts shows no empty title, people or note.
    @Test
    void card_withoutOptionalFacts() {
        var bare = new WorkCard("T1", "T1", none(), NOW.minusSeconds(42), WorkPhase.DESIGN, 0, none(), none(), none(), NOW, NOW);

        assertThat(WorkPage.card(bare, NOW)).isEqualTo("<div class=\"card\"><p><b><bdi>T1</bdi></b></p><p><span class=\"round\">r0</span></p>"
                                                       + "<p><small>since submitted 42s · in design 0s</small></p></div>");
    }

    @Test
    void elapsed_isASpan_neverNegative() {
        assertThat(WorkPage.elapsed(NOW.minusSeconds(11_520), NOW)).isEqualTo("3h 12m");
        assertThat(WorkPage.elapsed(NOW.minusSeconds(59), NOW)).isEqualTo("59s");
        assertThat(WorkPage.elapsed(NOW.minus(Duration.ofDays(3)).minusSeconds(7_200), NOW)).isEqualTo("3d 2h");
        assertThat(WorkPage.elapsed(NOW.plusSeconds(30), NOW)).isEqualTo("0s");
        assertThat(WorkPage.elapsed(NOW.plusSeconds(61), NOW)).isEqualTo("(clock skew)");
    }

    /// Every recorded text is escaped and isolated; the note is free text.
    @Test
    void everyText_isEscaped() {
        var hostile = "<script>alert('x')</script>&\"‮\u0000";
        var card = new WorkCard("T1-WP5", "T1", some(hostile), NOW, WorkPhase.VERIFY, 1, some(hostile), some(hostile), some(hostile), NOW, NOW);
        var html = WorkPage.render(new WorkBoard(List.of(card)), NOW, false);

        assertThat(html).doesNotContain("<script", "alert('x')", "\u0000")
                        .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;&quot;‮�</bdi>");
        assertThat(Html.marked(html)).as("a recorded text never marks the tab red").isFalse();
    }

    /// Merged more than 7 days ago: hidden, counted, and shown again with `merged=all`; 7 days exactly still shows.
    @Test
    void oldMerged_isHidden_withItsCount() {
        var html = WorkPage.render(board(), NOW, false);
        var all = WorkPage.render(board(), NOW, true);

        assertThat(html).doesNotContain("T0-OLD")
                        .contains("<p class=\"grey\">1 merged more than 7 days ago hidden · <a href=\"/?tab=work&amp;merged=all\">show all</a></p>");
        assertThat(all).contains("<b><bdi>T0-OLD</bdi></b>").doesNotContain("hidden");
        assertThat(WorkPage.hidden(card("X", WorkPhase.MERGED, 0, 7 * 86_400), NOW)).isFalse();
        assertThat(WorkPage.hidden(card("X", WorkPhase.MERGED, 0, 7 * 86_400 + 1), NOW)).isTrue();
        assertThat(WorkPage.hidden(card("X", WorkPhase.PARKED, 0, 30 * 86_400), NOW)).isFalse();
    }

    /// Hiding runs from when the package entered `merged` (phaseSince), not from its last event: a note recorded an
    /// hour ago on a package merged 8 days ago does not bring it back, and a package merged an hour ago stays.
    @Test
    void hiding_usesPhaseSince_notTheLastEvent() {
        var noted = new WorkCard("T1-OLD", "T1", none(), NOW.minus(Duration.ofDays(9)), WorkPhase.MERGED, 3, none(), none(), some("late note"),
                                 NOW.minusSeconds(3_600), NOW.minus(Duration.ofDays(8)));
        var fresh = new WorkCard("T1-NEW", "T1", none(), NOW.minus(Duration.ofDays(9)), WorkPhase.MERGED, 3, none(), none(), none(),
                                 NOW.minusSeconds(3_600), NOW.minusSeconds(3_600));

        assertThat(WorkPage.hidden(noted, NOW)).isTrue();
        assertThat(WorkPage.hidden(fresh, NOW)).isFalse();
        assertThat(WorkPage.render(new WorkBoard(List.of(noted, fresh)), NOW, false)).doesNotContain("T1-OLD").contains("T1-NEW");
    }

    @Test
    void freshness_isTheLastEvent() {
        assertThat(WorkPage.render(board(), NOW, false)).contains("<p class=\"asof\">as of 2026-11-03T11:59:15Z (45s ago): the last ledger event</p>");
        assertThat(WorkPage.render(WorkBoard.EMPTY, NOW, false)).contains("<p class=\"asof\">as of never: no ledger event recorded</p>\n<div class=\"board\">");
    }

    /// The tab counts the packages in flight: not merged, not parked.
    @Test
    void inFlight_countsNeitherMergedNorParked() {
        assertThat(WorkPage.inFlight(board())).isEqualTo(7);
        assertThat(WorkPage.inFlight(WorkBoard.EMPTY)).isZero();
    }

    /// The whole dashboard on the Work tab, for a reviewer: the board inside the page shell.
    @Test
    void golden_workTab() {
        var v = WebFixtures.fresh();
        var view = new DashboardView(v.sources(), v.bases(), v.hosts(), v.budget(), v.skipped(), v.passes(), v.prs(), v.runs(), v.counts(),
                                     v.listed(), v.mac(), v.nonGit(), v.digest(), board());

        assertGolden("work-board", WorkPage.render(board(), NOW, false));
        Golden.write("tab-work", Dashboard.render(view, NOW, some("work")));
        assertThat(Dashboard.render(view, NOW, some("work"))).contains("<a class=\"chip active\" href=\"/?tab=work\">Work<span class=\"count\">7</span></a>");
    }
}
