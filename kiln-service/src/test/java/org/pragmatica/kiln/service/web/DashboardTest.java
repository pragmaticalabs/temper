package org.pragmatica.kiln.service.web;

import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.WorkBoard;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.kiln.core.model.Observed.observed;
import static org.pragmatica.kiln.service.web.WebFixtures.BIGBOY;
import static org.pragmatica.kiln.service.web.WebFixtures.CI;
import static org.pragmatica.kiln.service.web.WebFixtures.CLOUD;
import static org.pragmatica.kiln.service.web.WebFixtures.LINT;
import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.PERIODS;
import static org.pragmatica.kiln.service.web.WebFixtures.ago;
import static org.pragmatica.kiln.service.web.WebFixtures.cell;
import static org.pragmatica.kiln.service.web.WebFixtures.degraded;
import static org.pragmatica.kiln.service.web.WebFixtures.fresh;
import static org.pragmatica.kiln.service.web.WebFixtures.ok;
import static org.pragmatica.kiln.service.web.WebFixtures.pr101;
import static org.pragmatica.kiln.service.web.WebFixtures.scanned;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The dashboard's rules, each pinned by name: what may be green, the stale-red rule, labels, the Mac in full, counts
/// that never read 0, and the order of the PRs. Each test names its positive and negative control.
class DashboardTest {
    private static final String GREEN = "class=\"cell ok\"";

    private static DashboardView withPrs(DashboardView view, List<PrView> prs) {
        return new DashboardView(view.sources(),
                                 view.bases(),
                                 view.hosts(),
                                 view.budget(),
                                 view.skipped(),
                                 view.passes(),
                                 prs,
                                 view.runs(),
                                 view.counts(),
                                 view.listed(),
                                 view.mac(),
                                 view.nonGit(),
                                 view.digest(),
                                 WorkBoard.EMPTY);
    }

    private static DashboardView withSources(DashboardView view, Sources sources) {
        return new DashboardView(sources,
                                 view.bases(),
                                 view.hosts(),
                                 view.budget(),
                                 view.skipped(),
                                 view.passes(),
                                 view.prs(),
                                 view.runs(),
                                 view.counts(),
                                 view.listed(),
                                 view.mac(),
                                 view.nonGit(),
                                 view.digest(),
                                 WorkBoard.EMPTY);
    }

    private static DashboardView withMac(DashboardView view, List<CheckoutView> mac) {
        return new DashboardView(view.sources(),
                                 view.bases(),
                                 view.hosts(),
                                 view.budget(),
                                 view.skipped(),
                                 view.passes(),
                                 view.prs(),
                                 view.runs(),
                                 view.counts(),
                                 view.listed(),
                                 mac,
                                 view.nonGit(),
                                 view.digest(),
                                 WorkBoard.EMPTY);
    }

    private static PrView withRequired(PrView pr, RequiredChecks required) {
        return new PrView(pr.pr(),
                          pr.detail(),
                          pr.behind(),
                          some(observed(required, ago(30))),
                          List.of(cell(CI, "success", 30)),
                          pr.gate(),
                          pr.checkouts());
    }

    private static String prsOf(PrView pr) {
        return Dashboard.prs(withPrs(fresh(), List.of(pr)), NOW);
    }

    @Nested
    class Green {
        /// Positive control: a fresh `success` under REQUIRED is green. Negative: `success*` beside it is not (K-7).
        @Test
        void cell_successStar_isNeverGreen() {
            var html = prsOf(pr101(0));

            assertThat(html).contains("<span class=\"cell ok\" title=\"ci: success · shown: success · observed 30s ago\">✓ <bdi>ci</bdi></span>")
                      .contains("<span class=\"cell\" title=\"ci@15368: success* · shown: success · observed 30s ago\">… <bdi>ci@15368</bdi> <bdi>success*</bdi></span>")
                      .doesNotContain("<span class=\"cell ok\" title=\"ci@15368");
        }

        /// NONE-REQUIRED is grey and never green, even over a cell whose value is `success`.
        @Test
        void required_noneRequired_isGreyNeverGreen() {
            var html = prsOf(withRequired(pr101(0), new RequiredChecks.NoneRequired("off", false)));

            assertThat(html).contains("<span class=\"grey\">NONE-REQUIRED(<bdi>off</bdi>)</span>")
                      .contains("<span class=\"cell\" title=\"ci: success · observed 30s ago\">? <bdi>ci</bdi> <bdi>success</bdi></span>")
                      .doesNotContain(GREEN);
        }

