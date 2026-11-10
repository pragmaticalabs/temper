package org.pragmatica.kiln.service.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.Derived;
import org.pragmatica.kiln.core.checkout.LinkRule;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.scan.ScanLine;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassOutcome;
import org.pragmatica.kiln.service.store.PassRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.BIGBOY;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.CLOUD;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.checkout;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.count;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.dump;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.facts;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.nonGit;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.path;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.stub;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [CheckoutStore][org.pragmatica.kiln.service.store.CheckoutStore] against real Postgres, and kiln-f1-design §3.4: no
/// failure makes a count read 0. An INCOMPLETE pass deletes nothing (K-4); a COMPLETE pass deletes only the absent rows
/// its roots cover; a failed pass touches no row (K-5); a failing statement rolls the whole pass back.
///
/// Probes (recorded in the WP5 state file): make [PgCheckoutStore]'s `pruned` delete for every outcome →
/// [#incompletePass_deletesNothing] red; drop the roots filter from `absent` →
/// [#completePass_deletesOnlyAbsentRows_withinItsRoots] red; make `passFailed` delete the host's rows →
/// [#failedPass_touchesNoRowOfAnyCheckoutTable] red.
@Tag("pg")
class CheckoutStoreIT {
    private static final String CO = "/home/builder/work/co";
    private static final String WT = "/home/builder/temper-wt";
    private static final List<String> ROOTS = List.of(CO + "/*", WT + "/*");
    private static final List<String> CHECKOUT_TABLES = List.of("checkout", "stash", "non_git", "checkout_derived");

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

    @Test
    void completePass_writesFactsStubsAndNonGit_andReadsThemBack() {
        var record = apply(ROOTS,
                           complete(1, checkout(CO + "/a"), stub(CO + "/b", T1), nonGit(CO + "/*", CO + "/junk"), new ScanLine.Trailer(2, 1, none(), none())),
                           T2);

        assertThat(record).isEqualTo(new PassRecord(PassOutcome.COMPLETE, 2));
        var rows = await(store.checkouts().rows(BIGBOY));
        assertThat(rows).extracting(CheckoutRow::path).containsExactly(path(CO + "/a"), path(CO + "/b"));
        var a = rows.getFirst();
        assertThat(a.facts()).isEqualTo(some(facts(CO + "/a", CO + "/a/.git", 0)));
        assertThat(a.factsPass()).isEqualTo(some(PassKind.HOT));
        assertThat(a.factsAt()).isEqualTo(some(T2));
        assertThat(a.listedAt()).isEqualTo(T2);
        var b = rows.get(1);
        assertThat(b.facts()).isEqualTo(none());
        assertThat(b.factsAt()).isEqualTo(none());
        assertThat(b.activityAt()).isEqualTo(T1);
        assertThat(b.commonDir()).isEqualTo(CO + "/b/.git");
        assertThat(await(store.views().nonGit(BIGBOY))).extracting(view -> view.path().value()).containsExactly(CO + "/junk");
        assertThat(dump(store, "host_pass")).singleElement().asString()
                                            .contains("\"kind\": \"hot\"", "\"outcome\": \"COMPLETE\"", "\"rows\": 2", "\"expected\": 2",
                                                      "\"non_git\": 1");
    }

    /// The stash count belongs to the common dir: two worktrees of one repository read the same count.
    @Test
    void stash_isOneCountPerCommonDir_sharedByItsWorktrees() {
        var shared = CO + "/main/.git";
        apply(ROOTS,
              complete(0,
                       new ScanLine.CheckoutLine(facts(CO + "/main", shared, 0)),
                       new ScanLine.CheckoutLine(facts(CO + "/wt", shared, 4))),
              T0);

        assertThat(await(store.checkouts().rows(BIGBOY))).extracting(row -> row.facts().map(CheckoutFacts::stash).or(-1))
                                                         .containsExactly(4, 4);
    }

