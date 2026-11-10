package org.pragmatica.kiln.service.web;

import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.service.store.Page;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.WorkBoard;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.kiln.core.model.Observed.observed;
import static org.pragmatica.kiln.service.web.WebFixtures.BIGBOY;
import static org.pragmatica.kiln.service.web.WebFixtures.CI;
import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.PERIODS;
import static org.pragmatica.kiln.service.web.WebFixtures.ago;
import static org.pragmatica.kiln.service.web.WebFixtures.cell;
import static org.pragmatica.kiln.service.web.WebFixtures.degraded;
import static org.pragmatica.kiln.service.web.WebFixtures.fresh;
import static org.pragmatica.kiln.service.web.WebFixtures.pr101;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The tabbed restyle (owner request, 2026-11-01): one page with CSS-only tabs, a status strip on every page that is
/// never greener than the sources, tab counts, amber only for the active tab, and compact check cells that lose no fact.
class TabsTest {
    private static final String BIGBOY_RED = "<span class=\"badge error\"><b>host:bigboy</b> UNREACHABLE · 30m ago <span class=\"flag\">STALE</span></span>";

    private static String tabsOf(DashboardView view) {
        return Dashboard.tabs(view, NOW, "prs", Dashboard.sections(view, NOW));
    }

    private static String strip(String html) {
        return html.substring(html.indexOf("<nav class=\"strip\""),
                              html.indexOf("</nav>") + "</nav>".length());
    }

    @Nested
    class Strip {
        /// A stale source is red in the strip on the dashboard, on every tab (the strip is outside the tabbed main), on the
        /// drill-down page and on the non-git page. Control: the fresh fixture shows it green everywhere.
        @Test
        void redSource_redOnEveryPage() {
            var degraded = degraded();
            var tabs = Html.TABS.stream().map(id -> Dashboard.render(degraded, NOW, some(id))).toList();
            var pages = List.of(Dashboard.render(degraded, NOW, none()),
                                CheckoutPages.drillDown(CheckoutStatus.REMOVABLE,
                                                        some(BIGBOY),
                                                        new Page<>(List.of(), 1, 1),
                                                        degraded.hosts(),
                                                        degraded.sources(),
                                                        NOW),
                                CheckoutPages.nonGit(BIGBOY, "/w", List.of(), degraded.hosts(), degraded.sources(), NOW));

            tabs.forEach(page -> assertThat(strip(page)).contains(BIGBOY_RED));
            pages.forEach(page -> assertThat(strip(page)).contains(BIGBOY_RED));
            pages.forEach(page -> assertThat(page.indexOf("<nav class=\"strip\"")).isLessThan(page.indexOf("<main")));
            assertThat(strip(Dashboard.render(fresh(),
                                              NOW,
                                              none()))).contains("<span class=\"badge ok\"><b>host:bigboy</b> OK · 1m ago</span>")
                      .doesNotContain("badge error", "badge warn");
        }

        /// Every source has a badge, in the header's order.
        @Test
        void everySource_inOrder() {
            var strip = strip(Dashboard.render(fresh(), NOW, none()));

            assertThat(strip).containsSubsequence("<b>github</b>",
                                                  "<b>mirror</b>",
                                                  "<b>temper</b>",
                                                  "<b>host:bigboy</b>",
                                                  "<b>host:cloudbb-3</b>",
                                                  "<b>mac</b>");
        }

        /// ok only for OK and fresh; a failing state within its bound is warn; stale or never recorded is error.
        @Test
        void badgeLevel_neverGreener() {
            var degraded = degraded().sources();

            assertThat(Html.level(Source.host(WebFixtures.CLOUD), degraded, NOW)).isEqualTo("warn");
            assertThat(Html.level(Source.host(BIGBOY), degraded, NOW)).isEqualTo("error");
            assertThat(Html.level(Source.MAC, degraded, NOW)).isEqualTo("error");
            assertThat(Html.level(Source.GITHUB, fresh().sources(), NOW)).isEqualTo("ok");
            assertThat(Html.badge(Source.MAC, degraded, NOW)).isEqualTo("<span class=\"badge error\"><b>mac</b> NO STATE · never <span class=\"flag\">STALE</span></span>");
            assertThat(Html.badge(Source.host(WebFixtures.CLOUD), degraded, NOW)).isEqualTo("<span class=\"badge warn\"><b>host:cloudbb-3</b> INCOMPLETE · 1m ago</span>");
        }