        @Test
        void required_unknown_cellsNeverGreen() {
            var html = prsOf(withRequired(pr101(0), new RequiredChecks.Unknown("403")));

            assertThat(html).contains("<span class=\"grey\">UNKNOWN(<bdi>403</bdi>)</span>")
                      .contains("<span class=\"cell\" title=\"ci: success · observed 30s ago\">? <bdi>ci</bdi> <bdi>success</bdi></span>")
                      .doesNotContain(GREEN);
        }

        @Test
        void required_neverRead_cellsNeverGreen() {
            var pr = pr101(0);
            var html = prsOf(new PrView(pr.pr(),
                                        pr.detail(),
                                        pr.behind(),
                                        none(),
                                        List.of(cell(CI, "success", 30)),
                                        pr.gate(),
                                        pr.checkouts()));

            assertThat(html).contains("<span class=\"grey\">UNKNOWN(not read)</span>").doesNotContain(GREEN);
        }

        /// A success observed past 3 × the GitHub period is shown stale, not green; at the bound it is still green.
        @Test
        void cell_staleSuccess_isNotGreen() {
            var pr = pr101(0);
            var fresh = withRequired(pr,
                                     new RequiredChecks.Required(List.of(CI), "non_admins"));
            var atBound = new PrView(pr.pr(),
                                     pr.detail(),
                                     pr.behind(),
                                     fresh.required(),
                                     List.of(cell(CI, "success", 180)),
                                     pr.gate(),
                                     pr.checkouts());
            var stale = new PrView(pr.pr(),
                                   pr.detail(),
                                   pr.behind(),
                                   fresh.required(),
                                   List.of(cell(CI, "success", 181)),
                                   pr.gate(),
                                   pr.checkouts());

            assertThat(prsOf(atBound)).contains(GREEN);
            assertThat(prsOf(stale)).doesNotContain(GREEN)
                      .contains("<span class=\"cell stale\" title=\"ci: success · observed 3m ago\">? <bdi>ci</bdi> <bdi>success</bdi> <span class=\"flag\">STALE</span></span>");
        }

        /// A cell the list no longer requires is shown, marked, and never green, even when it reads `success`.
        @Test
        void cell_notRequired_shownNeverGreen() {
            var pr = pr101(0);
            var html = prsOf(new PrView(pr.pr(),
                                        pr.detail(),
                                        pr.behind(),
                                        some(observed(new RequiredChecks.Required(List.of(LINT), "non_admins"),
                                                      ago(30))),
                                        List.of(cell(LINT, "failure", 30), cell(CI, "success", 30)),
                                        pr.gate(),
                                        pr.checkouts()));

            assertThat(html).contains("<span class=\"cell fail\" title=\"lint: failure · observed 30s ago\">✗ <bdi>lint</bdi> <bdi>failure</bdi></span>",
                                      "<span class=\"cell\" title=\"ci: success · observed 30s ago\">? <bdi>ci</bdi> <bdi>success</bdi></span> (not required)")
                      .doesNotContain(GREEN);
        }

        @Test
        void required_missingCell_readsNoCell() {
            var pr = pr101(0);
            var html = prsOf(new PrView(pr.pr(),
                                        pr.detail(),
                                        pr.behind(),
                                        pr.required(),
                                        List.of(),
                                        pr.gate(),
                                        pr.checkouts()));

            assertThat(html).contains("<span class=\"cell\" title=\"ci: no cell\">… <bdi>ci</bdi> no cell</span>")
                      .contains("<span class=\"cell\" title=\"ci@15368: no cell\">… <bdi>ci@15368</bdi> no cell</span>")
                      .doesNotContain(GREEN);
        }
    }

    @Nested
    class Labels {
        /// A pinned check is labelled `ci@15368`; its key's U+001F never reaches the page.
        @Test
        void cell_labelledByLabel_neverRawKey() {
            var html = Dashboard.render(fresh(), NOW, none());

            assertThat(html).contains("<bdi>ci@15368</bdi> <bdi>success*</bdi>").doesNotContain("\u001F");
        }
    }

    @Nested
    class StaleRed {
        private static Sources githubAt(long secondsAgo) {
            return new Sources(List.of(ok(Source.GITHUB, secondsAgo)), PERIODS);
        }

