package org.pragmatica.kiln.service.store.pg;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.scan.ScanLine;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassOutcome;
import org.pragmatica.kiln.service.store.PassView;
import org.pragmatica.kiln.service.store.PrView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.CheckoutStoreIT.derivedRow;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.BIGBOY;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.CLOUD;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.checkout;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.path;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pr;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pullRequest;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.stub;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [ViewStore][org.pragmatica.kiln.service.store.ViewStore] against real Postgres: counts are queries over the rows, with
/// the oldest `facts_at` they rest on (§3.4); pages hold 100 rows and filter by status and host; the open PRs are the
/// open ones only, newest activity first, with their linked checkouts.
@Tag("pg")
class ViewStoreIT {
    private static final String CO = "/home/builder/work/co";

    private KilnStore store;

    @BeforeEach
    void store() {
        store = KilnPgFixture.freshStore(StoreFixtures.DIFF);
    }

    @AfterEach
    void close() {
        if (store != null) {
            await(store.close());
        }
    }

    /// A row with no facts, or not yet derived, counts as UNSCANNED; sizes sum only where known, and the measured rows are
    /// counted (2 of bigboy's 4; none of cloudbb-3's), so an unmeasured host never reads 0 KiB.
    @Test
    void counts_perHostAndStatus_withKnownSizeAndOldestFacts() {
        pass(BIGBOY, checkout(CO + "/a"), checkout(CO + "/b"), checkout(CO + "/c"), stub(CO + "/d", T0));
        await(store.checkouts().applyPass(CLOUD, PassKind.HOT, List.of(CO + "/*"), new ScanOutcome.Complete(List.of(checkout(CO + "/z")), 0, none()), T1));
        await(store.checkouts().derived(BIGBOY, List.of(derivedRow(CO + "/a", CheckoutStatus.ACTIVE), derivedRow(CO + "/b", CheckoutStatus.ACTIVE))));
        await(store.checkouts().size(BIGBOY, path(CO + "/a"), 100, T1));
        await(store.checkouts().size(BIGBOY, path(CO + "/d"), 50, T1));

        assertThat(await(store.views().counts())).containsExactly(new HostCounts(BIGBOY,
                                                                                 Map.of(CheckoutStatus.ACTIVE, 2, CheckoutStatus.UNSCANNED, 2),
                                                                                 150,
                                                                                 2,
                                                                                 some(T0)),
                                                                  new HostCounts(CLOUD, Map.of(CheckoutStatus.UNSCANNED, 1), 0, 0, some(T1)));
    }

    @Test
    void checkouts_pageBy100_sortedByIdleTime_filteredByStatusAndHost() {
        var lines = IntStream.range(0, 150)
                             .mapToObj(index -> (ScanLine) stub(String.format("%s/r%03d", CO, index), T0.plusSeconds(150 - index)))
                             .toList();
        await(store.checkouts().applyPass(BIGBOY, PassKind.FULL, List.of(CO + "/*"), new ScanOutcome.Complete(lines, 0, none()), T1));
        pass(CLOUD, checkout(CO + "/a"));
        await(store.checkouts().derived(CLOUD, List.of(derivedRow(CO + "/a", CheckoutStatus.REMOVABLE))));

        var first = await(store.views().checkouts(none(), some(BIGBOY), 1));
        var second = await(store.views().checkouts(none(), some(BIGBOY), 2));
        var removable = await(store.views().checkouts(some(CheckoutStatus.REMOVABLE), none(), 0));
        var none = await(store.views().checkouts(some(CheckoutStatus.ACTIVE), none(), 1));

        assertThat(first.rows()).hasSize(100);
        assertThat(first.pages()).isEqualTo(2);
        assertThat(first.rows().getFirst().row().path()).isEqualTo(path(CO + "/r149"));
        assertThat(second.rows()).hasSize(50);
        assertThat(second.rows().getLast().row().path()).isEqualTo(path(CO + "/r000"));
        assertThat(removable.page()).isEqualTo(1);
        assertThat(removable.rows()).extracting(CheckoutView::derived)
                                    .containsExactly(some(derivedRow(CO + "/a", CheckoutStatus.REMOVABLE)));
        assertThat(none.rows()).isEmpty();
        assertThat(none.pages()).isEqualTo(1);
    }

    @Test
    void openPrs_areTheOpenOnes_newestFirst_withTheirLinkedCheckouts() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a'), pullRequest(2, PrState.CLOSED, 'b'), pullRequest(3, PrState.OPEN, 'c')), T0));
        pass(BIGBOY, checkout(CO + "/a"));
        await(store.checkouts().derived(BIGBOY, List.of(derivedRow(CO + "/a", CheckoutStatus.ACTIVE))));
        await(store.prs().upsert(List.of(pullRequest(7, PrState.OPEN, 'd')), T0));

        var views = await(store.views().openPrs());