        @Test
        void word_everyState() {
            assertThat(List.of(new SourceState.Ok(NOW),
                               new SourceState.Unreachable("r", NOW),
                               new SourceState.Incomplete("r", NOW),
                               new SourceState.RateLimited(NOW, NOW),
                               new SourceState.GithubDown("500", NOW),
                               new SourceState.TemperDown("r", NOW),
                               new SourceState.TemperSchema("f", NOW))
                           .stream()
                           .map(Html::word)
                           .toList()).containsExactly("OK",
                                                      "UNREACHABLE",
                                                      "INCOMPLETE",
                                                      "RATE-LIMITED",
                                                      "GITHUB-DOWN",
                                                      "TEMPER-DOWN",
                                                      "TEMPER-SCHEMA");
        }
    }

    @Nested
    class Tabs {
        @Test
        void tabs_countsInOrder() {
            var html = tabsOf(fresh());
            // Open PRs is flagged in the fresh fixture: #104's required list was never read, a red check area.
            assertThat(html).isEqualTo("<nav class=\"tabs\" aria-label=\"panels\">"
                                      + "<a class=\"chip active red\" href=\"/?tab=prs\">Open PRs<span class=\"count\">4</span> <span class=\"flag\">STALE</span></a> "
                                      + "<a class=\"chip\" href=\"/?tab=work\">Work<span class=\"count\">0</span></a> "
                                      + "<a class=\"chip\" href=\"/?tab=bases\">Base branches<span class=\"count warn\">1 issues</span></a> "
                                      + "<a class=\"chip\" href=\"/?tab=checkouts\">Checkouts<span class=\"count warn\">≥2 salvage</span></a> "
                                      + "<a class=\"chip\" href=\"/?tab=temper\">temper</a> "
                                      + "<a class=\"chip\" href=\"/?tab=pipeline\">Pipeline<span class=\"count\">0 / 0</span></a> "
                                      + "<a class=\"chip\" href=\"/?tab=conflicts\">Conflicts<span class=\"count\">0</span></a> "
                                      + "<a class=\"chip\" href=\"/?tab=questions\">Questions</a> "
                                      + "<a class=\"chip\" href=\"/?tab=sources\">Sources</a></nav>");
        }

        /// A red panel's tab is red and flagged STALE, so the active tab's amber ring never hides it.
        @Test
        void redPanels_redTabs() {
            var html = tabsOf(degraded());

            assertThat(html).contains("<a class=\"chip active red\" href=\"/?tab=prs\">Open PRs<span class=\"count\">4</span> <span class=\"flag\">STALE</span></a>",
                                      "<a class=\"chip red\" href=\"/?tab=checkouts\">",
                                      "<a class=\"chip red\" href=\"/?tab=temper\">temper <span class=\"flag\">STALE</span></a>",
                                      "<a class=\"chip\" href=\"/?tab=questions\">Questions</a>",
                                      "<a class=\"chip red\" href=\"/?tab=sources\">Sources<span class=\"count warn\">7 not OK</span>");
        }