        /// Red past 3 × 60 s (181 s); not red at exactly the bound (180 s).
        @Test
        void panel_sourceOlderThanThreePeriods_turnsRed() {
            assertThat(Dashboard.prs(withSources(fresh(),
                                                 githubAt(181)),
                                     NOW)).startsWith("<section id=\"prs\" class=\"panel red\">")
                      .contains("<h2>Open PRs <span class=\"flag\">STALE</span></h2>");
            assertThat(Dashboard.prs(withSources(fresh(), githubAt(180)), NOW)).startsWith("<section id=\"prs\" class=\"panel\">");
        }

        @Test
        void panel_sourceNeverRecorded_isRed() {
            assertThat(Dashboard.bases(withSources(fresh(),
                                                   new Sources(List.of(),
                                                               PERIODS)),
                                       NOW)).startsWith("<section id=\"bases\" class=\"panel red\">")
                      .contains("as of never");
        }

        @Test
        void everyPanel_hasAnAsOfLine() {
            var html = Dashboard.render(fresh(), NOW, none());

            assertThat(html.split("<section ", -1)).hasSize(10);
            assertThat(html.split("<p class=\"asof\">as of ", -1).length - 1).isGreaterThanOrEqualTo(8);
        }

        /// The degraded fixture: every panel but the F3 placeholder is red.
        @Test
        void degraded_everySourcedPanelIsRed() {
            var html = Dashboard.render(degraded(), NOW, none());

            assertThat(html).contains("<section id=\"sources\" class=\"panel red\">",
                                      "<section id=\"prs\" class=\"panel red\">",
                                      "<section id=\"bases\" class=\"panel red\">",
                                      "<section id=\"checkouts\" class=\"panel red\">",
                                      "<section id=\"temper\" class=\"panel red\">",
                                      "<section id=\"questions\" class=\"panel\">");
        }

        @Test
        void fresh_noPanelIsRed() {
            assertThat(String.join("\n",
                                   Dashboard.sections(fresh(), NOW).values())).doesNotContain("panel red",
                                                                                              "host red",
                                                                                              "<span class=\"flag\">",
                                                                                              " stale\"",
                                                                                              "class=\"stale\"");
        }

        /// An UNREACHABLE host's rows are kept and marked stale with their age; an INCOMPLETE host's too, though recent.
        @Test
        void failedHost_rowsMarkedStale() {
            var html = Dashboard.checkouts(degraded(), NOW);

            assertThat(html).contains("<tr class=\"stale\"><td><bdi>bigboy:/home/builder/work/co/kiln-wp10</bdi></td>");
            assertThat(Sources.sourceOf(CLOUD)).isEqualTo(Source.host(CLOUD));
            assertThat(degraded().sources().rowsStale(CLOUD, NOW)).isTrue();
            assertThat(fresh().sources().rowsStale(CLOUD, NOW)).isFalse();
        }

        @Test
        void temperDown_gateShowsTemperDown() {
            assertThat(Dashboard.prs(degraded(), NOW)).contains("TEMPER-DOWN; last: <span class=\"fact stale\"><bdi>PASS</bdi>, job 42, judge <bdi>temper</bdi>");
            assertThat(Dashboard.prs(fresh(),
                                     NOW)).doesNotContain("TEMPER-DOWN")
                      .contains("TEMPER-SCHEMA(<bdi>answer</bdi>)");
        }
    }

    @Nested
    class Mac {
        private static List<CheckoutView> macRows(int count) {
            return IntStream.range(0, count)
                            .mapToObj(index -> scanned(HostName.MAC,
                                                       "/Users/s/w/repo-" + index,
                                                       1_000 + index,
                                                       CheckoutStatus.values() [index % CheckoutStatus.values().length],
                                                       new Preservation.Preserved(),
                                                       PrLink.NONE,
                                                       none(),
                                                       some(1),
                                                       "/Users/s/w/repo-" + index + "/.git",
                                                       none()))
                            .toList();
        }

        /// 150 Mac rows of every status are all listed: no drill-down, no 100-row cut.
        @Test
        void macSection_shownInFull() {
            var html = Dashboard.checkouts(withMac(fresh(), macRows(150)), NOW);

            IntStream.range(0, 150).forEach(index -> assertThat(html).contains("<td><bdi>mac:/Users/s/w/repo-" + index
                                                                              + "</bdi></td>"));
            assertThat(html).contains("150 copies held only on the Mac (salvage refs in 150 repositories)");
        }

