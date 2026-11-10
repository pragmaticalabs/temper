package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.PrView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.branch;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.change;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.dump;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.observed;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pr;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pullRequest;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [CheckStore][org.pragmatica.kiln.service.store.CheckStore] against real Postgres: each of the three list kinds
/// round-trips, the cells are those of the current head only, and the `CHECK_CELL` changes commit and roll back with
/// their cells (§2 `change`). Probe (state file): insert the changes before the cell write in a separate transaction →
/// [#aFailingCell_rollsItsChangesBack] red.
@Tag("pg")
class CheckStoreIT {
    private static final RequiredCheck GATE = new RequiredCheck(CheckContext.checkContext("gate").unwrap(), some(AppId.appId(15368).unwrap()));
    private static final RequiredCheck LINT = new RequiredCheck(CheckContext.checkContext("lint").unwrap(), none());

    private KilnStore store;

    @BeforeEach
    void store() {
        store = KilnPgFixture.freshStore(StoreFixtures.DIFF);
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));
    }

    @AfterEach
    void close() {
        if (store != null) {
            await(store.close());
        }
    }

    @Test
    void required_roundTripsEachKind_andTheLastWriteWins() {
        var lists = List.of(new RequiredChecks.Required(List.of(GATE, LINT), "non_admins"),
                            new RequiredChecks.NoneRequired("enforcement_level: off", true),
                            new RequiredChecks.Unknown("403"));

        for (var list : lists) {
            await(store.checks().required(branch("main"), list, T1));

            assertThat(view().required()).isEqualTo(some(new Observed<RequiredChecks>(list, T1)));
        }
    }

    /// A base whose list was never read shows none, never a guessed one.
    @Test
    void required_isAbsent_forABaseNeverRead() {
        await(store.checks().required(branch("other"), new RequiredChecks.Unknown("403"), T1));

        assertThat(view().required()).isEqualTo(none());
    }

    @Test
    void cells_replaceThePrsCells_andRecordEachChangedValue() {
        var first = await(store.checks().cells(pr(1), sha('a'), List.of(cell(GATE, "pending"), cell(LINT, "success")), T1));
        var second = await(store.checks().cells(pr(1), sha('a'), List.of(cell(GATE, "success")), T2));

        assertThat(observed(first)).containsExactlyInAnyOrder(change(T1, 1, ChangeKind.CHECK_CELL, GATE.key(), "", "pending"),
                                                     change(T1, 1, ChangeKind.CHECK_CELL, LINT.key(), "", "success"));
        assertThat(observed(second)).containsExactly(change(T2, 1, ChangeKind.CHECK_CELL, GATE.key(), "pending", "success"));
        assertThat(view().cells()).containsExactly(new Observed<>(cell(GATE, "success"), T2));
        assertThat(await(store.changes().since(Instant.EPOCH, 10))).filteredOn(change -> change.kind() == ChangeKind.CHECK_CELL)
                                                                  .hasSize(3);
    }

    /// The view shows the cells of the PR's current head only: cells of an older head are never shown as the new
    /// head's.
    @Test
    void cells_ofAnOlderHead_areNotShown() {
        await(store.checks().cells(pr(1), sha('a'), List.of(cell(GATE, "success")), T1));

        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'b')), T2));

        assertThat(view().cells()).isEmpty();
    }

    /// The failing cell is refused by Postgres (a test trigger), while every change row it implies is valid: so a change
    /// row that commits outside the cell's transaction would be seen.
    @Test
    void aFailingCell_rollsItsChangesBack() {
        await(store.checks().cells(pr(1), sha('a'), List.of(cell(GATE, "pending")), T1));
        var before = dump(store, "check_cell");
        StoreFixtures.refuse(store, "check_cell", "context", "lint");

        var cursor = store.changes().watermark();

        store.checks()
             .cells(pr(1), sha('a'), List.of(cell(GATE, "success"), cell(LINT, "success")), T2)
             .await(KilnPgFixture.WAIT)
             .onSuccess(changes -> fail("expected the cell write to fail, got " + changes));

        assertThat(dump(store, "check_cell")).isEqualTo(before);
        assertThat(await(store.changes().since(cursor, 10))).isEmpty();
    }

    /// A NUL inside a jsonb string (a status description in `shown`) never blocks the write (lead ruling, WP5
    /// verification): it is stored as U+FFFD, while a literal backslash followed by `u0000` is kept as written.
    @Test
    void cells_storeANulInShownAsAReplacementCharacter_andKeepALiteralEscape() {
        var shown = List.of("failure: build broke\u0000here", "literal \\u0000 text");

        await(store.checks().cells(pr(1), sha('a'), List.of(new Cell(GATE, "failure", 0, shown)), T1));

        assertThat(view().cells()).extracting(observed -> observed.value().shown())
                                  .containsExactly(List.of("failure: build broke\uFFFDhere", "literal \\u0000 text"));
    }

    /// Lead ruling (WP2): a check is `{context, appId}`. One context pinned to two apps, and the same context unpinned,
    /// are three cells, stored, replaced and changed apart.
    @Test
    void cells_ofOneContextUnderTwoAppsAndNone_areKeptReplacedAndChangedApart() {
        var other = new RequiredCheck(GATE.context(), some(AppId.appId(99).unwrap()));
        var unpinned = new RequiredCheck(GATE.context(), none());

        var first = await(store.checks().cells(pr(1), sha('a'), List.of(cell(GATE, "pending"), cell(other, "success"), cell(unpinned, "failure")), T1));
        var second = await(store.checks().cells(pr(1), sha('a'), List.of(cell(GATE, "success"), cell(other, "success"), cell(unpinned, "failure")), T2));

        assertThat(first).extracting(Change::subject).containsExactlyInAnyOrder(GATE.key(), other.key(), unpinned.key());
        assertThat(observed(second)).containsExactly(change(T2, 1, ChangeKind.CHECK_CELL, GATE.key(), "pending", "success"));
        assertThat(view().cells()).extracting(Observed::value)
                                  .containsExactly(cell(unpinned, "failure"), cell(other, "success"), cell(GATE, "success"));

        var third = await(store.checks().cells(pr(1), sha('a'), List.of(cell(GATE, "success"), cell(other, "failure")), T2.plusSeconds(60)));

        assertThat(observed(third)).containsExactly(change(T2.plusSeconds(60), 1, ChangeKind.CHECK_CELL, other.key(), "success", "failure"));
        assertThat(view().cells()).extracting(Observed::value)
                                  .containsExactly(cell(other, "failure"), cell(GATE, "success"));
    }

    /// The change subject is the injective [RequiredCheck#key] (WP2, accepted 0d0b2c2a): an unpinned context spelled like
    /// a pinned check's label (`gate@15368`) is a cell of its own, and each changes apart under its own subject.
    @Test
    void cells_keyAnUnpinnedContextSpelledLikeAPinnedLabel_apart() {
        var lookalike = new RequiredCheck(CheckContext.checkContext("gate@15368").unwrap(), none());

        await(store.checks().cells(pr(1), sha('a'), List.of(cell(GATE, "pending"), cell(lookalike, "pending")), T1));
        var changed = await(store.checks().cells(pr(1), sha('a'), List.of(cell(GATE, "success"), cell(lookalike, "failure")), T2));

        assertThat(GATE.key()).isEqualTo("gate\u001F15368");
        assertThat(observed(changed)).containsExactlyInAnyOrder(change(T2, 1, ChangeKind.CHECK_CELL, GATE.key(), "pending", "success"),
                                                               change(T2, 1, ChangeKind.CHECK_CELL, "gate@15368", "pending", "failure"));
        assertThat(view().cells()).hasSize(2);
    }

    /// The key is `(pr, context, COALESCE(app_id, -1))`: an unpinned check is one key too, though NULL never equals NULL.
    /// The same check twice in one write (unpinned, and pinned) fails the write, which keeps the stored cells.
    @Test
    void cells_refuseTheSameCheckTwice_unpinnedOrPinned() {
        await(store.checks().cells(pr(1), sha('a'), List.of(cell(LINT, "pending")), T1));
        var before = dump(store, "check_cell");

        for (var check : List.of(LINT, GATE)) {
            store.checks()
                 .cells(pr(1), sha('a'), List.of(cell(check, "success"), cell(check, "failure")), T2)
                 .await(KilnPgFixture.WAIT)
                 .onSuccess(changes -> fail("expected a duplicate " + check + " to fail, got " + changes));

            assertThat(dump(store, "check_cell")).isEqualTo(before);
        }
    }

    private PrView view() {
        return await(store.views().openPrs()).getFirst();
    }

    private static Cell cell(RequiredCheck check, String value) {
        return new Cell(check, value, 1, List.of(value, "neutral"));
    }
}
