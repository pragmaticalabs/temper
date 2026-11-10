package org.pragmatica.kiln.service.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.Page;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassOutcome;
import org.pragmatica.kiln.service.store.PassView;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.WorkBoard;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.model.Observed.observed;
import static org.pragmatica.kiln.service.web.WebFixtures.BIGBOY;
import static org.pragmatica.kiln.service.web.WebFixtures.CI;
import static org.pragmatica.kiln.service.web.WebFixtures.CLOUD;
import static org.pragmatica.kiln.service.web.WebFixtures.MAIN;
import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.PERIODS;
import static org.pragmatica.kiln.service.web.WebFixtures.ago;
import static org.pragmatica.kiln.service.web.WebFixtures.cell;
import static org.pragmatica.kiln.service.web.WebFixtures.degraded;
import static org.pragmatica.kiln.service.web.WebFixtures.fresh;
import static org.pragmatica.kiln.service.web.WebFixtures.pr101;
import static org.pragmatica.kiln.service.web.WebFixtures.pullRequest;
import static org.pragmatica.kiln.service.web.WebFixtures.scanned;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The fix-round rulings (lead, WP10 verify), all "never greener or more complete than the facts". Each is pinned by the
/// verifier's attack (named by its id) and a control that shows the rule does not fire where it must not.
class RulingsTest {
    private static final String GREEN = "class=\"cell ok\"";

    private static DashboardView with(DashboardView v, Sources sources, List<PassView> passes, List<PrView> prs,
                                      List<HostCounts> counts, List<CheckoutView> mac) {
        return new DashboardView(sources, v.bases(), v.hosts(), v.budget(), v.skipped(), passes, prs, v.runs(), counts, v.listed(),
                                 mac, v.nonGit(), v.digest(), WorkBoard.EMPTY);
    }

    private static String prsOf(PrView pr) {
        var v = fresh();

        return Dashboard.prs(with(v, v.sources(), v.passes(), List.of(pr), v.counts(), v.mac()), NOW);
    }

    private static PrView requiredWith(long listAgo, Observed<Cell> cell) {
        var pr = pr101(0);

        return new PrView(pr.pr(), pr.detail(), pr.behind(),
                          some(observed(new RequiredChecks.Required(List.of(CI), "non_admins"), ago(listAgo))),
                          List.of(cell), pr.gate(), pr.checkouts());
    }

    private static Sources replacing(Sources sources, SourceStatus replacement) {
        return new Sources(sources.statuses()
                                  .stream()
                                  .map(status -> status.source().equals(replacement.source()) ? replacement : status)
                                  .toList(),
                           PERIODS);
    }

    private static CheckoutView macRow(String name, Option<Integer> salvage) {
        return scanned(HostName.MAC, "/Users/s/" + name, 600, CheckoutStatus.ACTIVE, new Preservation.Preserved(), PrLink.NONE,
                       some(MAIN), salvage, "/Users/s/" + name + "/.git", none());
    }

    @Nested
    class Salvage {
        /// z02: repo a reports 0, repo b reports nothing: a lower bound naming the unreported repository, never "0 copies".
        @Test
        void macSalvage_partlyUnreported_isLowerBound() {
            var v = fresh();
            var html = Dashboard.checkouts(with(v, v.sources(), v.passes(), v.prs(), v.counts(),
                                                List.of(macRow("a", some(0)), macRow("b", none()))), NOW);

            assertThat(html).contains("<p>≥0 copies held only on the Mac (1 repositories not reported)</p>")
                            .doesNotContain("<p>0 copies");
        }

        /// Control: every repository reported, so the plain total; a worktree that reports beside one that does not makes
        /// its repository reported.
        @Test
        void macSalvage_allReported_isExact() {
            var v = fresh();
            var worktree = scanned(HostName.MAC, "/Users/s/a-wt", 600, CheckoutStatus.ACTIVE, new Preservation.Preserved(),
                                   PrLink.NONE, some(MAIN), none(), "/Users/s/a/.git", none());
            var html = Dashboard.checkouts(with(v, v.sources(), v.passes(), v.prs(), v.counts(),
                                                List.of(macRow("a", some(7)), worktree, macRow("b", some(0)))), NOW);

            var mac = html.substring(html.indexOf("<div class=\"host mac"));

            assertThat(mac).contains("<p>7 copies held only on the Mac (salvage refs in 1 repositories)</p>")
                           .doesNotContain("≥", "not reported");
        }