    /// K-4: an INCOMPLETE pass upserts the rows it has and deletes nothing, not even with no rows at all.
    @Test
    void incompletePass_deletesNothing() {
        apply(ROOTS, complete(1, checkout(CO + "/a"), checkout(CO + "/b"), checkout(WT + "/c"), nonGit(CO + "/*", CO + "/junk")), T0);

        var truncated = apply(ROOTS, new ScanOutcome.Incomplete(List.of(stub(CO + "/a", T1)), "no trailer"), T1);
        var empty = apply(ROOTS, new ScanOutcome.Incomplete(List.of(), "empty stream"), T2);

        assertThat(truncated).isEqualTo(new PassRecord(PassOutcome.INCOMPLETE, 1));
        assertThat(empty).isEqualTo(new PassRecord(PassOutcome.INCOMPLETE, 0));
        assertThat(paths(BIGBOY)).containsExactly(WT + "/c", CO + "/a", CO + "/b");
        assertThat(await(store.views().nonGit(BIGBOY))).hasSize(1);
        assertThat(count(store, "SELECT count(*) AS n FROM stash")).isEqualTo(3);
        assertThat(dump(store, "host_pass")).singleElement().asString()
                                            .contains("\"outcome\": \"INCOMPLETE\"", "\"rows\": 0", "\"expected\": null");
    }

    /// A COMPLETE pass deletes the absent rows its roots cover, with their derivation, their orphaned stash and their
    /// non-git entries; rows under another root, and every row of another host, stay.
    @Test
    void completePass_deletesOnlyAbsentRows_withinItsRoots() {
        apply(ROOTS,
              complete(2, checkout(CO + "/a"), checkout(CO + "/b"), checkout(WT + "/x"), nonGit(CO + "/*", CO + "/junk"),
                       nonGit(WT + "/*", WT + "/junk")),
              T0);
        await(store.checkouts().applyPass(CLOUD, PassKind.HOT, ROOTS, complete(0, checkout(CO + "/b")), T0));
        await(store.checkouts().derived(BIGBOY, List.of(derivedRow(CO + "/a", CheckoutStatus.ACTIVE), derivedRow(CO + "/b", CheckoutStatus.REMOVABLE))));

        var record = apply(List.of(CO + "/*"), complete(0, checkout(CO + "/a")), T1);

        assertThat(record).isEqualTo(new PassRecord(PassOutcome.COMPLETE, 1));
        assertThat(paths(BIGBOY)).containsExactly(WT + "/x", CO + "/a");
        assertThat(paths(CLOUD)).containsExactly(CO + "/b");
        assertThat(count(store, "SELECT count(*) AS n FROM checkout_derived")).isEqualTo(1);
        assertThat(count(store, "SELECT count(*) AS n FROM stash WHERE host = 'bigboy' AND common_dir = $1", CO + "/b/.git")).isZero();
        assertThat(count(store, "SELECT count(*) AS n FROM stash WHERE host = 'cloudbb-3'")).isEqualTo(1);
        assertThat(await(store.views().nonGit(BIGBOY))).extracting(view -> view.path().value()).containsExactly(WT + "/junk");
    }

    /// A `~` root deletes nothing (lead ruling, WP5 verification; the store does not know the host's home): a COMPLETE
    /// pass under `~/ticketing` keeps `/home/builder/src/ticketing`, listed under `~/src/*`, and still
    /// upserts its own rows.
    @Test
    void homeRoot_deletesNothing_butStillUpserts() {
        apply(List.of("~/src/*"), complete(0, checkout("/home/builder/src/ticketing")), T0);

        apply(List.of("~/ticketing", "~/work/co/*"), complete(0, checkout(CO + "/a")), T1);

        assertThat(paths(BIGBOY)).containsExactly("/home/builder/src/ticketing", CO + "/a");
    }

