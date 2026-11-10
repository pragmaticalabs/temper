package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.core.summary.Counts;
import org.pragmatica.kiln.core.summary.SummaryText;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.branch;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.change;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.count;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.observed;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pr;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pullRequest;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;

/// [ChangeStore#watermark][org.pragmatica.kiln.service.store.ChangeStore#watermark] against real Postgres (lead
/// rulings, 2026-10-30): a summary cursor taken from the watermark never skips a change, even one whose transaction was
/// still open when the cursor was read, and even one whose caller stamped `observedAt` long before writing: the cursor is
/// the store's `recordedAt`.
///
/// Probes (WP5 state file): make [Watermark#watermark] return the clock whatever is in flight →
/// [#aStaleCallersWriteHeldOpenAcrossARead_isListedByTheNextReadFromThatReadsAsOf] red; stamp `recorded_at` with the
/// caller's `observedAt` → the same test red.
@Tag("pg")
class WatermarkIT {
    private static final Instant NOW = T0.plusSeconds(300);

    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private DbConfig config;
    private Db raw;
    private KilnStore store;

    @BeforeEach
    void store() {
        config = KilnPgFixture.freshDatabase();
        raw = KilnPgFixture.db(config);
        store = await(PgKilnStore.connect(config, StoreFixtures.REPO, StoreFixtures.highWater(), StoreFixtures.DIFF, clock::get));
    }

    @AfterEach
    void close() {
        if (store != null) {
            await(store.close());
            await(raw.close());
        }
    }