        /// The reporting worktree may come after a silent one: its repository is still reported.
        @Test
        void macSalvage_reportingWorktreeSecond_isExact() {
            var v = fresh();
            var silent = scanned(HostName.MAC, "/Users/s/a-wt", 600, CheckoutStatus.ACTIVE, new Preservation.Preserved(),
                                 PrLink.NONE, some(MAIN), none(), "/Users/s/a/.git", none());
            var html = Dashboard.checkouts(with(v, v.sources(), v.passes(), v.prs(), v.counts(), List.of(silent, macRow("a", some(4)))),
                                           NOW);

            assertThat(html.substring(html.indexOf("<div class=\"host mac"))).contains(
                    "<p>4 copies held only on the Mac (salvage refs in 1 repositories)</p>");
        }
    }

    @Nested
    class RequiredListFreshness {
        /// g05: a fresh success under a list read 24 h ago is not green, and the check area is red with the list's age.
        @Test
        void staleRequiredList_freshSuccessCell_isNotGreen() {
            var html = prsOf(requiredWith(86_400, cell(CI, "success", 30)));

            assertThat(html).doesNotContain(GREEN)
                            .contains("<td class=\"red\"><span class=\"fact stale\">REQUIRED(1, <bdi>non_admins</bdi>) <small>(observed 24h 0m ago)</small>");
        }

        /// Control: the list at its bound (180 s) and the cell fresh: green, and the check area not red.
        @Test
        void freshRequiredList_freshSuccessCell_isGreen() {
            var html = prsOf(requiredWith(180, cell(CI, "success", 30)));

            assertThat(html).contains(GREEN)
                            .doesNotContain("<td class=\"red\">");
        }
    }

    @Nested
    class ClockSkew {
        /// g04: a success observed an hour in the future is not green, and its age says why.
        @Test
        void futureObservedSuccess_isNotGreen() {
            var html = prsOf(requiredWith(30, observed(new Cell(CI, "success", 0, List.of()), NOW.plusSeconds(3_600))));

            assertThat(html).doesNotContain(GREEN)
                            .contains("<span class=\"cell stale\" title=\"ci: success · observed in the future (clock skew)\">? <bdi>ci</bdi> <bdi>success</bdi> <span class=\"flag\">STALE</span></span>");
        }

        /// Control: 60 s ahead is within the skew allowed: still green.
        @Test
        void slightlyAhead_withinSkew_isGreen() {
            assertThat(prsOf(requiredWith(30, observed(new Cell(CI, "success", 0, List.of()), NOW.plusSeconds(60))))).contains(GREEN);
        }

        /// s01: a last success a day ahead turns the panel red; 60 s ahead does not.
        @Test
        void futureLastSuccess_panelRed() {
            var v = fresh();
            var ahead = replacing(v.sources(), new SourceStatus(Source.GITHUB, new SourceState.Ok(NOW.plus(Duration.ofDays(1))),
                                                                some(NOW.plus(Duration.ofDays(1)))));
            var near = replacing(v.sources(), new SourceStatus(Source.GITHUB, new SourceState.Ok(NOW.plusSeconds(60)),
                                                               some(NOW.plusSeconds(60))));

            assertThat(Dashboard.prs(with(v, ahead, v.passes(), v.prs(), v.counts(), v.mac()), NOW)).contains(
                    "<section id=\"prs\" class=\"panel red\">", "as of 2026-10-31T12:00:00Z (in the future (clock skew))");
            assertThat(Dashboard.prs(with(v, near, v.passes(), v.prs(), v.counts(), v.mac()), NOW)).contains(
                    "<section id=\"prs\" class=\"panel\">");
        }

        @Test
        void age_skewBoundary() {
            assertThat(Html.age(NOW.plusSeconds(60), NOW)).isEqualTo("in 1m");
            assertThat(Html.age(NOW.plusSeconds(61), NOW)).isEqualTo("in the future (clock skew)");
            assertThat(Html.stale(NOW.plusSeconds(60), NOW, Duration.ofSeconds(60))).isFalse();
            assertThat(Html.stale(NOW.plusSeconds(61), NOW, Duration.ofSeconds(60))).isTrue();
            assertThat(Html.deadline(NOW.plusSeconds(3_600), NOW)).isEqualTo("2026-10-30T13:00:00Z (in 1h 0m)");
        }
    }

