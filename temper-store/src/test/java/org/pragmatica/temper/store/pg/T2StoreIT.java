package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.store.api.ControlPath;
import org.pragmatica.temper.store.api.DetectorBatch;
import org.pragmatica.temper.store.api.DetectorInput;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.MergeFacts;
import org.pragmatica.temper.store.api.MergeKey;
import org.pragmatica.temper.store.api.MergeRow;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.NewVerdict;
import org.pragmatica.temper.store.api.QueueEntry;
import org.pragmatica.temper.store.api.QueuedPr;
import org.pragmatica.temper.store.api.RegisteredVerdict;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.T2Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The T2 store half: V14's detector row and seeds, the merge facts the detector
/// reads under the T2 lock (the R3-2.c/d gating rule lives in this SQL), and the batch writes (`merges`, dense queue
/// positions, cursor and `last_ok_at`, events).
@Tag("pg")
class T2StoreIT {
    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private static final String SHA = "a".repeat(40);
    private static final String OTHER = "b".repeat(40);
    private static final String TIP = "c".repeat(40);
    private StoreHarness harness;
    private T2Store t2;
    private final List<Db> extra = new ArrayList<>();

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        t2 = harness.store.t2();
    }

    @AfterEach
    void tearDown() {
        extra.forEach(db -> db.close().await(PgFixture.WAIT));
        if (harness != null) {
            harness.close();
        }
    }

    // ---- V14 ---------------------------------------------------------------------------------------------------
    /// C1: the detector starts at `GREATEST(max(seq), floor)` of the stream it found, with `started_at`, period 5.
    @Test
    void v14_onAStreamWithRows_seedsTheDetectorCursorAtTheHighestSeq() {
        assertThat(detectorAfterV14(3, 0)).isEqualTo("3/5/true");
    }

    @Test
    void v14_withTheFloorAboveEveryRow_seedsTheDetectorCursorAtTheFloor() {
        assertThat(detectorAfterV14(2, 40)).isEqualTo("40/5/true");
    }

    @Test
    void v14_seedsTheControlPaths_andRefusesAnEscapingGlob() {
        assertThat(text("SELECT string_agg(repo || ' ' || glob, ',' ORDER BY repo, glob) AS v FROM ci_control_paths"))
            .isEqualTo("pragmaticalabs/pragmatica .github/**,pragmaticalabs/pragmatica .mvn/**,pragmaticalabs/pragmatica pom.xml,"
                       + "pragmaticalabs/pragmatica scripts/changelog-check.sh,pragmaticalabs/pragmatica tools/**,"
                       + "pragmaticalabs/temper-control .github/**");
        assertThat(execute("INSERT INTO ci_control_paths (repo, glob, added_by) VALUES ('o/r', 'a/../b', 'x')").isFailure()).isTrue();
        assertThat(execute("INSERT INTO ci_control_paths (repo, glob, added_by) VALUES ('o/r', '/abs', 'x')").isFailure()).isTrue();
        assertThat(execute("INSERT INTO merges (repo, pr, sha, base, route) VALUES ('o/r', 1, 'short', 'main', 'kiln')").isFailure())
            .as("a merges sha is 40 hex or unknown").isTrue();
        assertThat(execute("INSERT INTO merges (repo, pr, sha, base, route) VALUES ('o/r', 1, 'unknown', 'main', 'kiln')").isSuccess()).isTrue();
    }

    // ---- facts -------------------------------------------------------------------------------------------------
    /// R3-2.c (store half): consumed alone, refused and merge-failed do not gate; `merging` and `merged` do.
    @Test
    void facts_onlyMergingOrMergedGates_consumedRefusedOrFailedDoNot() {
        go(7, SHA, "consumed");
        go(7, SHA, "refused");
        go(7, SHA, "merge-failed");
        assertThat(facts(7, SHA).gate()).as("no GO reached merging").isEqualTo(none());

        var merging = go(7, SHA, "merging");
        assertThat(facts(7, SHA).gate().map(MergeFacts.GatingGo::id)).isEqualTo(some(merging));
        assertThat(facts(7, SHA).gate().flatMap(MergeFacts.GatingGo::baseTipBefore)).isEqualTo(some(TIP));
    }

    @Test
    void facts_mergedGates() {
        var merged = go(7, SHA, "merged");

        assertThat(facts(7, SHA).gate().map(MergeFacts.GatingGo::id)).isEqualTo(some(merged));
    }

    /// R3-2.d (store half): a GO at another sha does not gate; with the sha unknown, any GO of the PR does; another
    /// PR's GO never does.
    @Test
    void facts_aGoAtAnotherSha_gatesOnlyAnUnknownSha() {
        var other = go(7, OTHER, "merged");
        go(8, SHA, "merged");

        assertThat(facts(7, SHA).gate()).isEqualTo(none());
        assertThat(facts(7, MergeKey.UNKNOWN).gate().map(MergeFacts.GatingGo::id)).isEqualTo(some(other));
        assertThat(facts(9, MergeKey.UNKNOWN).gate()).as("another PR's GO").isEqualTo(none());
    }

    @Test
    void facts_twoGatingGos_theNewestGates() {
        go(7, SHA, "merging", "now() - interval '2 minutes'");
        var newest = go(7, SHA, "merged", "now() - interval '1 minute'");
        go(7, SHA, "merging", "now() - interval '3 minutes'");

        assertThat(facts(7, SHA).gate().map(MergeFacts.GatingGo::id)).isEqualTo(some(newest));
    }

    @Test
    void facts_recordedForThePr_queueForTheKeyOnly() {
        execute("INSERT INTO merges (repo, pr, sha, base, route) VALUES ('o/r', 7, '" + SHA + "', 'main', 'kiln')").unwrap();
        queue("o/r", "main", 5, 7, 9);
        queue("o/r", "dev", 11);
        queue("x/y", "main", 7);

        assertThat(facts(7, SHA).recorded()).isTrue();
        assertThat(facts(7, OTHER).recorded()).as("another sha: a PR merges once (R3-2.i, WP-0T item 8)").isTrue();
        assertThat(facts(7, MergeKey.UNKNOWN).recorded()).as("sha unknown").isTrue();
        assertThat(facts(8, SHA).recorded()).as("another PR").isFalse();
        assertThat(facts(7, SHA).queue()).containsExactly(QueueEntry.queueEntry("o/r", "main", 1, 5),
                                                          QueueEntry.queueEntry("o/r", "main", 2, 7),
                                                          QueueEntry.queueEntry("o/r", "main", 3, 9));
        assertThat(facts(12, SHA).queue()).as("not queued").isEmpty();
    }

    // ---- input and writes --------------------------------------------------------------------------------------
    @Test
    void detect_handsThePlanTheCursorStartedAtAndTheRowsAfterIt() {
        harness.store.fleet().append(List.of(event("one"), event("two"), event("three"))).await(PgFixture.WAIT).unwrap();
        execute("UPDATE fleet_source SET cursor = 1 WHERE name = 't2:detector'").unwrap();
        var seen = new AtomicReference<DetectorInput>();

        t2.detect(2, (input, _) -> seenThen(seen, input, batch(9, List.of(), List.of(), List.of()))).await(PgFixture.WAIT).unwrap();

        assertThat(seen.get().cursor()).isEqualTo(1);
        assertThat(seen.get().startedAt()).isNotEqualTo(none());
        assertThat(seen.get().pending()).isInstanceOfSatisfying(FleetPending.Events.class,
                                                                events -> assertThat(events.rows()).extracting(row -> row.seq()).containsExactly(2L, 3L));
        assertThat(text("SELECT cursor || ' ' || (last_ok_at = '" + T0 + "'::timestamptz) AS v FROM fleet_source WHERE name = 't2:detector'"))
            .isEqualTo("9 true");
    }

    @Test
    void detect_floorAboveTheCursor_handsThePlanTheGap() {
        execute("UPDATE fleet_floor SET floor_seq = 30").unwrap();
        var seen = new AtomicReference<DetectorInput>();

        t2.detect(500, (input, _) -> seenThen(seen, input, batch(30, List.of(), List.of(), List.of()))).await(PgFixture.WAIT).unwrap();

        assertThat(seen.get().pending()).isEqualTo(new FleetPending.Gap(1, 30));
    }

    /// The batch's writes: the merge row, the queue removal with positions dense, the events last.
    @Test
    void detect_writesTheMergeTheDenseQueueAndTheEvents() {
        queue("o/r", "main", 5, 7, 9);
        var merge = MergeRow.mergeRow(MergeKey.mergeKey("o/r", 7, SHA), "main", none(), "kiln", some("octocat"), some(T0), some(4L));

        var seqs = t2.detect(500, (_, _) -> Promise.success(batch(4,
                                                                    List.of(merge),
                                                                    List.of(QueueEntry.queueEntry("o/r", "main", 2, 7)),
                                                                    List.of(event("ungated-merge"), event("queue-advanced")))))
                     .await(PgFixture.WAIT)
                     .unwrap();

        assertThat(seqs.map(range -> range.last() - range.first())).isEqualTo(some(1L));
        assertThat(text("SELECT repo || '#' || pr || '@' || sha || ' ' || base || ' ' || route || ' ' || coalesce(go_id::text, '-') || ' '"
                        + " || merged_by || ' ' || (merged_at = '" + T0 + "'::timestamptz) || ' ' || event_seq AS v FROM merges"))
            .isEqualTo("o/r#7@" + SHA + " main kiln - octocat true 4");
        assertThat(text("SELECT string_agg(position || ':' || pr, ',' ORDER BY position) AS v FROM merge_queue")).isEqualTo("1:5,2:9");
        assertThat(text("SELECT string_agg(kind, ',' ORDER BY seq) AS v FROM fleet_events")).isEqualTo("ungated-merge,queue-advanced");
    }

    @Test
    void detect_theHeadLeavingTheQueue_shiftsEveryLaterPosition() {
        queue("o/r", "main", 5, 7, 9);
        queue("o/r", "dev", 3);

        t2.detect(500, (_, _) -> Promise.success(batch(0, List.of(), List.of(QueueEntry.queueEntry("o/r", "main", 1, 5)), List.of())))
          .await(PgFixture.WAIT)
          .unwrap();

        assertThat(text("SELECT string_agg(base || ' ' || position || ':' || pr, ',' ORDER BY base, position) AS v FROM merge_queue"))
            .isEqualTo("dev 1:3,main 1:7,main 2:9");
    }

    /// A merge the plan read as new but the insert finds recorded rolls the whole batch back (nothing announced twice).
    @Test
    void detect_aMergeRecordedAlready_refusesTheBatch() {
        execute("INSERT INTO merges (repo, pr, sha, base, route) VALUES ('o/r', 7, '" + SHA + "', 'main', 'temper-merge')").unwrap();
        var merge = MergeRow.mergeRow(MergeKey.mergeKey("o/r", 7, SHA), "main", none(), "kiln", none(), none(), some(4L));

        var result = t2.detect(500, (_, _) -> Promise.success(batch(4, List.of(merge), List.of(), List.of(event("ungated-merge")))))
                       .await(PgFixture.WAIT);

        boolean refused = result.fold(cause -> cause == StoreError.General.MERGE_ALREADY_RECORDED, _ -> false);
        assertThat(refused).isTrue();
        assertThat(text("SELECT count(*)::text AS v FROM fleet_events")).isEqualTo("0");
        assertThat(text("SELECT cursor::text AS v FROM fleet_source WHERE name = 't2:detector'")).isEqualTo("0");
    }

    /// The T2 lock: a batch waits for another T2 transaction (here one holding the lock 1.5 s) before it reads.
    @Test
    void detect_waitsForTheT2Lock() throws Exception {
        var holder = PgFixture.db(harness.config);
        extra.add(holder);
        var released = new AtomicReference<Instant>();
        var held = CompletableFuture.runAsync(() -> holder.inTransaction(tx -> Sql.execute(tx, "SELECT pg_advisory_xact_lock($1)", PgT2Store.T2_LOCK)
                                                                                  .flatMap(_ -> Sql.execute(tx, "SELECT pg_sleep(1.5)"))
                                                                                  .map(_ -> {
                                                                                      released.set(Instant.now());
                                                                                      return 0;
                                                                                  }))
                                                       .await(PgFixture.WAIT)
                                                       .unwrap());
        Thread.sleep(300);
        var read = new AtomicReference<Instant>();

        t2.detect(500, (_, _) -> {
              read.set(Instant.now());
              return Promise.success(batch(0, List.of(), List.of(), List.of()));
          })
          .await(PgFixture.WAIT)
          .unwrap();
        held.get();

        assertThat(read.get()).as("the batch read only after the holder's lock was released").isAfterOrEqualTo(released.get());
    }

    /// Verifier probe (WP-0T ruling 6): `merged` is preferred over a newer `merging`.
    @Test
    void vprobe_facts_mergedIsPreferredOverANewerMerging() {
        var merged = go(7, SHA, "merged", "now() - interval '2 minutes'");
        go(7, SHA, "merging", "now() - interval '1 minute'");

        assertThat(facts(7, SHA).gate().map(MergeFacts.GatingGo::id)).isEqualTo(some(merged));
    }

    /// Verifier probe: a failure in the last step (the events insert) rolls back the merge, the queue and the cursor.
    @Test
    void vprobe_detect_aFailureInTheLastStep_rollsBackTheMergeTheQueueAndTheCursor() {
        queue("o/r", "main", 7, 9);
        var merge = MergeRow.mergeRow(MergeKey.mergeKey("o/r", 7, SHA), "main", none(), "kiln", none(), none(), some(4L));
        var bad = NewFleetEvent.newFleetEvent(T0, "controller", "ungated-merge", "o/r#7", "not json");

        var result = t2.detect(500, (_, _) -> Promise.success(batch(4, List.of(merge), List.of(QueueEntry.queueEntry("o/r", "main", 1, 7)), List.of(bad))))
                       .await(PgFixture.WAIT);

        assertThat(result.isFailure()).isTrue();
        assertThat(text("SELECT count(*)::text AS v FROM merges")).isEqualTo("0");
        assertThat(text("SELECT string_agg(position || ':' || pr, ',' ORDER BY position) AS v FROM merge_queue")).isEqualTo("1:7,2:9");
        assertThat(text("SELECT cursor::text AS v FROM fleet_source WHERE name = 't2:detector'")).isEqualTo("0");
        assertThat(text("SELECT count(*)::text AS v FROM fleet_events")).isEqualTo("0");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------
    /// Migrates a fresh database below V14, writes `rows` fleet events and the floor, then applies V14; answers the
    /// detector row as `cursor/period/started`.
    private String detectorAfterV14(int rows, long floor) {
        var config = PgFixture.freshDatabase();
        var raw = PgFixture.db(config);
        extra.add(raw);
        MigrationRunner.migrate(raw,
                                MigrationRunner.MIGRATIONS.stream()
                                                          .filter(migration -> migration.version() < 14)
                                                          .map(migration -> MigrationRunner.load(migration).unwrap())
                                                          .toList())
                       .await(PgFixture.WAIT)
                       .unwrap();
        for (var i = 0; i < rows; i++) {
            Sql.execute(raw.executor(),
                        "INSERT INTO fleet_events (epoch, observed_at, source, kind, subject, payload) SELECT id, now(), 'x', 'k', 's', '{}' FROM temper_epoch")
               .await(PgFixture.WAIT)
               .unwrap();
        }
        Sql.execute(raw.executor(), "UPDATE fleet_floor SET floor_seq = " + floor).await(PgFixture.WAIT).unwrap();

        try (var _ = Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap()) {
            return Sql.query(raw.executor(),
                             "SELECT cursor || '/' || period_s || '/' || (started_at IS NOT NULL AND last_ok_at IS NOT NULL) AS v FROM fleet_source WHERE name = 't2:detector'")
                      .map(resultSet -> Sql.first(resultSet).unwrap().getString("v"))
                      .await(PgFixture.WAIT)
                      .unwrap();
        }
    }

    private static Promise<DetectorBatch> seenThen(AtomicReference<DetectorInput> seen, DetectorInput input, DetectorBatch batch) {
        seen.set(input);
        return Promise.success(batch);
    }

    private static DetectorBatch batch(long cursor, List<MergeRow> merges, List<QueueEntry> dequeued, List<NewFleetEvent> events) {
        return DetectorBatch.detectorBatch(cursor, T0, merges, dequeued, events);
    }

    private MergeFacts facts(long pr, String sha) {
        var facts = new AtomicReference<MergeFacts>();

        t2.detect(500, (_, reader) -> reader.facts(MergeKey.mergeKey("o/r", pr, sha))
                                           .map(read -> {
                                               facts.set(read);
                                               return batch(0, List.of(), List.of(), List.of());
                                           }))
          .await(PgFixture.WAIT)
          .unwrap();

        return facts.get();
    }

    private String go(long pr, String sha, String outcome) {
        return go(pr, sha, outcome, "now()");
    }

    /// A GO row inserted directly (the GO routes come with WP-A2), consumed with `outcome`.
    // ---- WP-A1: verdicts, queue edits -------------------------------------------------------------------
    /// One transaction: an event the order-locked insert refuses (a payload that is not JSON) rolls back the verdict
    /// row and the GO invalidation with it.
    @Test
    void register_isOneTransaction_aFailedEventInsertKeepsNothing() {
        execute("INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256) VALUES ('o/r', 7, '" + SHA
                + "', 'main', 'ACCEPT', 'tester', '/r.md', '" + "d".repeat(64) + "')").unwrap();
        execute("INSERT INTO go (repo, pr, sha, base, base_tip, verdict_id, fleet_seq) VALUES ('o/r', 7, '" + SHA + "', 'main', '" + TIP
                + "', (SELECT max(id) FROM verdicts), 0)").unwrap();
        var verdict = NewVerdict.newVerdict("o/r", 7, SHA, "main", "FIX", "agent", new NewVerdict.Basis.Report("/r2.md", "e".repeat(64)));

        var failed = t2.register(verdict, registered -> List.of(NewFleetEvent.newFleetEvent(T0, "controller", "verdict-registered", "o/r#7", "not json")))
                       .await(PgFixture.WAIT);

        assertThat(failed.isFailure()).isTrue();
        assertThat(text("SELECT count(*)::text AS v FROM verdicts")).isEqualTo("1");
        assertThat(text("SELECT count(*)::text AS v FROM go WHERE invalidated_at IS NULL")).isEqualTo("1");

        var events = new AtomicReference<RegisteredVerdict>();
        var committed = t2.register(verdict, registered -> {
                              events.set(registered);
                              return List.of(event("verdict-registered"));
                          })
                          .await(PgFixture.WAIT)
                          .unwrap();

        assertThat(committed.value()).isEqualTo(events.get());
        assertThat(committed.value().invalidated()).singleElement().satisfies(go -> assertThat(go.sha() + " " + go.base()).isEqualTo(SHA + " main"));
        assertThat(committed.seqs()).matches(seqs -> seqs.isPresent());
        assertThat(text("SELECT count(*)::text AS v FROM go WHERE reason = 'verdict-changed'")).isEqualTo("1");
    }

    /// The carry SQL: the LATEST row at the carried SHA decides (an ACCEPT under a later REJECT is not carried).
    @Test
    void register_carry_readsTheLatestRowAtTheCarriedSha() {
        var report = new NewVerdict.Basis.Report("/r.md", "d".repeat(64));
        var carry = NewVerdict.newVerdict("o/r", 7, OTHER, "main", "ACCEPT", "agent", new NewVerdict.Basis.Carried(SHA));
        t2.register(NewVerdict.newVerdict("o/r", 7, SHA, "main", "ACCEPT", "agent", report), _ -> List.of()).await(PgFixture.WAIT).unwrap();
        t2.register(NewVerdict.newVerdict("o/r", 7, SHA, "main", "REJECT", "agent", report), _ -> List.of()).await(PgFixture.WAIT).unwrap();

        assertThat(t2.register(carry, _ -> List.of()).await(PgFixture.WAIT).<Object> fold(cause -> cause, value -> value))
            .isInstanceOf(StoreError.CarryNoAccept.class);
        assertThat(t2.register(NewVerdict.newVerdict("o/r", 8, OTHER, "main", "ACCEPT", "agent", new NewVerdict.Basis.Carried(SHA)), _ -> List.of())
                     .await(PgFixture.WAIT)
                     .isFailure()).as("another PR's row is not carried").isTrue();
    }

    /// Moves in both directions and to either end keep the positions dense; the base filter and the range refuse.
    @Test
    void move_keepsPositionsDense_andRefusesOutOfRangeOrOtherBase() {
        queue("o/r", "main", 1, 2, 3, 4);

        assertThat(t2.move("o/r", 4, 1, none()).await(PgFixture.WAIT).unwrap()).isEqualTo(QueueEntry.queueEntry("o/r", "main", 1, 4));
        assertThat(order()).isEqualTo("4,1,2,3");
        t2.move("o/r", 4, 4, some("main")).await(PgFixture.WAIT).unwrap();
        assertThat(order()).isEqualTo("1,2,3,4");
        t2.move("o/r", 2, 3, none()).await(PgFixture.WAIT).unwrap();
        assertThat(order()).isEqualTo("1,3,2,4");
        assertThat(t2.move("o/r", 2, 5, none()).await(PgFixture.WAIT).<Object> fold(cause -> cause, value -> value))
            .isEqualTo(StoreError.PositionOutOfRange.FACTORY.apply(5, 4));
        assertThat(t2.move("o/r", 2, 1, some("other")).await(PgFixture.WAIT).<Object> fold(cause -> cause, value -> value))
            .isInstanceOf(StoreError.NotQueued.class);
        assertThat(t2.dequeue("o/r", 3, some("other")).await(PgFixture.WAIT).isFailure()).isTrue();
        assertThat(t2.dequeue("o/r", 3, none()).await(PgFixture.WAIT).unwrap()).isEqualTo(QueueEntry.queueEntry("o/r", "main", 2, 3));
        assertThat(order()).isEqualTo("1,2,4");
        assertThat(text("SELECT string_agg(position::text, ',' ORDER BY position) AS v FROM merge_queue")).isEqualTo("1,2,3");
    }

    /// Appends at n+1 per (repo, base), refuses a second add of a PR; the listing carries the newest outstanding GO.
    @Test
    void enqueue_appends_refusesTwice_andQueuesListTheOutstandingGo() {
        assertThat(t2.enqueue("o/r", "main", 7, "agent").await(PgFixture.WAIT).unwrap()).isEqualTo(QueueEntry.queueEntry("o/r", "main", 1, 7));
        assertThat(t2.enqueue("o/r", "main", 8, "agent").await(PgFixture.WAIT).unwrap()).isEqualTo(QueueEntry.queueEntry("o/r", "main", 2, 8));
        assertThat(t2.enqueue("o/r", "rel", 9, "agent").await(PgFixture.WAIT).unwrap()).isEqualTo(QueueEntry.queueEntry("o/r", "rel", 1, 9));
        assertThat(t2.enqueue("o/r", "rel", 8, "agent").await(PgFixture.WAIT).<Object> fold(cause -> cause, value -> value))
            .isEqualTo(StoreError.AlreadyQueued.FACTORY.apply("main", 2));
        execute("INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256) VALUES ('o/r', 8, '" + SHA
                + "', 'main', 'ACCEPT', 'tester', '/r.md', '" + "d".repeat(64) + "')").unwrap();
        var open = text("INSERT INTO go (repo, pr, sha, base, base_tip, verdict_id, fleet_seq) VALUES ('o/r', 8, '" + SHA + "', 'main', '" + TIP
                        + "', (SELECT max(id) FROM verdicts), 0) RETURNING id::text AS v");

        var all = t2.queues(none(), none()).await(PgFixture.WAIT).unwrap();

        assertThat(all).extracting(QueuedPr::entry)
                       .containsExactly(QueueEntry.queueEntry("o/r", "main", 1, 7), QueueEntry.queueEntry("o/r", "main", 2, 8),
                                        QueueEntry.queueEntry("o/r", "rel", 1, 9));
        assertThat(all.getFirst().go()).isEqualTo(none());
        assertThat(all.getFirst().addedBy()).isEqualTo("agent");
        assertThat(all.get(1).go().map(QueuedPr.OpenGo::id)).isEqualTo(some(open));
        assertThat(all.get(1).go().map(QueuedPr.OpenGo::sha)).isEqualTo(some(SHA));
        assertThat(t2.queues(some("o/r"), some("rel")).await(PgFixture.WAIT).unwrap()).extracting(QueuedPr::entry)
                                                                                     .containsExactly(QueueEntry.queueEntry("o/r", "rel", 1, 9));
    }

    @Test
    void controlPaths_add_refusesTwice_remove_refusesMissing() {
        assertThat(t2.controlPaths("o/r").await(PgFixture.WAIT).unwrap()).isEmpty();
        assertThat(t2.addControlPath("o/r", "ci/**", "agent").await(PgFixture.WAIT).unwrap().glob()).isEqualTo("ci/**");
        assertThat(t2.addControlPath("o/r", "ci/**", "agent").await(PgFixture.WAIT).<Object> fold(cause -> cause, value -> value))
            .isEqualTo(StoreError.PathExists.FACTORY.apply("o/r", "ci/**"));
        assertThat(t2.controlPaths("o/r").await(PgFixture.WAIT).unwrap()).extracting(ControlPath::glob, ControlPath::addedBy)
                                                                          .containsExactly(tuple("ci/**", "agent"));
        assertThat(t2.removeControlPath("o/r", "ci/**").await(PgFixture.WAIT).isSuccess()).isTrue();
        assertThat(t2.removeControlPath("o/r", "ci/**").await(PgFixture.WAIT).<Object> fold(cause -> cause, value -> value))
            .isEqualTo(StoreError.PathNotFound.FACTORY.apply("o/r", "ci/**"));
        assertThat(t2.controlPaths("pragmaticalabs/temper-control").await(PgFixture.WAIT).unwrap()).extracting(ControlPath::glob)
                                                                                                    .containsExactly(".github/**");
    }

    private String order() {
        return text("SELECT string_agg(pr::text, ',' ORDER BY position) AS v FROM merge_queue WHERE repo = 'o/r' AND base = 'main'");
    }

    private String go(long pr, String sha, String outcome, String createdAt) {
        execute("INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256) VALUES ('o/r', " + pr + ", '"
                + sha + "', 'main', 'ACCEPT', 'tester', '/r.md', '" + "d".repeat(64) + "')").unwrap();
        return text("INSERT INTO go (repo, pr, sha, base, base_tip, verdict_id, fleet_seq, created_at, consumed_at, outcome, outcome_at, base_tip_before) "
                    + "VALUES ('o/r', " + pr + ", '" + sha + "', 'main', '" + TIP + "', (SELECT max(id) FROM verdicts), 0, " + createdAt + ", now(), '"
                    + outcome + "', now(), '" + TIP + "') RETURNING id::text AS v");
    }

    private void queue(String repo, String base, long... prs) {
        for (var i = 0; i < prs.length; i++) {
            execute("INSERT INTO merge_queue (repo, base, position, pr, added_by) VALUES ('" + repo + "', '" + base + "', " + (i + 1) + ", "
                    + prs[i] + ", 'tester')").unwrap();
        }
    }

    private static NewFleetEvent event(String kind) {
        return NewFleetEvent.newFleetEvent(T0, "controller", kind, "o/r#7", "{}");
    }

    private Result<Unit> execute(String sql) {
        return Sql.execute(harness.raw.executor(), sql).await(PgFixture.WAIT);
    }

    private String text(String sql) {
        return Sql.query(harness.raw.executor(), sql)
                  .map(resultSet -> Sql.first(resultSet).unwrap().getString("v"))
                  .await(PgFixture.WAIT)
                  .unwrap();
    }
}