        /// Every host and the Mac have counts: the salvage count is exact; with none at all it is `?`.
        @Test
        void salvageCount_exactAndUnknown() {
            var v = fresh();
            var complete = new DashboardView(v.sources(),
                                             v.bases(),
                                             List.of(BIGBOY),
                                             v.budget(),
                                             v.skipped(),
                                             v.passes(),
                                             v.prs(),
                                             v.runs(),
                                             v.counts(),
                                             v.listed(),
                                             v.mac(),
                                             v.nonGit(),
                                             v.digest(),
                                             WorkBoard.EMPTY);
            var none = new DashboardView(v.sources(),
                                         v.bases(),
                                         List.of(BIGBOY),
                                         v.budget(),
                                         v.skipped(),
                                         v.passes(),
                                         v.prs(),
                                         v.runs(),
                                         List.of(),
                                         v.listed(),
                                         v.mac(),
                                         v.nonGit(),
                                         v.digest(),
                                         WorkBoard.EMPTY);
            var onlyBigboy = new Sources(List.of(WebFixtures.ok(Source.host(BIGBOY), 10),
                                                 WebFixtures.ok(Source.MAC, 10)),
                                         PERIODS);

            assertThat(tabsOf(new DashboardView(onlyBigboy,
                                                v.bases(),
                                                List.of(BIGBOY),
                                                v.budget(),
                                                v.skipped(),
                                                List.of(),
                                                v.prs(),
                                                v.runs(),
                                                v.counts(),
                                                List.of(),
                                                v.mac(),
                                                v.nonGit(),
                                                v.digest(),
                                                WorkBoard.EMPTY))).contains("<span class=\"count warn\">2 salvage</span>");
            assertThat(tabsOf(none)).contains("<span class=\"count warn\">? salvage</span>");
            assertThat(tabsOf(complete)).contains("salvage");
        }

        /// The subpages link back to each tab of the dashboard.
        @Test
        void tabLinks_backToDashboard() {
            assertThat(Dashboard.tabLinks()).contains("<a class=\"chip\" href=\"/?tab=prs\">Open PRs</a>",
                                                      "<a class=\"chip\" href=\"/?tab=sources\">Sources</a>");
            assertThat(CheckoutPages.nonGit(BIGBOY, "/w", List.of(), fresh().hosts(), fresh().sources(), NOW)).contains("href=\"/?tab=checkouts\"");
        }

        /// One page, every section inside the tabbed main; the server names the open tab in `data-tab` and CSS shows only
        /// that section. Without a tab, Open PRs.
        @Test
        void css_serverTab_defaultPrs() {
            var html = Dashboard.render(fresh(), NOW, none());

            assertThat(html).contains("<main class=\"tabbed\" data-tab=\"prs\">\n<section id=\"sources\"",
                                      "<section id=\"questions\"",
                                      "main.tabbed>section.panel{display:none}",
                                      "main.tabbed[data-tab=\"prs\"]>section#prs,main.tabbed[data-tab=\"work\"]>section#work,"
                                     + "main.tabbed[data-tab=\"bases\"]>section#bases,",
                                      "main.tabbed[data-tab=\"sources\"]>section#sources{display:block}")
                      .containsPattern("<a class=\"chip active( red)?\" href=\"/\\?tab=prs\">")
                      .contains("<meta http-equiv=\"refresh\" content=\"30\">")
                      .doesNotContain(":target", "href=\"#");
            Html.TABS.forEach(id -> assertThat(html).contains("<section id=\"" + id + "\"", "href=\"/?tab=" + id + "\""));
        }

        /// Each allowlisted tab opens its section and makes its chip, and only it, active.
        @Test
        void eachTab_opensItsSection() {
            for (var id : Html.TABS) {
                var html = Dashboard.render(fresh(), NOW, some(id));

                Golden.write("tab-fresh-" + id, html);
                Golden.write("tab-degraded-" + id, Dashboard.render(degraded(), NOW, some(id)));
                assertThat(html).contains("<main class=\"tabbed\" data-tab=\"" + id + "\">")
                          .containsPattern("<a class=\"chip active( red)?\" href=\"/\\?tab=" + id + "\">");
                assertThat(html.split("chip active", -1)).hasSize(2);
            }
        }

        /// The request's text is never written back: an unknown or hostile tab opens Open PRs and appears nowhere.
        @Test
        void hostileTab_rendersPrs_reflectsNothing() {
            for (var raw : List.of("<script>alert(1)</script>", "prs\"x", "PRS", "prs ", "checkouts#x", "")) {
                var html = Dashboard.render(fresh(), NOW, some(raw));

                assertThat(html).as(raw)
                          .contains("<main class=\"tabbed\" data-tab=\"prs\">")
                          .containsPattern("<a class=\"chip active( red)?\" href=\"/\\?tab=prs\">")
                          .doesNotContain("<script",
                                          "&lt;script",
                                          "alert(1)",
                                          "prs\"x",
                                          "prs&quot;x",
                                          "data-tab=\"PRS",
                                          "checkouts#x");
            }

            assertThat(Dashboard.tab(none())).isEqualTo("prs");
            assertThat(Dashboard.tab(some("temper"))).isEqualTo("temper");
            assertThat(Dashboard.tab(some("<script>"))).isEqualTo("prs");
        }