    @Nested
    class FailedPasses {
        /// z01: an INCOMPLETE pass shows no row count; control: a COMPLETE one does.
        @Test
        void incompletePass_noRowCount() {
            var v = degraded();
            var passes = List.of(new PassView(CLOUD, PassKind.HOT, PassOutcome.INCOMPLETE, 0, some(3), ago(70), some(ago(60)), none(), none()),
                                 new PassView(BIGBOY, PassKind.HOT, PassOutcome.COMPLETE, 5, none(), ago(70), some(ago(60)), none(), none()));
            var html = Dashboard.header(with(v, v.sources(), passes, v.prs(), v.counts(), v.mac()), NOW);

            assertThat(html).contains("hot pass: INCOMPLETE, finished 2026-10-30T11:59:00Z (1m ago)",
                                      "hot pass: COMPLETE, 5 rows, finished")
                            .doesNotContain("INCOMPLETE, 0 rows");
        }

        /// WP9 open item 1: a full pass's row holds its last chunk, shown with `chunk k of N`, and a pass with its
        /// listing's own count shows `rows of expected`; a failed chunk shows its chunk and still no count.
        @Test
        void fullPass_showsItsChunk_andRowsOfExpected() {
            var v = fresh();
            var passes = List.of(new PassView(BIGBOY, PassKind.FULL, PassOutcome.COMPLETE, 100, some(100), ago(70), some(ago(60)), some(1), some(3)),
                                 new PassView(CLOUD, PassKind.FULL, PassOutcome.INCOMPLETE, 40, some(100), ago(70), some(ago(60)), some(2), some(3)));
            var html = Dashboard.header(with(v, v.sources(), passes, v.prs(), v.counts(), v.mac()), NOW);

            assertThat(html).contains("full pass: COMPLETE (chunk 1 of 3), 100 of 100 rows, finished",
                                      "full pass: INCOMPLETE (chunk 2 of 3), finished")
                            .doesNotContain("40 of 100");
        }
    }

    @Nested
    class Sizes {
        private static String countsOf(HostCounts counts) {
            var v = fresh();

            return Dashboard.checkouts(with(v, v.sources(), v.passes(), v.prs(), List.of(counts), v.mac()), NOW);
        }

        /// z03: nothing measured reads "size not measured", never 0 KiB.
        @Test
        void nothingMeasured_sizeNotMeasured() {
            assertThat(countsOf(new HostCounts(BIGBOY, Map.of(CheckoutStatus.ACTIVE, 5), 0, 0, some(ago(100))))).contains(
                    "total 5 · size not measured · oldest fact")
                                                                                                                .doesNotContain("0 KiB");
        }

        @Test
        void partlyMeasured_lowerBound() {
            assertThat(countsOf(new HostCounts(BIGBOY, Map.of(CheckoutStatus.ACTIVE, 5), 2_048, 2, some(ago(100))))).contains(
                    "total 5 · known size ≥2.0 MiB (2 of 5 measured) · oldest fact");
        }

        /// N1: more measured rows than rows is inconsistent, never an exact size.
        @Test
        void moreMeasuredThanRows_inconsistent() {
            assertThat(countsOf(new HostCounts(BIGBOY, Map.of(CheckoutStatus.ACTIVE, 5), 4_096, 6, some(ago(100))))).contains(
                    "total 5 · size inconsistent (6 measured of 5 rows) · oldest fact")
                                                                                                                  .doesNotContain("known size");
        }

        /// Control: every row measured, a plain total, even when it is 0 KiB (measured empty checkouts).
        @Test
        void allMeasured_exact() {
            assertThat(countsOf(new HostCounts(BIGBOY, Map.of(CheckoutStatus.ACTIVE, 5), 0, 5, some(ago(100))))).contains(
                    "total 5 · known size 0 KiB · oldest fact");
        }
    }

