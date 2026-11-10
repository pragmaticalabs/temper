package org.pragmatica.kiln.service.store.pg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.core.summary.PrSnapshot;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.KilnStoreError;
import org.pragmatica.kiln.service.store.PrView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.branch;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.change;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.count;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.dump;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.observed;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pr;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pullRequest;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [PrStore][org.pragmatica.kiln.service.store.PrStore] against real Postgres. K-13 and §3.4: `pr` is never deleted —
/// a PR absent from a later upsert stays, the table refuses DELETE and TRUNCATE, and no kiln source names a delete of it.
/// §2 `change`: the `PR_STATE` changes commit with the value and roll back with it.
///
/// Probes (recorded in the WP5 state file): make `upsertIn` delete the PRs it was not given →
/// [#upsert_neverDeletes_aPrAbsentFromALaterUpsert] red (the trigger refuses; with the trigger dropped the row is gone);
/// insert the changes in their own transaction → [#aFailingChangeRow_rollsTheValueBack] red.
@Tag("pg")
class PrStoreIT {
    private static final Path MAIN = Path.of("src", "main");
    private static final Pattern DELETES_PR = Pattern.compile("(?i)(DELETE\\s+FROM\\s+pr\\b|TRUNCATE\\s+(TABLE\\s+)?pr\\b)");

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
    void upsert_storesEveryField_andAllReadsThemBack() {
        var open = pullRequest(1, PrState.OPEN, 'a');
        var merged = pullRequest(2, PrState.MERGED, 'b');
        var fork = new PullRequest(pr(3), "fork", PrState.CLOSED, true, branch("main"), branch("x"), none(), sha('c'), none(), some(T1), T2,
                                   List.of(), List.of(), List.of(pr(1), pr(2)));

        await(store.prs().upsert(List.of(open, merged, fork), T0));

        assertThat(await(store.prs().all())).containsExactly(open, merged, fork);
    }

    /// K-13: the full table, kept forever.
    @Test
    void upsert_neverDeletes_aPrAbsentFromALaterUpsert() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a'), pullRequest(2, PrState.OPEN, 'b')), T0));

        await(store.prs().upsert(List.of(pullRequest(2, PrState.CLOSED, 'b')), T1));
        await(store.prs().upsert(List.of(), T2));