        /// Amber is only "you are here": the one active-tab rule uses it, no status does.
        @Test
        void amber_onlyTheActiveTab() {
            var uses = Pattern.compile("var\\(--amber").matcher(Html.STYLE).results().count();

            assertThat(uses).isEqualTo(1);
            assertThat(Html.STYLE).contains(".chip.active{box-shadow:0 0 0 2px var(--amber) inset;font-weight:600;"
                                           + "text-decoration:underline 2px var(--ink);text-underline-offset:4px}",
                                            ".chip.red{border-color:var(--error)}");
            assertThat(Html.STYLE.substring(Html.STYLE.indexOf(".chip.active{"))).doesNotContain("border-color");
            assertThat(Html.STYLE).contains(".ok{color:var(--ok)}.warn{color:var(--warn)}.error{color:var(--error)}",
                                            "--ok:#126b33;--warn:#9a4507;--error:#b91c1c",
                                            "--ok:#4ade80;--warn:#fb923c;--error:#f87171",
                                            "@media (prefers-color-scheme:dark)")
                      .doesNotContain("http:",
                                      "https:",
                                      "@import",
                                      "url(");
        }
    }

    /// MAJOR-1 (verify of 864d15a1): a tab carries the worst mark inside its section, not only its panel's own red.
    @Nested
    class WorstMark {
        private static DashboardView withPrs(DashboardView v, List<PrView> prs) {
            return new DashboardView(v.sources(),
                                     v.bases(),
                                     v.hosts(),
                                     v.budget(),
                                     v.skipped(),
                                     v.passes(),
                                     prs,
                                     v.runs(),
                                     v.counts(),
                                     v.listed(),
                                     v.mac(),
                                     v.nonGit(),
                                     v.digest(),
                                     WorkBoard.EMPTY);
        }

        private static DashboardView withSources(DashboardView v, Sources sources) {
            return new DashboardView(sources,
                                     v.bases(),
                                     v.hosts(),
                                     v.budget(),
                                     v.skipped(),
                                     v.passes(),
                                     v.prs(),
                                     v.runs(),
                                     v.counts(),
                                     v.listed(),
                                     v.mac(),
                                     v.nonGit(),
                                     v.digest(),
                                     WorkBoard.EMPTY);
        }

        private static Sources replacing(Sources sources, SourceStatus replacement) {
            return new Sources(sources.statuses()
                                      .stream()
                                      .map(status -> status.source()
                                                           .equals(replacement.source())
                                                     ? replacement
                                                     : status)
                                      .toList(),
                               PERIODS);
        }

        private static PrView cleanPr(long cellAgo) {
            var pr = pr101(0);

            return new PrView(pr.pr(),
                              pr.detail(),
                              pr.behind(),
                              some(observed(new RequiredChecks.Required(List.of(CI), "non_admins"),
                                            ago(30))),
                              List.of(cell(CI, "success", cellAgo)),
                              pr.gate(),
                              pr.checkouts());
        }

        private static String chip(String tabs, String id) {
            var at = tabs.indexOf("href=\"/?tab=" + id + "\"");

            return tabs.substring(tabs.lastIndexOf("<a ", at), tabs.indexOf("</a>", at));
        }

        /// (a) GitHub fresh, one PR's cell 10 min stale: the Open PRs panel is not red, its tab is. Control: the same PR
        /// with a fresh cell leaves the tab plain.
        @Test
        void staleCell_freshGithub_flagsOpenPrs() {
            var stale = withPrs(fresh(), List.of(cleanPr(600)));
            var clean = withPrs(fresh(), List.of(cleanPr(30)));

            assertThat(Dashboard.prs(stale, NOW)).startsWith("<section id=\"prs\" class=\"panel\">");
            assertThat(chip(tabsOf(stale), "prs")).contains("chip active red", "<span class=\"flag\">STALE</span>");
            assertThat(chip(tabsOf(clean), "prs")).doesNotContain("red", "flag");
        }