    @Nested
    class TemperSchema {
        /// z05: under TEMPER-SCHEMA the last answer carries the marker; control: temper OK, no marker.
        @Test
        void temperSchema_gateMarked() {
            var v = fresh();
            var pr = pr101(0);
            var gated = new PrView(pr.pr(), pr.detail(), pr.behind(), pr.required(), pr.cells(),
                                   some(observed(new GateRead.Answered("x", "PASS", none(), none()), ago(50))), pr.checkouts());
            var schema = replacing(v.sources(), new SourceStatus(Source.TEMPER, new SourceState.TemperSchema("answer", ago(30)),
                                                                 some(ago(30))));

            assertThat(Dashboard.prs(with(v, schema, v.passes(), List.of(gated), v.counts(), v.mac()), NOW)).contains(
                    "<td>TEMPER-SCHEMA(<bdi>answer</bdi>); last: <span class=\"fact\"><bdi>PASS</bdi>");
            assertThat(Dashboard.prs(with(v, v.sources(), v.passes(), List.of(gated), v.counts(), v.mac()), NOW)).contains(
                    "<td><span class=\"fact\"><bdi>PASS</bdi>");
        }
    }

    @Nested
    class DrillLine {
        private static String drillLineOf(String html, String host) {
            return html.lines()
                       .filter(line -> line.contains(">drill down: ") && line.contains("host=" + host + "&amp;"))
                       .findFirst()
                       .orElseThrow();
        }

        /// The drill-down line of an unreachable host carries the STALE flag (the line's own, not `STALE-UNOWNED`);
        /// control: a fresh host's does not.
        @Test
        void drillLine_staleHost_flagged() {
            var degraded = drillLineOf(Dashboard.checkouts(degraded(), NOW), "bigboy");
            var fresh = drillLineOf(Dashboard.checkouts(fresh(), NOW), "bigboy");

            assertThat(degraded).startsWith("<p class=\"stale\">drill down: ")
                                .endsWith("</a> <span class=\"flag\">STALE</span></p>");
            assertThat(fresh).startsWith("<p>drill down: ")
                             .endsWith("</a></p>")
                             .doesNotContain("class=\"flag\"");
        }
    }

    @Nested
    class DrillDownHosts {
        /// d01: an empty every-host page while the hosts are unreachable is red and lists each host's state.
        @Test
        void emptyPage_unreachableHosts_redWithStates() {
            var html = CheckoutPages.drillDown(CheckoutStatus.REMOVABLE, none(), new Page<>(List.of(), 1, 1), fresh().hosts(),
                                               degraded().sources(), NOW);

            assertThat(html).contains("<section id=\"drill-down\" class=\"panel red\">",
                                      "<li class=\"stale\"><b>bigboy</b>: UNREACHABLE(<bdi>ssh exit 255</bdi>) since",
                                      "<li><b>cloudbb-3</b>: INCOMPLETE(", "<li class=\"stale\"><b>mac</b>: no state recorded",
                                      "<p>no rows</p>");
        }

        /// Control: the same empty page with every host fresh is not red; the hosts come from the configuration, not the
        /// rows.
        @Test
        void emptyPage_freshHosts_notRed() {
            var html = CheckoutPages.drillDown(CheckoutStatus.REMOVABLE, none(), new Page<>(List.of(), 1, 1), List.of(BIGBOY, CLOUD),
                                               fresh().sources(), NOW);

            assertThat(html).contains("<section id=\"drill-down\" class=\"panel\">", "<li><b>bigboy</b>: OK",
                                      "<li><b>cloudbb-3</b>: OK", "<li><b>mac</b>: OK", "as of 2026-10-30T11:00:00Z");
        }

        @Test
        void everyHost_configuredAndStatedThenMac() {
            var sources = new Sources(List.of(WebFixtures.ok(Source.host(HostName.hostName("zeta").unwrap()), 10)), PERIODS);

            assertThat(CheckoutPages.everyHost(List.of(CLOUD, BIGBOY), sources)).containsExactly(
                    BIGBOY, CLOUD, HostName.hostName("zeta").unwrap(), HostName.MAC);
            assertThat(CheckoutPages.everyHost(List.of(HostName.MAC, BIGBOY), sources)).containsExactly(
                    BIGBOY, HostName.hostName("zeta").unwrap(), HostName.MAC);
        }
    }

    @Nested
    class Pager {
        private static String pageOf(int page, int pages) {
            return CheckoutPages.drillDown(CheckoutStatus.REMOVABLE, some(BIGBOY), new Page<>(List.of(), page, pages), fresh().hosts(),
                                           fresh().sources(), NOW);
        }