        /// With no Mac row and no Mac state, the section is still there, red, and says it never pushed.
        @Test
        void macSection_shownWithNoPush() {
            var html = Dashboard.checkouts(withMac(degraded(), List.of()), NOW);

            assertThat(html).contains("<div class=\"host mac red\">",
                                      "<h3>mac <span class=\"flag\">STALE</span></h3>",
                                      "<p>pushed never</p>",
                                      "<p>salvage refs: not reported</p>");
        }

        /// The Mac's rows are never sized (spec §5.4): its line says so instead of a 0 KiB total; a host shows its total.
        @Test
        void macCounts_sizeNotMeasured_neverZero() {
            var html = Dashboard.checkouts(fresh(), NOW);
            var mac = html.substring(html.indexOf("<div class=\"host mac"));

            assertThat(mac).contains("total 3 · size not measured · oldest fact").doesNotContain("known size");
            assertThat(html).contains("total 127 · known size ≥4.8 GiB (125 of 127 measured) · oldest fact");
        }

        /// A Mac repository whose scan reported no salvage count makes the total a lower bound, never "0 copies".
        @Test
        void salvage_notReported_neverZero() {
            var rows = List.of(scanned(HostName.MAC,
                                       "/Users/s/w/x",
                                       600,
                                       CheckoutStatus.ACTIVE,
                                       new Preservation.Preserved(),
                                       PrLink.NONE,
                                       none(),
                                       none(),
                                       "/Users/s/w/x/.git",
                                       none()));
            var html = Dashboard.checkouts(withMac(fresh(), rows), NOW);

            assertThat(html).contains("<p>≥0 copies held only on the Mac (1 repositories not reported)</p>")
                      .doesNotContain("<p>0 copies");
        }

        /// Two worktrees of one repository share its salvage refs: 131, not 262.
        @Test
        void salvage_countedOncePerRepository() {
            assertThat(Dashboard.checkouts(fresh(), NOW)).contains("131 copies held only on the Mac (salvage refs in 1 repositories)");
        }
    }

    @Nested
    class Counts {
        /// cloudbb-3 has no counts: it reads "no rows observed" and `?`, never 0. bigboy's line carries its oldest fact.
        @Test
        void hostWithoutCounts_neverReadsZero() {
            var html = Dashboard.checkouts(degraded(), NOW);
            var cloud = html.substring(html.indexOf("<h3>cloudbb-3"), html.indexOf("<div class=\"host mac"));

            assertThat(cloud).contains("no rows observed", "REMOVABLE ?", "BASE-CLEAN ?").doesNotContain(" 0");
            assertThat(html).contains("ACTIVE 1 · IN-USE 1 · REMOVABLE 120 · BASE-CLEAN 3 · NEEDS-SALVAGE 1 · STALE-UNOWNED 0 · "
                                     + "UNOWNED 0 · UNSCANNED 1 · total 127 · known size ≥4.8 GiB (125 of 127 measured) · oldest fact 2026-10-29T16:00:00Z");
        }

        /// Only ACTIVE, IN-USE, NEEDS-SALVAGE and UNSCANNED rows are listed; REMOVABLE is a drill-down.
        @Test
        void listedRows_onlyTheFourStatuses() {
            var html = Dashboard.checkouts(fresh(), NOW);

            assertThat(html).contains("bigboy:/home/builder/work/co/kiln-wp10",
                                      "bigboy:/home/builder/work/co/bb-temper",
                                      "bigboy:/home/builder/work/co/old-fix",
                                      "bigboy:/home/builder/work/co/new-one")
                      .doesNotContain("bigboy:/home/builder/work/co/done")
                      .contains("<a href=\"/checkouts?status=REMOVABLE&amp;host=bigboy&amp;page=1\">REMOVABLE 120</a>");
        }

        @Test
        void nonGit_countedPerRootWithLink() {
            assertThat(Dashboard.checkouts(fresh(), NOW)).contains("<p>2 non-git directories under <bdi>/home/builder/work/co</bdi> <a href=\"/non-git?host=bigboy&amp;root=%2Fhome%2Fbuilder%2Fwork%2Fco\">list</a></p>",
                                                                   "<p>1 non-git directories under <bdi>/tmp/scratch</bdi> ");
        }
    }