        assertThat(views).extracting(view -> view.pr().value().number()).containsExactly(pr(7), pr(3), pr(1));
        assertThat(views.getFirst().checkouts()).containsExactly(new Observed<>(new PrView.LinkedCheckout(BIGBOY, path(CO + "/a"),
                                                                                                         CheckoutStatus.ACTIVE),
                                                                               derivedRow(CO + "/a", CheckoutStatus.ACTIVE).derivedAt()));
        assertThat(views.get(1).checkouts()).isEmpty();
        assertThat(views).allSatisfy(view -> assertThat(view.required()).isEqualTo(none()));
    }

    @Test
    void nonGit_isTheHostsList_byRootAndPath() {
        pass(BIGBOY, new ScanLine.NonGitLine("~/work/*", path("/home/builder/work/b")), new ScanLine.NonGitLine("~/work/*", path("/home/builder/work/a")));
        pass(CLOUD, new ScanLine.NonGitLine("~/work/*", path("/home/builder/work/c")));

        assertThat(await(store.views().nonGit(BIGBOY))).extracting(view -> view.path().value())
                                                       .containsExactly("/home/builder/work/a", "/home/builder/work/b");
    }

    /// WP9 open item 1: the last pass of each kind per host, the Mac's push included, with its outcome, rows, the
    /// listing's own count, its end and a full pass's `k of N`; a failed pass has no count.
    @Test
    void passes_areTheLastPassOfEachKindPerHost_withExpectedAndProgress() {
        pass(BIGBOY, checkout(CO + "/a"), checkout(CO + "/b"));
        await(store.checkouts().applyPass(BIGBOY, PassKind.FULL, List.of(CO + "/*"), new ScanOutcome.Complete(List.of(checkout(CO + "/a")), 0, none()), T1));
        await(store.checkouts().progress(BIGBOY, PassKind.FULL, 1, 3));
        await(store.checkouts().passFailed(CLOUD, PassKind.HOT, "ssh exit 255", T2));
        await(store.checkouts().applyPass(HostName.MAC, PassKind.PUSH, List.of("~/x"), new ScanOutcome.Incomplete(List.of(checkout("/Users/s/x/a")), "no trailer"), T2));

        assertThat(await(store.views().passes())).containsExactly(
            new PassView(BIGBOY, PassKind.FULL, PassOutcome.COMPLETE, 1, some(1), T1, some(T1), some(1), some(3)),
            new PassView(BIGBOY, PassKind.HOT, PassOutcome.COMPLETE, 2, some(2), T0, some(T0), none(), none()),
            new PassView(CLOUD, PassKind.HOT, PassOutcome.UNREACHABLE, 0, none(), T2, some(T2), none(), none()),
            new PassView(HostName.MAC, PassKind.PUSH, PassOutcome.INCOMPLETE, 1, none(), T2, some(T2), none(), none()));
    }

    /// WP9 open item 4: the dashboard's rows, unpaged: the listed statuses of every host (here 150 UNSCANNED rows, past
    /// one page), and every row of one host whatever its status; in the drill-down's idle order.
    @Test
    void listed_isEveryRowOfTheStatuses_unpaged_onOneHostOrAll() {
        var lines = IntStream.range(0, 150)
                             .mapToObj(index -> (ScanLine) stub(String.format("%s/r%03d", CO, index), T0.plusSeconds(150 - index)))
                             .toList();
        await(store.checkouts().applyPass(BIGBOY, PassKind.FULL, List.of(CO + "/*"), new ScanOutcome.Complete(lines, 0, none()), T1));
        pass(CLOUD, checkout(CO + "/a"), checkout(CO + "/b"));
        await(store.checkouts().derived(CLOUD, List.of(derivedRow(CO + "/a", CheckoutStatus.REMOVABLE), derivedRow(CO + "/b", CheckoutStatus.ACTIVE))));

        var listed = await(store.views().listed(Set.of(CheckoutStatus.ACTIVE, CheckoutStatus.UNSCANNED), none()));
        var cloud = await(store.views().listed(Set.of(CheckoutStatus.values()), some(CLOUD)));
        var removable = await(store.views().listed(Set.of(CheckoutStatus.REMOVABLE), some(BIGBOY)));

        assertThat(listed).hasSize(151);
        assertThat(listed.getFirst().row().path()).isEqualTo(path(CO + "/b"));
        assertThat(listed.get(1).row().path()).isEqualTo(path(CO + "/r149"));
        assertThat(listed.getLast().row().path()).isEqualTo(path(CO + "/r000"));
        assertThat(cloud).extracting(CheckoutView::status).containsExactlyInAnyOrder(CheckoutStatus.REMOVABLE, CheckoutStatus.ACTIVE);
        assertThat(removable).isEmpty();
    }

    private void pass(HostName host, ScanLine... lines) {
        await(store.checkouts().applyPass(host, PassKind.HOT, List.of(CO + "/*"), new ScanOutcome.Complete(List.of(lines), 0, none()), T0));
    }
}