        /// d02: page 1000 of 3 is out of range and links back to the last page only.
        @Test
        void pastTheEnd_linksToLastPage() {
            assertThat(pageOf(1_000, 3)).contains("page 1000 of 3 (out of range) · <a href=\"/checkouts?status=REMOVABLE&amp;host=bigboy&amp;page=3\">previous</a> · <a href=\"/\">")
                                        .doesNotContain("page=999", ">next<");
        }

        @Test
        void beforeTheStart_linksToFirstPage() {
            assertThat(pageOf(0, 3)).contains("page 0 of 3 (out of range) · <a href=\"/checkouts?status=REMOVABLE&amp;host=bigboy&amp;page=1\">next</a>")
                                    .doesNotContain(">previous<");
        }

        /// Control: a page in range links both ways and is not marked.
        @Test
        void inRange_bothLinks() {
            assertThat(pageOf(2, 3)).contains("page 2 of 3 · <a href=\"/checkouts?status=REMOVABLE&amp;host=bigboy&amp;page=1\">previous</a> · <a href=\"/checkouts?status=REMOVABLE&amp;host=bigboy&amp;page=3\">next</a>")
                                    .doesNotContain("out of range");
        }
    }

    @Nested
    class BidiAndControls {
        private static PrView titled(String title) {
            var pr = pr101(0);

            return new PrView(observed(pullRequest(101, title, false, MAIN, 'a', 60, List.of(), List.of()), ago(30)), pr.detail(),
                              pr.behind(), pr.required(), pr.cells(), pr.gate(), pr.checkouts());
        }

        /// a03: a NUL never reaches the page; it is shown as U+FFFD. Control: a tab is kept.
        @Test
        void nul_replaced() {
            var html = prsOf(titled("a\u0000b\tc\u001Bd e"));

            assertThat(html).doesNotContain("\u0000", "\u001B", " ")
                            .contains("<bdi>a�b\tc�d�e</bdi>");
        }

        /// a04: a U+202E in a title is isolated in its own `<bdi>`, so it cannot reorder the text after it.
        @Test
        void bidiOverride_isolated() {
            assertThat(prsOf(titled("fix ‮gnp.exe"))).contains("#101 <bdi>fix ‮gnp.exe</bdi><br>base <bdi>main</bdi>");
        }

        /// The digest keeps its line breaks (control: `\n` is not replaced) inside its `<bdi>`.
        @Test
        void digest_keepsNewlines() {
            assertThat(Dashboard.temper(fresh(), NOW)).contains("<pre><bdi>temper digest\n  jobs: 3 &lt;running&gt; &amp; 1 queued\n</bdi></pre>");
        }

        /// N2: a lone high and a lone low surrogate become U+FFFD; a valid pair (U+1F600) is kept.
        @Test
        void loneSurrogates_replaced_pairKept() {
            assertThat(Html.escape("a\uD83Db\uDE00c\uD83D\uDE00")).isEqualTo("a\uFFFDb\uFFFDc\uD83D\uDE00");
            assertThat(prsOf(titled("x\uD800y"))).contains("<bdi>x\uFFFDy</bdi>")
                                                  .doesNotContain("\uD800");
        }

        /// N3: a CRLF digest round-trips inside its pre; a CR in a title is still replaced.
        @Test
        void digestCrlf_kept_titleCr_replaced() {
            var v = fresh();
            var view = new DashboardView(v.sources(), v.bases(), v.hosts(), v.budget(), v.skipped(), v.passes(), v.prs(), v.runs(),
                                         v.counts(), v.listed(), v.mac(), v.nonGit(), some(observed("a\r\nb\u0000\r\n", ago(10))), WorkBoard.EMPTY);

            assertThat(Dashboard.temper(view, NOW)).contains("<pre><bdi>a\r\nb\uFFFD\r\n</bdi></pre>");
            assertThat(prsOf(titled("a\rb"))).contains("<bdi>a\uFFFDb</bdi>")
                                              .doesNotContain("\r");
        }

        @Test
        void escape_controlsAndSeparators() {
            assertThat(Html.escape("\u0000\u0007\r\n\t\u007F\u0085  x")).isEqualTo("���\n\t����x");
            assertThat(Html.text("<a>")).isEqualTo("<bdi>&lt;a&gt;</bdi>");
        }
    }
}