    @Nested
    class Order {
        /// Given 103, 101, 104, 102: rendered 102 (1 min), 101 (5 min), 103 (30 min), 104 (40 min).
        @Test
        void prs_sortedByLastActivity_newestFirst() {
            var html = Dashboard.prs(fresh(), NOW);

            assertThat(html.indexOf("#102 ")).isLessThan(html.indexOf("#101 "));
            assertThat(html.indexOf("#101 ")).isLessThan(html.indexOf("#103 "));
            assertThat(html.indexOf("#103 ")).isLessThan(html.indexOf("#104 "));
        }

        /// Equal activity: the higher number first.
        @Test
        void prs_equalActivity_higherNumberFirst() {
            var first = pr101(0);
            var twin = new PrView(observed(new PullRequest(WebFixtures.number(100),
                                                           "Twin",
                                                           first.pr().value().state(),
                                                           false,
                                                           first.pr().value().base(),
                                                           first.pr().value().headRef(),
                                                           Option.none(),
                                                           first.pr().value().head(),
                                                           none(),
                                                           none(),
                                                           first.pr().value().updatedAt(),
                                                           List.of(),
                                                           List.of(),
                                                           List.of()),
                                           first.pr().observedAt()),
                                  none(),
                                  none(),
                                  none(),
                                  List.of(),
                                  none(),
                                  List.of());
            var html = Dashboard.prs(withPrs(fresh(), List.of(twin, first)), NOW);

            assertThat(html.indexOf("#101 ")).isLessThan(html.indexOf("#100 "));
        }

        @Test
        void bases_configuredFirstThenSchedule() {
            var html = Dashboard.bases(fresh(), NOW);

            assertThat(html.indexOf("runs on <bdi>release-1.0.0-rc5")).isLessThan(html.indexOf("runs on <bdi>main"));
            assertThat(html.indexOf("runs on <bdi>main")).isLessThan(html.indexOf("scheduled runs"));
            assertThat(html).contains("<p>no runs observed</p>");
        }

        @Test
        void bases_empty_saysNoneObserved() {
            var view = fresh();
            var empty = new DashboardView(view.sources(),
                                          List.of(),
                                          view.hosts(),
                                          view.budget(),
                                          view.skipped(),
                                          view.passes(),
                                          view.prs(),
                                          new BaseView(List.of(), List.of()),
                                          view.counts(),
                                          view.listed(),
                                          view.mac(),
                                          view.nonGit(),
                                          view.digest(),
                                          WorkBoard.EMPTY);

            assertThat(Dashboard.bases(empty, NOW)).contains("scheduled runs</h3>\n<p>no runs observed</p>",
                                                             "<p>none observed</p>");
            assertThat(Dashboard.prs(withPrs(view, List.of()), NOW)).contains("<p>no open PR observed</p>");
        }
    }

    @Nested
    class Header {
        @Test
        void header_showsBudgetSkippedAndPasses() {
            var html = Dashboard.header(fresh(), NOW);

            assertThat(html).contains("rate budget 4990 left, resets 2026-10-30T12:40:00Z (in 40m)",
                                      "skipped #77: <bdi>base: invalid branch</bdi>",
                                      "hot pass: COMPLETE, 181 of 181 rows, finished 2026-10-30T11:58:20Z (1m ago)",
                                      "full pass: RUNNING 3 of 10, started 2026-10-30T11:00:00Z (1h 0m ago)",
                                      "<b>host:cloudbb-3</b>: OK",
                                      "hot pass: none recorded",
                                      "push: COMPLETE, 3 of 3 rows, finished 2026-10-30T11:00:00Z (1h 0m ago)");
        }

        @Test
        void header_everyStateSpelled() {
            var html = Dashboard.header(degraded(), NOW);

            assertThat(html).contains("RATE-LIMITED(reset 2026-10-30T12:40:00Z (in 40m)) since",
                                      "TEMPER-DOWN(<bdi>connect refused</bdi>) since",
                                      "UNREACHABLE(<bdi>ssh exit 255</bdi>) since",
                                      "INCOMPLETE(<bdi>count 3, rows 2</bdi>) at",
                                      "<b>mac</b>: no state recorded; last success never",
                                      "hot pass: UNREACHABLE, finished 2026-10-30T11:59:00Z (1m ago)",
                                      "hot pass: INCOMPLETE, finished",
                                      "push: none recorded")
                      .doesNotContain("UNREACHABLE, 0 rows", "INCOMPLETE, 2 rows");
        }

        @Test
        void hosts_unionOfConfiguredAndObserved_neverMac() {
            assertThat(Dashboard.hosts(fresh())).containsExactly(BIGBOY, CLOUD);
        }
    }
}
