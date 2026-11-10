package org.pragmatica.kiln.service.store.pg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.BotIssue;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.service.api.FeedReply;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.vo.Uuid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pullRequest;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// kiln's restore detection (lead ruling, verifier NOTE-1 and R2-F1..F3; applied to kiln): the high-water
/// file holds `<epoch> <last id>` after each committed change batch, and a start whose database is behind the file,
/// foreign to it, with a file that does not parse, cannot be read or written, or is missing after ids were handed out
/// rotates the epoch. While running, a sequence below the last id seen in the epoch rotates it (a restore under a running
/// kiln, by a real `pg_dump` / `pg_restore --clean`). A normal restart, a first start (file missing, sequence unused) and
/// a file behind the database keep it. An unusable file never stops kiln; its `lastOkAt` stops advancing.
/// Probe: [HighWater#regressed] answering false → `restoredOlderDump_rotatesTheEpochAtStart` red.
@Tag("pg")
class HighWaterIT {
    private DbConfig config;
    private Path file;
    private KilnStore store;
    private int ticks;

    @BeforeEach
    void start() {
        config = KilnPgFixture.freshDatabase();
        file = StoreFixtures.highWater();
        store = open();
    }

    @AfterEach
    void stop() {
        await(store.close());
    }

    @Test
    void firstStart_writesTheMark_andABatchRewritesIt() throws IOException {
        var epoch = epoch();

        assertThat(Files.readString(file)).isEqualTo(epoch + " 0\n");
        changes(3);
        assertThat(Files.readString(file)).isEqualTo(epoch + " 3\n");
    }

    @Test
    void restart_keepsTheEpoch() {
        changes(2);
        var epoch = epoch();

        reopen();

        assertThat(epoch()).isEqualTo(epoch);
    }

    /// A restore of a dump taken at id 2, after ids 3..5 were handed out: the sequence and the rows go back, the epoch
    /// row is the dump's.
    @Test
    void restoredOlderDump_rotatesTheEpochAtStart() throws IOException {
        changes(5);
        var epoch = epoch();
        StoreFixtures.execute(store, "DELETE FROM change WHERE id > 2");
        await(Sql.query(((PgKilnStore) store).db().executor(), "SELECT setval('change_id_seq', 2)"));

        reopen();

        assertThat(epoch()).isNotEqualTo(epoch);
        assertThat(Files.readString(file)).isEqualTo(epoch() + " 2\n");
    }

    @Test
    void foreignOrUnreadableFile_rotatesTheEpochAtStart() throws IOException {
        var epoch = epoch();
        Files.writeString(file, UUID.randomUUID() + " 0\n");
        reopen();
        var afterForeign = epoch();
        Files.writeString(file, "not a mark\n");
        reopen();

        assertThat(afterForeign).isNotEqualTo(epoch);
        assertThat(epoch()).isNotEqualTo(afterForeign);
    }

    /// A crash between a commit and the rename leaves the file behind the database: normal, the epoch stays.
    @Test
    void fileBehindTheDatabase_keepsTheEpoch() throws IOException {
        var epoch = epoch();
        changes(3);
        Files.writeString(file, epoch + " 1\n");

        reopen();

        assertThat(epoch()).isEqualTo(epoch);
    }

    /// R2-F1, by the verifier's method: a real `pg_restore --clean` of a dump taken at id 2, under the running store, after
    /// ids 3..5 were handed out. The next read rotates the epoch, so a reader at cursor 5 of the old one is told to reset;
    /// the file carries the new epoch, so a restart keeps it (the evidence is not erased). Probe: [HighWater#behind]
    /// answering false → red.
    @Test
    void restoreUnderARunningKiln_theNextReadIsAReset_andARestartKeepsTheNewEpoch() throws IOException {
        changes(2);
        var epoch = epoch();
        KilnPgFixture.dump(config, dumpPath());
        changes(5);

        KilnPgFixture.restore(config, dumpPath());
        var reply = FeedReply.reply(some(Uuid.uuid(epoch).unwrap()), 7, await(store.changes().feed(7, 10)));

        assertThat(reply).isInstanceOf(FeedReply.Reset.class);
        var rotated = epoch();
        assertThat(rotated).isNotEqualTo(epoch);
        assertThat(Files.readString(file)).isEqualTo(rotated + " 2\n");
        reopen();
        assertThat(epoch()).isEqualTo(rotated);
    }

