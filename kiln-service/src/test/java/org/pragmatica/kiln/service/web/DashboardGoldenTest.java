package org.pragmatica.kiln.service.web;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.Page;
import org.pragmatica.lang.Option;

import static org.pragmatica.kiln.service.web.Golden.assertGolden;
import static org.pragmatica.kiln.service.web.WebFixtures.BIGBOY;
import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.degraded;
import static org.pragmatica.kiln.service.web.WebFixtures.fresh;
import static org.pragmatica.kiln.service.web.WebFixtures.scanned;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Every panel, rendered from the fresh and the degraded fixture, against its golden file (kiln-f1-design §7.2, WP10).
class DashboardGoldenTest {
    private static CheckoutView removable(String path, long idle, Option<Long> kb) {
        return scanned(BIGBOY, path, idle, CheckoutStatus.REMOVABLE, new Preservation.Preserved(), PrLink.NONE, some(WebFixtures.MAIN),
                       some(0), path + "/.git", kb);
    }

    @Test
    void header_fresh() {
        assertGolden("header-fresh", Dashboard.header(fresh(), NOW));
    }

    @Test
    void header_degraded() {
        assertGolden("header-degraded", Dashboard.header(degraded(), NOW));
    }

    @Test
    void prs_fresh() {
        assertGolden("prs-fresh", Dashboard.prs(fresh(), NOW));
    }

    @Test
    void prs_degraded() {
        assertGolden("prs-degraded", Dashboard.prs(degraded(), NOW));
    }

    @Test
    void bases_fresh() {
        assertGolden("bases-fresh", Dashboard.bases(fresh(), NOW));
    }

    @Test
    void bases_degraded() {
        assertGolden("bases-degraded", Dashboard.bases(degraded(), NOW));
    }

    @Test
    void checkouts_fresh() {
        assertGolden("checkouts-fresh", Dashboard.checkouts(fresh(), NOW));
    }

    @Test
    void checkouts_degraded() {
        assertGolden("checkouts-degraded", Dashboard.checkouts(degraded(), NOW));
    }

    @Test
    void temper_fresh() {
        assertGolden("temper-fresh", Dashboard.temper(fresh(), NOW));
    }

    @Test
    void temper_degraded() {
        assertGolden("temper-degraded", Dashboard.temper(degraded(), NOW));
    }

    @Test
    void questions() {
        assertGolden("questions", Dashboard.questions(NOW));
    }

    @Test
    void dashboard_degraded_wholePage() {
        assertGolden("dashboard-degraded", Dashboard.render(degraded(), NOW, none()));
    }

    @Test
    void dashboard_fresh_wholePage() {
        assertGolden("dashboard-fresh", Dashboard.render(fresh(), NOW, none()));
    }

    @Test
    void drillDown_removableOnBigboy() {
        assertGolden("drill-down",
                     CheckoutPages.drillDown(CheckoutStatus.REMOVABLE,
                                             some(BIGBOY),
                                             new Page<>(List.of(removable("/home/builder/work/co/a", 90_000, some(2_048L)),
                                                                removable("/home/builder/work/co/b", 900_000, none()),
                                                                removable("/home/builder/work/co/c", 3_600, some(10L))),
                                                        2,
                                                        3),
                                             fresh().hosts(),
                                             fresh().sources(),
                                             NOW));
    }

    @Test
    void nonGit_oneRoot() {
        assertGolden("non-git",
                     CheckoutPages.nonGit(BIGBOY,
                                          "/home/builder/work/co",
                                          fresh().nonGit().get(BIGBOY),
                                          fresh().hosts(),
                                          degraded().sources(),
                                          NOW));
    }

    @Test
    void nonGit_empty() {
        assertGolden("non-git-empty", CheckoutPages.nonGit(BIGBOY, "/nowhere", List.of(), fresh().hosts(), fresh().sources(), NOW));
    }
}