    /// An absolute root still deletes within itself, beside a `~` root that deletes nothing.
    @Test
    void absoluteRoot_stillDeletesWithinItself_besideAHomeRoot() {
        apply(List.of("/srv/co/*", "~/work/co/*"), complete(0, checkout(CO + "/a"), checkout("/srv/co/a"), checkout("/srv/co/b")), T0);

        apply(List.of("/srv/co/*", "~/work/co/*"), complete(0, checkout("/srv/co/a")), T1);

        assertThat(paths(BIGBOY)).containsExactly(CO + "/a", "/srv/co/a");
    }

    /// K-5: a failed pass writes its `host_pass` row and touches no row of any checkout table.
    @Test
    void failedPass_touchesNoRowOfAnyCheckoutTable() {
        apply(ROOTS, complete(1, checkout(CO + "/a"), stub(CO + "/b", T0), nonGit(CO + "/*", CO + "/junk")), T0);
        await(store.checkouts().derived(BIGBOY, List.of(derivedRow(CO + "/a", CheckoutStatus.ACTIVE))));
        var before = CHECKOUT_TABLES.stream().map(table -> dump(store, table)).toList();

        await(store.checkouts().passFailed(BIGBOY, PassKind.HOT, "ssh exit 255", T1));
        await(store.checkouts().passFailed(BIGBOY, PassKind.FULL, "timeout", T1));

        assertThat(CHECKOUT_TABLES.stream().map(table -> dump(store, table)).toList()).isEqualTo(before);
        assertThat(before.getFirst()).hasSize(2);
        assertThat(dump(store, "host_pass")).hasSize(2)
                                            .allSatisfy(row -> assertThat(row).contains("\"outcome\": \"UNREACHABLE\"", "\"rows\": 0"));
    }

    /// One statement failing (a row Postgres refuses) rolls the whole COMPLETE pass back: no new row, no deletion,
    /// and the previous `host_pass` row stays.
    @Test
    void aFailingStatement_rollsTheWholePassBack() {
        apply(ROOTS, complete(0, checkout(CO + "/a"), checkout(CO + "/b")), T0);
        var passBefore = dump(store, "host_pass");
        StoreFixtures.refuse(store, "checkout", "common_dir", "refused-dir");

        store.checkouts()
             .applyPass(BIGBOY,
                        PassKind.HOT,
                        ROOTS,
                        complete(0, checkout(CO + "/c"), new ScanLine.CheckoutLine(facts(CO + "/d", "refused-dir", 0))),
                        T1)
             .await(KilnPgFixture.WAIT)
             .onSuccess(record -> fail("expected the refused row to fail the pass, got " + record));

        assertThat(paths(BIGBOY)).containsExactly(CO + "/a", CO + "/b");
        assertThat(dump(store, "host_pass")).isEqualTo(passBefore);
    }

    /// A stub line refreshes the listing and the activity time and keeps the facts of the last full-facts pass; the facts
    /// read back carry the newest activity kiln has seen.
    @Test
    void stubLine_keepsTheLastFacts() {
        apply(ROOTS, complete(0, checkout(CO + "/a")), T0);

        apply(ROOTS, complete(0, stub(CO + "/a", T2)), T2);

        var row = await(store.checkouts().rows(BIGBOY)).getFirst();
        assertThat(row.facts()).isEqualTo(some(factsOf(factsAt(CO + "/a", T2))));
        assertThat(row.factsAt()).isEqualTo(some(T0));
        assertThat(row.activityAt()).isEqualTo(T2);
        assertThat(row.listedAt()).isEqualTo(T2);
    }