    /// R2-F1, the write side: PRs #1..#7 open, then closed (ids up to `dumped`), dumped; #1 reopened (`dumped` + 1, the
    /// mark kiln saw); the dump restored under the running store; then, before any read, one batch reopens all seven and
    /// hands out `dumped` + 1..7, past the old mark. The check inside the writing transaction sees the sequence below the
    /// mark before an id is drawn and rotates, so a reader at the old mark is told to reset. Probe: `mark.verified(tx)`
    /// removed from `PgChangeStore.guardedWrite` → red (the check after the batch sees `dumped` + 7 above the mark).
    @Test
    void restoreUnderARunningKiln_thenABatchPastTheOldMark_beforeAnyRead_stillRotates() {
        upsert(PrState.OPEN, 1, 2, 3, 4, 5, 6, 7);
        upsert(PrState.CLOSED, 1, 2, 3, 4, 5, 6, 7);
        var epoch = epoch();
        var dumped = await(store.changes().feed(0, 1)).head();
        KilnPgFixture.dump(config, dumpPath());
        upsert(PrState.OPEN, 1);

        KilnPgFixture.restore(config, dumpPath());
        upsert(PrState.OPEN, 1, 2, 3, 4, 5, 6, 7);

        var feed = await(store.changes().feed(dumped + 1, 10));
        assertThat(feed.head()).isEqualTo(dumped + 7);
        assertThat(FeedReply.reply(some(Uuid.uuid(epoch).unwrap()), dumped + 1, feed)).isInstanceOf(FeedReply.Reset.class);
    }

    /// The same, with a feed-only batch (CI interface T1 WP2): after the restore, seven bot issues first seen give seven
    /// `BOT_ISSUE_OPENED` rows past the old mark, and nothing else is written. The feed's insert path runs the same check
    /// ([PgChangeStore#guardedWrite]), so the epoch rotates. Probe: the check skipped in the feed path → red.
    @Test
    void restoreUnderARunningKiln_thenAFeedOnlyBatchPastTheOldMark_stillRotates() {
        upsert(PrState.OPEN, 1, 2, 3, 4, 5, 6, 7);
        upsert(PrState.CLOSED, 1, 2, 3, 4, 5, 6, 7);
        var epoch = epoch();
        var dumped = await(store.changes().feed(0, 1)).head();
        KilnPgFixture.dump(config, dumpPath());
        upsert(PrState.OPEN, 1);

        KilnPgFixture.restore(config, dumpPath());
        var issues = IntStream.rangeClosed(100, 106)
                              .mapToObj(number -> new BotIssue(number, "nightly " + number, "https://x/" + number, T0))
                              .toList();
        await(store.feed().issues(StoreFixtures.REPO, issues, T0.plusSeconds(7_200)));

        var feed = await(store.changes().feed(dumped + 1, 10));
        assertThat(feed.head()).isEqualTo(dumped + 7);
        assertThat(FeedReply.reply(some(Uuid.uuid(epoch).unwrap()), dumped + 1, feed)).isInstanceOf(FeedReply.Reset.class);
    }

    /// R2-F2: a missing file after the sequence handed out ids is unknown history (a new host, a wiped home): rotate.
    @Test
    void missingFile_afterIdsWereHandedOut_rotatesTheEpochAtStart() throws IOException {
        changes(3);
        var epoch = epoch();
        await(store.close());
        Files.delete(file);

        store = open();

        assertThat(epoch()).isNotEqualTo(epoch);
        assertThat(Files.readString(file)).isEqualTo(epoch() + " 3\n");
    }