        /// (b) bigboy's last pass UNREACHABLE with a fresh last success: its counts line is red, so the Checkouts tab is
        /// too, though the panel is not. Control: bigboy OK.
        @Test
        void failedHost_freshSuccess_flagsCheckouts() {
            var failed = withSources(fresh(),
                                     replacing(fresh().sources(),
                                               new SourceStatus(Source.host(BIGBOY),
                                                                new SourceState.Unreachable("ssh exit 255", ago(60)),
                                                                some(ago(60)))));

            assertThat(Dashboard.checkouts(failed, NOW)).startsWith("<section id=\"checkouts\" class=\"panel\">")
                      .contains("<p class=\"counts stale\">");
            assertThat(chip(tabsOf(failed), "checkouts")).contains("chip red", "<span class=\"flag\">STALE</span>");
            assertThat(chip(tabsOf(fresh()), "checkouts")).doesNotContain("chip red", "flag");
        }

        /// (c) temper fresh, its digest 10 min old: the temper tab is flagged. Control: a fresh digest.
        @Test
        void staleDigest_freshTemper_flagsTemper() {
            var v = fresh();
            var old = new DashboardView(v.sources(),
                                        v.bases(),
                                        v.hosts(),
                                        v.budget(),
                                        v.skipped(),
                                        v.passes(),
                                        v.prs(),
                                        v.runs(),
                                        v.counts(),
                                        v.listed(),
                                        v.mac(),
                                        v.nonGit(),
                                        some(observed("digest", ago(600))),
                                        WorkBoard.EMPTY);

            assertThat(Dashboard.temper(old, NOW)).startsWith("<section id=\"temper\" class=\"panel\">");
            assertThat(chip(tabsOf(old), "temper")).contains("chip red", "<span class=\"flag\">STALE</span>");
            assertThat(chip(tabsOf(v), "temper")).doesNotContain("red", "flag");
        }

        /// The structural guard, both ways, over every fixture and tab: a tab is flagged exactly when its section's markup
        /// holds a red or stale marker (detected here by its own list, not the renderer's), so a future mark is covered.
        @Test
        void everyTab_flaggedExactlyWhenItsSectionIsMarked() {
            var markers = List.of("class=\"red\"", " red\"", "class=\"stale\"", " stale\"", "class=\"flag\"");
            var fixtures = List.of(fresh(),
                                   degraded(),
                                   withPrs(fresh(), List.of(cleanPr(600))),
                                   withPrs(fresh(), List.of(cleanPr(30))),
                                   withSources(fresh(),
                                               replacing(fresh().sources(),
                                                         new SourceStatus(Source.host(BIGBOY),
                                                                          new SourceState.Incomplete("count", ago(60)),
                                                                          some(ago(60))))));

            for (var view : fixtures) {
                var sections = Dashboard.sections(view, NOW);
                var tabs = Dashboard.tabs(view, NOW, "questions", sections);

                for (var id : Html.TABS) {
                    var marked = markers.stream().anyMatch(sections.get(id)::contains);
                    var chip = chip(tabs, id);

                    assertThat(chip.contains(" red\"") && chip.contains("STALE")).as(id
                                                                                    + " in " + view.sources().statuses())
                              .isEqualTo(marked);
                }
            }
        }
    }

    /// MAJOR-2: a configured host never read shows red in the strip of /non-git and /checkouts too.
    @Test
    void neverReadConfiguredHost_redOnSubpages() {
        var zeta = HostName.hostName("zeta").unwrap();
        var configured = List.of(BIGBOY, zeta);
        var red = "<span class=\"badge error\"><b>host:zeta</b> NO STATE · never <span class=\"flag\">STALE</span></span>";

        assertThat(strip(CheckoutPages.nonGit(BIGBOY, "/w", List.of(), configured, fresh().sources(), NOW))).contains(red);
        assertThat(strip(CheckoutPages.drillDown(CheckoutStatus.REMOVABLE,
                                                 some(BIGBOY),
                                                 new Page<>(List.of(), 1, 1),
                                                 configured,
                                                 fresh().sources(),
                                                 NOW))).contains(red);
    }