        assertThat(await(store.prs().all())).extracting(PullRequest::number).containsExactly(pr(1), pr(2));
        assertThat(await(store.prs().all()).get(1).state()).isEqualTo(PrState.CLOSED);
    }

    @Test
    void prTable_refusesDeleteAndTruncate() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));
        var db = ((PgKilnStore) store).db();

        for (var statement : List.of("DELETE FROM pr", "DELETE FROM pr WHERE number = 1", "TRUNCATE pr")) {
            Sql.execute(db.executor(), statement)
               .await(KilnPgFixture.WAIT)
               .onSuccess(_ -> fail(statement + " was accepted"));
        }
        assertThat(count(store, "SELECT count(*) AS n FROM pr")).isEqualTo(1);
    }

    @Test
    void noKilnSource_deletesFromPr() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            var offenders = files.filter(Files::isRegularFile)
                                 .filter(file -> file.toString().endsWith(".java") || file.toString().endsWith(".sql"))
                                 .filter(PrStoreIT::deletesPr)
                                 .toList();

            assertThat(offenders).isEmpty();
        }
    }

    /// The `PR_STATE` changes are returned and stored with the value: a first sight and each state change, nothing for
    /// an unchanged PR.
    @Test
    void upsert_recordsItsChanges_inTheSameCommit() {
        var first = await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));
        var same = await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T1));
        var merged = await(store.prs().upsert(List.of(pullRequest(1, PrState.MERGED, 'a')), T2));

        assertThat(observed(first)).containsExactly(change(T0, 1, ChangeKind.PR_STATE, "#1", "", "OPEN"));
        assertThat(same).isEmpty();
        assertThat(observed(merged)).containsExactly(change(T2, 1, ChangeKind.PR_STATE, "#1", "OPEN", "MERGED"));
        assertThat(merged.getFirst().recordedAt()).isAfter(first.getFirst().recordedAt());
        assertThat(await(store.changes().since(Instant.EPOCH, 10))).containsExactlyElementsOf(List.of(first.getFirst(), merged.getFirst()));
    }

    /// A change row that cannot be written rolls its value back: the stored state stays OPEN.
    @Test
    void aFailingChangeRow_rollsTheValueBack() {
        var failing = KilnPgFixture.freshStore((before, after, at) -> List.of(change(at, 1, ChangeKind.PR_STATE, "refused", "", "x")));
        StoreFixtures.refuse(failing, "change", "subject", "refused");

        failing.prs()
               .upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0)
               .await(KilnPgFixture.WAIT)
               .onSuccess(changes -> fail("expected the change row to fail, got " + changes));

        assertThat(await(failing.prs().all())).isEmpty();
        assertThat(dump(failing, "change")).isEmpty();
        await(failing.close());
    }

    /// A value that cannot be written rolls its change rows back.
    @Test
    void aFailingValue_rollsItsChangesBack() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));
        StoreFixtures.refuse(store, "pr", "title", "refused");
        var broken = new PullRequest(pr(1), "refused", PrState.CLOSED, false, branch("main"), branch("feature-1"), none(), sha('a'),
                                     none(), some(T1), T1, List.of(), List.of(), List.of());

        var cursor = store.changes().watermark();

        store.prs()
             .upsert(List.of(broken), T1)
             .await(KilnPgFixture.WAIT)
             .onSuccess(changes -> fail("expected the PR write to fail, got " + changes));

        assertThat(await(store.prs().all()).getFirst().state()).isEqualTo(PrState.OPEN);
        assertThat(await(store.changes().since(cursor, 10))).isEmpty();
    }

    /// A NUL in free text never blocks a batch (lead ruling, WP5 verification): the store writes U+0000 as U+FFFD, so the
    /// batch [#4, #3 titled `a<NUL>b` with a NUL label] stores both, and a lookup by the raw text finds the stored row.
    @Test
    void upsert_storesANulInFreeTextAsAReplacementCharacter_andTheRestOfItsBatch() {
        var nul = new PullRequest(pr(3), "a\u0000b", PrState.OPEN, false, branch("main"), branch("feature-3"), none(), sha('a'),
                                  none(), none(), T0, List.of("x\u0000y"), List.of(), List.of());

        await(store.prs().upsert(List.of(pullRequest(4, PrState.OPEN, 'a'), nul), T0));

        assertThat(await(store.prs().all())).extracting(PullRequest::title)
                                            .containsExactly("a\uFFFDb", "PR 4");
        assertThat(await(store.prs().all()).getFirst().labels()).containsExactly("x\uFFFDy");
        assertThat(count(store, "SELECT count(*) AS n FROM pr WHERE title = $1", "a\u0000b")).isEqualTo(1);
    }

    /// The diff sees the stored states of exactly the PRs written.
    @Test
    void upsert_givesTheRuleTheStoredStates() {
        var seen = new ArrayList<PrSnapshot>();
        var spying = KilnPgFixture.freshStore((before, after, at) -> recorded(seen, before));
        await(spying.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a'), pullRequest(2, PrState.OPEN, 'b')), T0));

        await(spying.prs().upsert(List.of(pullRequest(2, PrState.MERGED, 'b'), pullRequest(3, PrState.OPEN, 'c')), T1));

        assertThat(seen.getLast().states()).containsExactlyEntriesOf(Map.of(pr(2), PrState.OPEN));
        await(spying.close());
    }

    /// A PR listed twice in one upsert (overlapping pages) is stored as its last listing, and the change says the same.
    @Test
    void upsert_ofAPrListedTwice_keepsTheLastListing() {
        var changes = await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a'), pullRequest(1, PrState.MERGED, 'a')), T0));

        assertThat(await(store.prs().all()).getFirst().state()).isEqualTo(PrState.MERGED);
        assertThat(observed(changes)).containsExactly(change(T0, 1, ChangeKind.PR_STATE, "#1", "", "MERGED"));
    }

    @Test
    void highWater_isAbsentUntilSet_thenTheLastSet() {
        assertThat(await(store.prs().highWater())).isEqualTo(none());

        await(store.prs().highWater(T0));
        await(store.prs().highWater(T1));

        assertThat(await(store.prs().highWater())).isEqualTo(some(T1));
    }

    /// The backfill marker (design §4.1, for WP6's poller) is absent until set, then the last set, and kept apart from
    /// the high-water cursor in the same table: setting either never reads as the other.
    @Test
    void backfillDone_isAbsentUntilSet_thenTheLastSet_apartFromTheHighWater() {
        await(store.prs().highWater(T2));

        assertThat(await(store.prs().backfillDone())).isEqualTo(none());

        await(store.prs().backfillDone(T0));
        await(store.prs().backfillDone(T1));

        assertThat(await(store.prs().backfillDone())).isEqualTo(some(T1));
        assertThat(await(store.prs().highWater())).isEqualTo(some(T2));
    }

    /// A marker that is no instant fails the read as a corrupt `gh_cursor` row; it never reads as "not done".
    @Test
    void backfillDone_failsOnACorruptValue() {
        StoreFixtures.execute(store, "INSERT INTO gh_cursor (name, value) VALUES ('backfill_done', 'yes')");

        store.prs()
             .backfillDone()
             .await(KilnPgFixture.WAIT)
             .onSuccess(done -> fail("expected a corrupt marker to fail, got " + done))
             .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(KilnStoreError.CorruptRow.class,
                                                                          row -> assertThat(row.table()).isEqualTo("gh_cursor")));
    }

    /// The open detail and the mirror facts of the current head reach the view with their own times; a NULL mirror fact
    /// stays absent (`UNKNOWN`, K-8), and a mirror row about an older head is not shown as the current head's.
    @Test
    void openDetailAndBehind_reachTheView_forTheCurrentHeadOnly() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a'), pullRequest(2, PrState.OPEN, 'b')), T0));
        var detail = new OpenDetail(some(true), some("clean"), some(T0));

        await(store.prs().openDetail(pr(1), detail, T1));
        await(store.prs().behind(pr(1), sha('a'), branch("main"), new BehindFacts(none(), none()), T2));
        await(store.prs().behind(pr(2), sha('f'), branch("main"), new BehindFacts(some(3), some(false)), T2));

        var views = await(store.views().openPrs());
        var one = views.stream().filter(view -> view.pr().value().number().equals(pr(1))).findFirst().orElseThrow();
        var two = views.stream().filter(view -> view.pr().value().number().equals(pr(2))).findFirst().orElseThrow();
        assertThat(one.detail().map(observed -> observed.value())).isEqualTo(some(detail));
        assertThat(one.detail().map(observed -> observed.observedAt())).isEqualTo(some(T1));
        assertThat(one.behind().map(observed -> observed.value())).isEqualTo(some(new BehindFacts(none(), none())));
        assertThat(two.behind()).isEqualTo(none());
        assertThat(two.detail()).isEqualTo(none());
        assertThat(views).extracting(PrView::pr).extracting(observed -> observed.observedAt()).containsOnly(T0);
    }

    private static List<Change> recorded(List<PrSnapshot> seen, PrSnapshot before) {
        seen.add(before);
        return List.of();
    }

    private static boolean deletesPr(Path file) {
        try {
            return DELETES_PR.matcher(Files.readString(file)).find();
        } catch (IOException e) {
            throw new IllegalStateException(file.toString(), e);
        }
    }
}