    /// Q-12: the derived statuses ACTIVE, IN_USE and UNOWNED, activity inside the window, every UNSCANNED row, and a row
    /// whose activity moved past its facts; a cold REMOVABLE row is not hot.
    @Test
    void hotSet_isTheActiveRecentUnscannedAndMovedRows() {
        var now = T0.plus(Duration.ofHours(72));
        var window = Duration.ofHours(48);
        apply(ROOTS,
              complete(0, checkout(CO + "/active"), checkout(CO + "/inuse"), checkout(CO + "/unowned"), checkout(CO + "/cold"),
                       checkout(CO + "/moved"), stub(CO + "/unscanned", T0)),
              T0);
        apply(List.of(), complete(0, factsAt(CO + "/recent", now.minus(Duration.ofHours(1)))), now);
        apply(List.of(), new ScanOutcome.Incomplete(List.of(stub(CO + "/moved", T0.plus(Duration.ofHours(10)))), "partial"), T1);
        await(store.checkouts().derived(BIGBOY,
                                        List.of(derivedRow(CO + "/active", CheckoutStatus.ACTIVE),
                                                derivedRow(CO + "/inuse", CheckoutStatus.IN_USE),
                                                derivedRow(CO + "/unowned", CheckoutStatus.UNOWNED),
                                                derivedRow(CO + "/cold", CheckoutStatus.REMOVABLE),
                                                derivedRow(CO + "/moved", CheckoutStatus.REMOVABLE),
                                                derivedRow(CO + "/recent", CheckoutStatus.REMOVABLE))));

        assertThat(await(store.checkouts().hotSet(BIGBOY, now, window))).extracting(CheckoutPath::value)
                                                                          .containsExactly(CO + "/active", CO + "/inuse", CO + "/moved",
                                                                                           CO + "/recent", CO + "/unowned", CO + "/unscanned");
    }

    /// A derivation is written only for a checkout that still exists, so a derivation racing a deletion never
    /// resurrects a row; a second write replaces the first.
    @Test
    void derived_isWrittenOnlyForAnExistingCheckout_andReplaced() {
        apply(ROOTS, complete(0, checkout(CO + "/a")), T0);

        await(store.checkouts().derived(BIGBOY, List.of(derivedRow(CO + "/a", CheckoutStatus.ACTIVE), derivedRow(CO + "/gone", CheckoutStatus.ACTIVE))));
        await(store.checkouts().derived(BIGBOY, List.of(derivedRow(CO + "/a", CheckoutStatus.NEEDS_SALVAGE))));

        var page = await(store.views().checkouts(none(), some(BIGBOY), 1));
        assertThat(count(store, "SELECT count(*) AS n FROM checkout_derived")).isEqualTo(1);
        assertThat(page.rows().getFirst().derived()).isEqualTo(some(derivedRow(CO + "/a", CheckoutStatus.NEEDS_SALVAGE)));
    }

    /// Every preservation kind, with its reasons, reads back as written.
    @Test
    void derived_roundTripsEveryPreservation() {
        apply(ROOTS, complete(0, checkout(CO + "/a")), T0);

        for (var preservation : List.<Preservation> of(new Preservation.Preserved(),
                                                         new Preservation.HostBareOnly(),
                                                         new Preservation.Unpreserved(List.of("unpushed:x")))) {
            var row = new DerivedRow(path(CO + "/a"), new Derived(CheckoutStatus.REMOVABLE, preservation, PrLink.NONE, none()), none(), none(), none(), T1);
            await(store.checkouts().derived(BIGBOY, List.of(row)));

            assertThat(await(store.views().checkouts(none(), some(BIGBOY), 1)).rows().getFirst().derived()).isEqualTo(some(row));
        }
    }

    /// Null sizes first (by path), then the oldest measurement.
    @Test
    void nextToSize_isNullSizesFirst_thenTheOldest() {
        apply(ROOTS, complete(0, checkout(CO + "/a"), checkout(CO + "/b")), T0);
        assertThat(await(store.checkouts().nextToSize(BIGBOY))).isEqualTo(some(path(CO + "/a")));

        await(store.checkouts().size(BIGBOY, path(CO + "/a"), 1024, T1));
        assertThat(await(store.checkouts().nextToSize(BIGBOY))).isEqualTo(some(path(CO + "/b")));

        await(store.checkouts().size(BIGBOY, path(CO + "/b"), 2048, T2));
        assertThat(await(store.checkouts().nextToSize(BIGBOY))).isEqualTo(some(path(CO + "/a")));
        assertThat(await(store.checkouts().rows(BIGBOY))).extracting(CheckoutRow::sizeKb).containsExactly(some(1024L), some(2048L));
        assertThat(await(store.checkouts().nextToSize(CLOUD))).isEqualTo(none());
    }

