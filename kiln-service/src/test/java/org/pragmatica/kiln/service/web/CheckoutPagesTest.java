package org.pragmatica.kiln.service.web;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.web.WebFixtures.BIGBOY;
import static org.pragmatica.kiln.service.web.WebFixtures.CLOUD;
import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.degraded;
import static org.pragmatica.kiln.service.web.WebFixtures.fresh;
import static org.pragmatica.kiln.service.web.WebFixtures.scanned;
import static org.pragmatica.kiln.service.web.WebFixtures.unscanned;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;

/// The drill-down page: 100 rows, idle-first, with sizes and paging; the status a row files under.
class CheckoutPagesTest {
    private static CheckoutView removable(String name, long idle, Long kb) {
        return scanned(BIGBOY, "/w/" + name, idle, CheckoutStatus.REMOVABLE, new Preservation.Preserved(), PrLink.NONE, none(), none(),
                       "/w/" + name + "/.git", option(kb));
    }

    /// Given newest first, the page lists the longest idle first (oldest activity), ties by host then path.
    @Test
    void drillDown_sortedByIdle_longestFirst() {
        var page = new Page<>(List.of(removable("young", 60, 1L),
                                      scanned(CLOUD, "/w/middle", 3_600, CheckoutStatus.REMOVABLE, new Preservation.Preserved(),
                                              PrLink.NONE, none(), none(), "/w/middle/.git", none()),
                                      removable("old", 90_000, null),
                                      removable("middle", 3_600, 2L)),
                              1,
                              1);
        var html = CheckoutPages.drillDown(CheckoutStatus.REMOVABLE, none(), page, fresh().hosts(), fresh().sources(), NOW);

        assertThat(html.indexOf("bigboy:/w/old")).isLessThan(html.indexOf("bigboy:/w/middle"));
        assertThat(html.indexOf("bigboy:/w/middle")).isLessThan(html.indexOf("cloudbb-3:/w/middle"));
        assertThat(html.indexOf("cloudbb-3:/w/middle")).isLessThan(html.indexOf("bigboy:/w/young"));
        assertThat(html).contains("REMOVABLE checkouts on every host", "<td>unmeasured</td>",
                                  "<td>1 KiB <small>(measured 50m ago)</small></td>", "page 1 of 1 · <a href=\"/\">dashboard</a>",
                                  "as of 2026-10-30T11:00:00Z (1h 0m ago)");
        assertThat(CheckoutPages.drillDown(CheckoutStatus.REMOVABLE, none(), page, fresh().hosts(), degraded().sources(), NOW)).contains(
                "<section id=\"drill-down\" class=\"panel red\">");
    }

    /// Equal idle time on one host: by path.
    @Test
    void drillDown_equalIdle_byPath() {
        var page = new Page<>(List.of(removable("b", 600, null), removable("a", 600, null)), 1, 1);
        var html = CheckoutPages.drillDown(CheckoutStatus.REMOVABLE, some(BIGBOY), page, fresh().hosts(), fresh().sources(), NOW);

        assertThat(html.indexOf("bigboy:/w/a<")).isLessThan(html.indexOf("bigboy:/w/b<"));
    }

    /// A row without full facts is UNSCANNED and shows no derivation age, even if an old derivation is still stored.
    @Test
    void row_unscannedWithStaleDerivation_showsNoDerivedAge() {
        var derived = removable("d", 60, null);
        var unscanned = unscanned(BIGBOY, "/w/d", 60);
        var html = CheckoutPages.row(new CheckoutView(unscanned.row(), derived.derived()), false, NOW);

        assertThat(html).contains("<td>UNSCANNED</td>")
                        .doesNotContain("derived");
        assertThat(CheckoutPages.row(derived, false, NOW)).contains("<td>REMOVABLE <small>(derived 1m ago)</small></td>");
    }

    @Test
    void drillDown_hundredRows_allRendered() {
        var rows = IntStream.range(0, 100).mapToObj(index -> removable("r" + index, 1_000 + index, null)).toList();
        var html = CheckoutPages.drillDown(CheckoutStatus.REMOVABLE, some(BIGBOY), new Page<>(rows, 1, 2), fresh().hosts(), fresh().sources(), NOW);

        assertThat(html.split("<tr><td>", -1)).hasSize(101);
        assertThat(html).contains("page 1 of 2 · <a href=\"/checkouts?status=REMOVABLE&amp;host=bigboy&amp;page=2\">next</a>")
                        .doesNotContain("previous");
    }

    @Test
    void drillDown_lastPage_previousOnly_andEmpty() {
        var html = CheckoutPages.drillDown(CheckoutStatus.UNOWNED, none(), new Page<>(List.of(), 3, 3), fresh().hosts(), degraded().sources(), NOW);

        assertThat(html).contains("page 3 of 3 · <a href=\"/checkouts?status=UNOWNED&amp;page=2\">previous</a> · <a href=\"/\">",
                                  "<p>no rows</p>", "as of never")
                        .doesNotContain(">next<");
    }

    /// A stale host's drill-down page is red and its rows are marked stale.
    @Test
    void drillDown_unreachableHost_redAndStale() {
        var html = CheckoutPages.drillDown(CheckoutStatus.REMOVABLE, some(BIGBOY),
                                           new Page<>(List.of(removable("a", 60, null)), 1, 1), fresh().hosts(), degraded().sources(), NOW);

        assertThat(html).contains("<section id=\"drill-down\" class=\"panel red\">", "<tr class=\"stale\">");
    }

    /// Status filing, as the store's counts do: UNSCANNED without full facts or without a derivation.
    @Test
    void status_unscannedWithoutFactsOrDerivation() {
        var derived = removable("d", 60, null);

        assertThat(derived.status()).isEqualTo(CheckoutStatus.REMOVABLE);
        assertThat(unscanned(BIGBOY, "/w/u", 60).status()).isEqualTo(CheckoutStatus.UNSCANNED);
        assertThat(new CheckoutView(derived.row(), none()).status()).isEqualTo(CheckoutStatus.UNSCANNED);
        assertThat(CheckoutPages.status(CheckoutStatus.STALE_UNOWNED)).isEqualTo("STALE-UNOWNED");
    }

    @Test
    void nonGit_filtersByRootAndSortsByPath() {
        var html = CheckoutPages.nonGit(BIGBOY, "/home/builder/work/co", fresh().nonGit().get(BIGBOY).reversed(), fresh().hosts(), fresh().sources(), NOW);

        assertThat(html.indexOf("/home/builder/work/co/notes")).isLessThan(html.indexOf("/home/builder/work/co/tmp"));
        assertThat(html).doesNotContain("/tmp/scratch/x");
    }
}