    /// MINOR-1: the active tab keeps its red border: amber is an inset ring, not a border.
    @Test
    void activeRedTab_keepsRedBorder() {
        var tabs = tabsOf(degraded());

        assertThat(tabs).contains("<a class=\"chip active red\" href=\"/?tab=prs\">");
        assertThat(Html.STYLE.indexOf(".chip.red{border-color:var(--error)}")).isGreaterThan(-1);
        assertThat(Html.STYLE.substring(Html.STYLE.indexOf(".chip.active{"),
                                        Html.STYLE.indexOf("}", Html.STYLE.indexOf(".chip.active{")))).doesNotContain("border");
    }

    @Nested
    class Cells {
        private static String prsWith(String value) {
            var pr = pr101(0);

            return Dashboard.prs(new DashboardView(fresh().sources(),
                                                   fresh().bases(),
                                                   fresh().hosts(),
                                                   fresh().budget(),
                                                   fresh().skipped(),
                                                   fresh().passes(),
                                                   List.of(new PrView(pr.pr(),
                                                                      pr.detail(),
                                                                      pr.behind(),
                                                                      some(observed(new RequiredChecks.Required(List.of(CI),
                                                                                                                "x"),
                                                                                    ago(30))),
                                                                      List.of(cell(CI, value, 30)),
                                                                      pr.gate(),
                                                                      pr.checkouts())),
                                                   fresh().runs(),
                                                   fresh().counts(),
                                                   fresh().listed(),
                                                   fresh().mac(),
                                                   fresh().nonGit(),
                                                   fresh().digest(),
                                                   WorkBoard.EMPTY),
                                 NOW);
        }

        /// ✓ only for exactly `success`; ✗ for the failure family (value shown); … for the rest (value shown). The title
        /// keeps the whole fact.
        @Test
        void glyphs_andValueShown() {
            assertThat(prsWith("success")).contains("<span class=\"cell ok\" title=\"ci: success · observed 30s ago\">✓ <bdi>ci</bdi></span>");
            assertThat(prsWith("cancelled")).contains("<span class=\"cell fail\" title=\"ci: cancelled · observed 30s ago\">✗ <bdi>ci</bdi> <bdi>cancelled</bdi></span>");
            assertThat(prsWith("in_progress")).contains("<span class=\"cell\" title=\"ci: in_progress · observed 30s ago\">… <bdi>ci</bdi> <bdi>in_progress</bdi></span>");
            assertThat(prsWith("failure")).contains("<span class=\"cell fail\" title=\"ci: failure · observed 30s ago\">✗ <bdi>ci</bdi> <bdi>failure</bdi></span>");
            assertThat(prsWith("Success")).contains("<span class=\"cell\" title=\"ci: Success · observed 30s ago\">… <bdi>ci</bdi> <bdi>Success</bdi></span>");
        }

        /// The cells sit on one wrapping line under the list's state, and foreign runs stay visible.
        @Test
        void cells_oneRow_foreignVisible() {
            var html = Dashboard.prs(fresh(), NOW);

            assertThat(html).contains("<div class=\"cells\"><span class=\"cell ok\"",
                                      "(foreign 1)</span></div>",
                                      "title=\"lint: failure (foreign 1) · shown: failure, success · observed 30s ago\"");
        }
    }

    /// A source failing within its bound (warn) is never shown as OK: the control for [Strip#badgeLevel_neverGreener].
    @Test
    void warn_isNotOk() {
        var sources = new Sources(List.of(new SourceStatus(Source.TEMPER,
                                                           new SourceState.TemperSchema("answer", ago(10)),
                                                           some(ago(10)))),
                                  PERIODS);

        assertThat(Html.badge(Source.TEMPER, sources, NOW)).isEqualTo("<span class=\"badge warn\"><b>temper</b> TEMPER-SCHEMA · 10s ago</span>");
    }
}