    /// A caller stamps `observedAt` 30 s in the past, and its write is held open by a row lock while a reader renders a
    /// summary. The reader's `asOf` stays below the write's `recordedAt`, so the next read from that `asOf` lists the
    /// change once it commits, though its `observedAt` lies before even the previous cursor.
    @Test
    void aStaleCallersWriteHeldOpenAcrossARead_isListedByTheNextReadFromThatReadsAsOf() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), NOW));
        var previous = store.changes().watermark();
        var locked = Promise.<Unit> promise();
        var release = Promise.<Unit> promise();
        var holder = raw.inTransaction(tx -> Sql.execute(tx, "SELECT 1 FROM pr WHERE number = 1 FOR UPDATE")
                                                 .flatMap(_ -> held(locked, release)));
        await(locked);
        clock.set(NOW.plusSeconds(10));

        var write = store.prs().upsert(List.of(pullRequest(1, PrState.MERGED, 'a')), NOW.plusSeconds(10).minusSeconds(30));
        var asOf = store.changes().watermark();
        var read = summary(previous, asOf);
        release.succeed(unit());
        await(holder);
        var written = await(write);
        var next = summary(asOf, store.changes().watermark());

        assertThat(asOf).isBefore(NOW.plusSeconds(10)).isAfterOrEqualTo(previous);
        assertThat(read).noneMatch(line -> line.contains("MERGED"));
        assertThat(observed(written)).containsExactly(change(NOW.minusSeconds(20), 1, ChangeKind.PR_STATE, "#1", "OPEN", "MERGED"));
        assertThat(written.getFirst().observedAt()).isBefore(previous);
        assertThat(written.getFirst().recordedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(next).contains(NOW.minusSeconds(20) + " #1 state: OPEN -> MERGED");
        assertThat(store.changes().watermark()).isEqualTo(NOW.plusSeconds(10));
    }

    /// The watermark never moves back when the clock does: after a step back it stays at the newest committed stamp, and
    /// a write begun then is recorded 1 µs above the watermark already handed out, so its holder still lists it.
    @Test
    void watermark_neverMovesBack_andAWriteAfterAClockStepBack_isRecordedAboveIt() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), NOW));
        var cursor = store.changes().watermark();
        clock.set(NOW.minusSeconds(60));

        var written = await(store.prs().upsert(List.of(pullRequest(1, PrState.MERGED, 'a')), NOW.minusSeconds(60)));

        assertThat(cursor).isEqualTo(NOW);
        assertThat(written.getFirst().recordedAt()).isEqualTo(NOW.plus(1, ChronoUnit.MICROS));
        assertThat(await(store.changes().since(cursor, 10))).containsExactlyElementsOf(written);
        assertThat(store.changes().watermark()).isEqualTo(NOW.plus(1, ChronoUnit.MICROS));
    }

    /// Idle, the watermark is the newest committed `recordedAt`, never the clock (lead ruling N1), and a restart starts
    /// it at `max(recorded_at)` (N2). Two changes with distinct stamps exist before the restart, and the clock has run
    /// 30 s past the newest when a reader takes its cursor; the store restarts with the clock 60 s behind and writes.
    /// The new change is listed from that cursor. A watermark taken from the clock (`NOW + 30 s`) or a start at
    /// `min(recorded_at)` would stamp it at or below the cursor, and it would never be listed.
    @Test
    void aRestartWithTheClockBehind_neverStampsAtOrBelowACursorHandedOutBefore() {
        clock.set(NOW.minusSeconds(30));
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), NOW.minusSeconds(30)));
        clock.set(NOW);
        await(store.prs().upsert(List.of(pullRequest(1, PrState.CLOSED, 'a')), NOW));
        clock.set(NOW.plusSeconds(30));
        var cursor = store.changes().watermark();
        var distinctStamps = count(store, "SELECT count(DISTINCT recorded_at) AS n FROM change");
        await(store.close());
        store = await(PgKilnStore.connect(config, StoreFixtures.REPO, StoreFixtures.highWater(), StoreFixtures.DIFF, () -> NOW.minusSeconds(60)));

        var restarted = store.changes().watermark();
        var written = await(store.prs().upsert(List.of(pullRequest(1, PrState.MERGED, 'a')), NOW.minusSeconds(60)));

        assertThat(distinctStamps).isEqualTo(2);
        assertThat(cursor).isEqualTo(NOW);
        assertThat(restarted).isEqualTo(NOW);
        assertThat(written.getFirst().recordedAt()).isEqualTo(NOW.plus(1, ChronoUnit.MICROS));
        assertThat(await(store.changes().since(cursor, 10))).containsExactlyElementsOf(written);
    }

    /// A write that fails leaves the in-flight set and never counts as committed: the watermark stays at the last
    /// committed stamp while it fails, and moves on with the next committed write instead of staying pinned below the
    /// failed stamp.
    @Test
    void aFailedWrite_leavesTheInFlightSet_andIsNeverCommitted() {
        await(store.prs().upsert(List.of(pullRequest(2, PrState.OPEN, 'a')), NOW));
        StoreFixtures.refuse(store, "pr", "title", "refused");
        var broken = new PullRequest(pr(1), "refused", PrState.OPEN, false, branch("main"), branch("feature-1"), none(), sha('a'),
                                     none(), none(), T0, List.of(), List.of(), List.of());
        clock.set(NOW.plusSeconds(60));

        store.prs()
             .upsert(List.of(broken), T0)
             .await(KilnPgFixture.WAIT)
             .onSuccess(changes -> fail("expected the PR write to fail, got " + changes));
        var afterFailure = store.changes().watermark();
        clock.set(NOW.plusSeconds(120));
        await(store.prs().upsert(List.of(pullRequest(2, PrState.CLOSED, 'a')), NOW.plusSeconds(120)));

        assertThat(afterFailure).isEqualTo(NOW);
        assertThat(store.changes().watermark()).isEqualTo(NOW.plusSeconds(120));
    }

    private List<String> summary(Instant since, Instant asOf) {
        return SummaryText.render(since, asOf, List.of(), await(store.changes().since(since, 100)), new Counts(0, 0), none())
                          .lines();
    }

    private static Promise<Unit> held(Promise<Unit> locked, Promise<Unit> release) {
        locked.succeed(unit());
        return release;
    }
}