    /// The full pass's `k of n` (WP8): set on the kind's last pass only, and cleared by the next pass of that kind.
    @Test
    void progress_setsKofN_ofTheLastPassOfItsKind_andTheNextPassClearsIt() {
        await(store.checkouts().applyPass(BIGBOY, PassKind.FULL, ROOTS, complete(0, checkout(CO + "/a")), T0));
        apply(ROOTS, complete(0, checkout(CO + "/a")), T0);

        await(store.checkouts().progress(BIGBOY, PassKind.FULL, 2, 7));

        assertThat(dump(store, "host_pass")).anySatisfy(row -> assertThat(row).asString().contains("\"kind\": \"full\"",
                                                                                                    "\"progress_k\": 2",
                                                                                                    "\"progress_n\": 7"))
                                            .anySatisfy(row -> assertThat(row).asString().contains("\"kind\": \"hot\"",
                                                                                                    "\"progress_k\": null"));
        await(store.checkouts().applyPass(BIGBOY, PassKind.FULL, ROOTS, complete(0, checkout(CO + "/a")), T1));
        assertThat(dump(store, "host_pass")).allSatisfy(row -> assertThat(row).asString().contains("\"progress_k\": null",
                                                                                                    "\"progress_n\": null"));
    }

    /// The Mac's rows are host `mac`, which a configured host can never be.
    @Test
    void macPush_isStoredUnderTheReservedHost() {
        await(store.checkouts().applyPass(HostName.MAC, PassKind.PUSH, List.of("~/src/pragmatica*"),
                                          complete(0, checkout("/Users/me/src/pragmatica-x")), T0));

        assertThat(await(store.checkouts().rows(HostName.MAC))).extracting(CheckoutRow::host).containsExactly(HostName.MAC);
        assertThat(dump(store, "host_pass")).singleElement().asString().contains("\"kind\": \"push\"");
    }

    private PassRecord apply(List<String> roots, ScanOutcome outcome, Instant at) {
        return await(store.checkouts().applyPass(BIGBOY, PassKind.HOT, roots, outcome, at));
    }

    private List<String> paths(HostName host) {
        return await(store.checkouts().rows(host)).stream().map(row -> row.path().value()).toList();
    }

    private static ScanOutcome complete(int nonGit, ScanLine... lines) {
        return new ScanOutcome.Complete(List.of(lines), nonGit, none());
    }

    private static ScanLine factsAt(String path, Instant activityAt) {
        var facts = facts(path, path + "/.git", 0);

        return new ScanLine.CheckoutLine(new CheckoutFacts(facts.path(), facts.head(), facts.branch(), facts.remoteUrl(), facts.branches(),
                                                           facts.modified(), facts.untracked(), facts.op(), activityAt, facts.processes(),
                                                           facts.maven(), facts.commonDir(), facts.stash(), facts.salvageRefs()));
    }

    private static CheckoutFacts factsOf(ScanLine line) {
        return ((ScanLine.CheckoutLine) line).facts();
    }

    static DerivedRow derivedRow(String path, CheckoutStatus status) {
        return new DerivedRow(path(path),
                              new Derived(status, new Preservation.Unpreserved(List.of("dirty:2", "stash:1")), new PrLink(List.of(StoreFixtures.pr(7)),
                                                                                                                        LinkRule.BRANCH),
                                          some(false)),
                              some(T0),
                              some(T1),
                              none(),
                              T2);
    }
}