    /// R2-F2's other half: a missing file with a sequence that never handed out an id is a first start; the epoch stays.
    @Test
    void missingFile_withAnUnusedSequence_keepsTheEpoch() throws IOException {
        var epoch = epoch();
        await(store.close());
        Files.delete(file);

        store = open();

        assertThat(epoch()).isEqualTo(epoch);
    }

    /// R2-F3: an unreadable file never refuses the start; it is unknown, so the epoch rotates.
    @Test
    void unreadableFile_startsAndRotatesTheEpoch() throws IOException {
        changes(1);
        var epoch = epoch();
        await(store.close());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        Assumptions.assumeFalse(Files.isReadable(file), "the tests run as a user that reads any file");

        try {
            store = open();

            assertThat(epoch()).isNotEqualTo(epoch);
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /// R2-F3: an unwritable file never refuses the start. A good file says the database is the same, yet the epoch
    /// rotates, since the next start could not tell a restore; `kiln:highwater` has no `lastOkAt` (stale from the start),
    /// also after a batch.
    @Test
    void unwritableFile_startsRotated_andHighWaterHasNoLastOkAt() throws IOException {
        changes(2);
        var epoch = epoch();
        await(store.close());
        readOnly(file.getParent());

        try {
            store = open();
            changes(2);

            assertThat(epoch()).isNotEqualTo(epoch);
            assertThat(await(store.changes().feed(0, 1)).highWaterOkAt()).isEqualTo(none());
        } finally {
            writable(file.getParent());
        }
    }

    /// R2-F3: `lastOkAt` is the last successful write. It advances with each batch, stops while the file cannot be
    /// written (the reader sees it go stale), and advances again once it can.
    @Test
    void highWaterLastOkAt_advancesOnlyOnASuccessfulWrite() throws IOException, InterruptedException {
        var first = lastOkAt();
        Thread.sleep(5);
        changes(1);
        var second = lastOkAt();
        readOnly(file.getParent());

        try {
            Thread.sleep(5);
            changes(2);
            var stuck = lastOkAt();
            writable(file.getParent());
            Thread.sleep(5);
            changes(3);

            assertThat(second).isAfter(first);
            assertThat(stuck).isEqualTo(second);
            assertThat(lastOkAt()).isAfter(second);
        } finally {
            writable(file.getParent());
        }
    }

    /// A read within [HighWater#REWRITE] of the last write of an unchanged mark does not write again (one write per
    /// period, not one per read). PIT: the attempt left unrecorded → every read rewrites → red.
    @Test
    void aReadWithinTheRewritePeriod_ofAnUnchangedMark_doesNotWrite() throws IOException {
        var written = lastOkAt();
        Files.delete(file);

        var read = lastOkAt();

        assertThat(read).isEqualTo(written);
        assertThat(file).doesNotExist();
    }

    @Test
    void behind_isOnlyASequenceBelowTheKnownMark_inTheSameEpoch() {
        var known = new HighWater.Mark(Uuid.randomUuid(), 5);

        assertThat(HighWater.behind(none(), known)).isFalse();
        assertThat(HighWater.behind(some(known), new HighWater.Mark(known.epoch(), 4))).isTrue();
        assertThat(HighWater.behind(some(known), known)).isFalse();
        assertThat(HighWater.behind(some(known), new HighWater.Mark(known.epoch(), 6))).isFalse();
        assertThat(HighWater.behind(some(known), new HighWater.Mark(Uuid.randomUuid(), 0))).isFalse();
    }

    @Test
    void later_keepsTheKnownMarkOnlyWhenAheadInTheSameEpoch() {
        var known = new HighWater.Mark(Uuid.randomUuid(), 5);
        var behind = new HighWater.Mark(known.epoch(), 4);
        var ahead = new HighWater.Mark(known.epoch(), 6);
        var other = new HighWater.Mark(Uuid.randomUuid(), 1);

        assertThat(HighWater.later(none(), behind)).isEqualTo(behind);
        assertThat(HighWater.later(some(known), behind)).isEqualTo(known);
        assertThat(HighWater.later(some(known), known)).isEqualTo(known);
        assertThat(HighWater.later(some(known), ahead)).isEqualTo(ahead);
        assertThat(HighWater.later(some(known), other)).isEqualTo(other);
    }

    @Test
    void due_isANewMark_orTheSameMarkAfterTheRewritePeriod() {
        var mark = new HighWater.Mark(Uuid.randomUuid(), 5);
        var at = Instant.parse("2026-11-03T10:00:00Z");
        Option<HighWater.Attempt> tried = some(new HighWater.Attempt(mark, at));

        assertThat(HighWater.due(none(), mark, at)).isTrue();
        assertThat(HighWater.due(tried, mark, at.plus(HighWater.REWRITE).minusMillis(1))).isFalse();
        assertThat(HighWater.due(tried, mark, at.plus(HighWater.REWRITE))).isTrue();
        assertThat(HighWater.due(tried, new HighWater.Mark(mark.epoch(), 6), at)).isTrue();
    }

    @Test
    void regressed_isOnlyAForeignAheadOrUnreadableMark_orAMissingOneAfterIdsWereHandedOut() {
        var database = new HighWater.Mark(Uuid.randomUuid(), 5);
        var line = database.line();

        assertThat(HighWater.regressed(none(), database)).isTrue();
        assertThat(HighWater.regressed(none(), new HighWater.Mark(database.epoch(), 0))).isFalse();
        assertThat(HighWater.regressed(some(line), database)).isFalse();
        assertThat(HighWater.regressed(some(database.epoch().value() + " 4"), database)).isFalse();
        assertThat(HighWater.regressed(some(database.epoch().value() + " 6"), database)).isTrue();
        assertThat(HighWater.regressed(some(UUID.randomUUID() + " 5"), database)).isTrue();
        assertThat(HighWater.regressed(some("x"), database)).isTrue();
        assertThat(HighWater.regressed(some(database.epoch().value() + " 5 7"), database)).isTrue();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private KilnStore open() {
        return await(PgKilnStore.connect(config, StoreFixtures.REPO, file, ChangeDiff.CHANGES));
    }

    private Instant lastOkAt() {
        return await(store.changes().feed(0, 1)).highWaterOkAt()
                                                 .unwrap();
    }

    private String dumpPath() {
        return "/tmp/" + config.database() + ".dump";
    }

    /// Read and enter only: no file can be created in `dir`, so the write beside the file fails.
    private static void readOnly(Path dir) throws IOException {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        Assumptions.assumeFalse(Files.isWritable(dir), "the tests run as a user that writes any directory");
    }

    private static void writable(Path dir) throws IOException {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }

    private void reopen() {
        await(store.close());
        store = open();
    }

    private String epoch() {
        return await(store.changes().feed(0, 1)).epoch()
                                                .value()
                                                .toString();
    }

    /// PRs `numbers` observed in `state`, in one batch, each call one second after the last.
    private void upsert(PrState state, int... numbers) {
        var prs = Arrays.stream(numbers).mapToObj(number -> pullRequest(number, state, 'a')).toList();

        await(store.prs().upsert(prs, T0.plusSeconds(3_600 + ticks++)));
    }

    /// `count` PR_STATE changes (ids 1..count): PR #1 seen open, then closed and reopened in turn.
    private void changes(int count) {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));
        for (int n = 1; n <= count; n++) {
            await(store.prs().upsert(List.of(pullRequest(1, n % 2 == 1 ? PrState.CLOSED : PrState.OPEN, 'a')), T0.plusSeconds(n)));
        }
    }
}
