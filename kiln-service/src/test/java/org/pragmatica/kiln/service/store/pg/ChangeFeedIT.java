package org.pragmatica.kiln.service.store.pg;

import java.time.Duration;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.api.ApiRoutes;
import org.pragmatica.kiln.service.http.ApiRequest;
import org.pragmatica.kiln.service.http.ApiResponse;
import org.pragmatica.kiln.service.http.Router;
import org.pragmatica.kiln.service.scan.MacIngest;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.KilnStoreError;
import org.pragmatica.kiln.service.web.Periods;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.lang.Option.none;

/// `GET /api/v1/changes` over real Postgres and the real route: `after`/`max` paging, an empty
/// table (head 0), `reset` on another epoch, `gap` below the floor, each source's `lastOkAt` as stored, and the rebuild
/// control (review H4): a recreated schema has a new epoch, and a reader holding the old one is told to reset.
@Tag("pg")
class ChangeFeedIT {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final Instant NOW = T0.plusSeconds(3_600);
    /// GitHub's period as a rate limit stretched it: the feed still reports the configured 60 s (lead ruling).
    private static final Duration STRETCHED = Duration.ofSeconds(600);
    private static final String INSERT = "INSERT INTO change (observed_at, kind, pr, subject, before, after, recorded_at, repo, payload) "
                                       + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb)";

    private DbConfig config;
    private Path highWater;
    private KilnStore store;

    @BeforeEach
    void start() {
        config = KilnPgFixture.freshDatabase();
        highWater = StoreFixtures.highWater();
        store = open();
    }

    @AfterEach
    void stop() {
        await(store.close());
    }

    @Test
    void feed_pagesByAfterAndMax_ascending() {
        insertPrStates(5);

        var first = get(Map.of("after", "0", "max", "2"));
        var second = get(Map.of("after", "2", "max", "2"));
        var last = get(Map.of("after", "4"));
        var past = get(Map.of("after", "5"));

        assertThat(ids(first)).containsExactly(1L, 2L);
        assertThat(ids(second)).containsExactly(3L, 4L);
        assertThat(ids(last)).containsExactly(5L);
        assertThat(ids(past)).isEmpty();
        assertThat(List.of(first, second, last, past)).allSatisfy(answer -> assertThat(answer).containsEntry("head", 5)
                                                                                           .containsEntry("floor", 1)
                                                                                           .containsEntry("epoch", epoch())
                                                                                           .containsEntry("asOf", NOW.toString()));
        assertThat(get(Map.of())).containsEntry("changes", get(Map.of("after", "0", "max", "500")).get("changes"));
    }

    /// Every field of the feed row for a WP1 row (payload `null`) and for a row about no PR with a payload (WP2's shape): a kind
    /// this build has no constant for is passed through as stored, so a row a newer kiln wrote (a rollback) never fails
    /// the feed.
    @Test
    void feed_carriesEveryField_aPrlessRow_andItsPayload() {
        insert("PR_STATE", 7L, "#7", "open", "merged", null);
        insert("PUSH_RUN_CONCLUDED", null, "main", "in_progress", "success", "{\"run\":12,\"attempt\":2}");

        List<Map<String, Object>> changes = cast(get(Map.of()).get("changes"));

        assertThat(changes.get(0)).containsEntry("id", 1)
                                  .containsEntry("kind", "PR_STATE")
                                  .containsEntry("repo", StoreFixtures.REPO)
                                  .containsEntry("pr", 7)
                                  .containsEntry("subject", "#7")
                                  .containsEntry("before", "open")
                                  .containsEntry("after", "merged")
                                  .containsEntry("observedAt", T0.toString())
                                  .containsEntry("recordedAt", T1.toString())
                                  .containsEntry("payload", null);
        assertThat(changes.get(1)).containsEntry("kind", "PUSH_RUN_CONCLUDED")
                                  .containsEntry("pr", null)
                                  .containsEntry("payload", Map.of("run", 12, "attempt", 2));
    }

    @Test
    void feed_ofAnEmptyTable_hasHead0_andNoChanges() {
        var answer = get(Map.of());

        assertThat(answer).containsEntry("head", 0).containsEntry("floor", 1).containsEntry("changes", List.of());
        assertThat(UUID.fromString((String) answer.get("epoch"))).isNotNull();
    }

    @Test
    void feed_isAReset_whenTheCallerHoldsAnotherEpoch() {
        insertPrStates(3);
        var other = UUID.randomUUID().toString();

        var reset = get(Map.of("epoch", other, "after", "1"));
        var same = get(Map.of("epoch", epoch(), "after", "1"));

        assertThat(reset).containsOnlyKeys("reset", "head", "asOf", "sources")
                         .containsEntry("reset", Map.of("epoch", epoch()))
                         .containsEntry("head", 3);
        assertThat(ids(same)).containsExactly(2L, 3L);
    }

    /// `gap` exactly when `after < floor - 1`: a reader at `floor - 1` has missed nothing.
    @Test
    void feed_isAGap_whenTheCursorIsBelowTheFloor() {
        insertPrStates(5);
        await(store.changes().prune(T0.plusSeconds(4)));

        var gap = get(Map.of("after", "2"));
        var fromZero = get(Map.of("after", "0"));
        var atTheFloor = get(Map.of("after", "3"));

        assertThat(gap).containsOnlyKeys("gap", "epoch", "floor", "head", "asOf", "sources")
                       .containsEntry("gap", Map.of("from", 2, "to", 4))
                       .containsEntry("floor", 4)
                       .containsEntry("head", 5);
        assertThat(fromZero).containsEntry("gap", Map.of("from", 0, "to", 4));
        assertThat(ids(atTheFloor)).containsExactly(4L, 5L);
    }

    /// Every row pruned (lead ruling): `head` stays the last id handed out and `floor` is `head + 1`, so a cursor behind the
    /// pruned rows still reads a gap. Probe: `head` as `max(id)` → head 0, no gap, red.
    @Test
    void feed_ofAFullyPrunedTable_keepsItsHead_andTheGap() {
        insertPrStates(5);
        await(store.changes().prune(T0.plusSeconds(100)));

        var gap = get(Map.of("after", "2"));
        var caughtUp = get(Map.of("after", "5"));

        assertThat(gap).containsEntry("gap", Map.of("from", 2, "to", 6)).containsEntry("floor", 6).containsEntry("head", 5);
        assertThat(caughtUp).containsEntry("head", 5).containsEntry("floor", 6).containsEntry("changes", List.of());
    }

    /// Prune by id (lead ruling): `recorded_at` out of id order (a clock step back) still leaves no id below the floor, and
    /// the floor is the cutoff (the greatest id recorded before the prune instant) + 1. Probe: prune by `recorded_at` → ids
    /// 1 and 3 survive below the floor, red.
    @Test
    void prune_cutsById_soNoIdSurvivesBelowTheFloor() {
        List.of(5, 1, 9, 2, 8).forEach(seconds -> insertAt(T0.plusSeconds(seconds), "PR_STATE", 1L, "#1", "open", "closed", null));

        await(store.changes().prune(T0.plusSeconds(3)));
        var answer = get(Map.of("after", "4"));

        assertThat(answer).containsEntry("floor", 5).containsEntry("head", 5);
        assertThat(ids(answer)).containsExactly(5L);
        assertThat(StoreFixtures.count(store, "SELECT count(*) AS n FROM change WHERE id < 5")).isZero();
    }

    /// No change recorded before the prune instant: there is no cutoff, nothing is deleted and the floor stays put.
    @Test
    void prune_ofNothingRecordedBefore_keepsEveryRow_andTheFloor() {
        insertPrStates(3);

        await(store.changes().prune(T0));
        var answer = get(Map.of("after", "0"));

        assertThat(answer).containsEntry("floor", 1).containsEntry("head", 3).doesNotContainKey("gap");
        assertThat(ids(answer)).containsExactly(1L, 2L, 3L);
    }

    /// Verifier FIX-1: an id a rolled-back write burned is not pruned. A fresh database whose first id was burned answers
    /// no gap to `after=0`, before and after its first committed row. Probe: floor as `min(id)` (or `head + 1`) → red.
    @Test
    void feed_ofABurnedId_isNoGap() {
        await(Sql.query(((PgKilnStore) store).db().executor(), "SELECT nextval('change_id_seq')"));

        var burned = get(Map.of("after", "0"));
        insert("PR_STATE", 1L, "#1", "open", "closed", null);
        var listed = get(Map.of("after", "0"));

        assertThat(burned).containsKey("changes").containsEntry("head", 1).containsEntry("floor", 1);
        assertThat(ids(listed)).containsExactly(2L);
        assertThat(listed).containsEntry("floor", 1).doesNotContainKey("gap");
    }

    /// Verifier FIX-2: a missing epoch row is replaced by a new epoch, so the next read is a reset, never a 500. Probe:
    /// the epoch insert dropped from `feed` → 500, red.
    @Test
    void feed_ofAMissingEpoch_isAResetToANewEpoch() {
        insertPrStates(2);
        var old = epoch();
        StoreFixtures.execute(store, "DELETE FROM kiln_epoch");

        var reset = get(Map.of("epoch", old, "after", "2"));

        assertThat(reset).containsKey("reset").containsEntry("head", 2);
        assertThat(epoch()).isNotEqualTo(old).isEqualTo(((Map<?, ?>) reset.get("reset")).get("epoch"));
    }

    /// `lastOkAt` is `last_success_at`, which a failing source keeps: never the time of the read, never a state word.
    /// `kiln:highwater` is always there: the restore-detection file's last write, period 60 s (lead ruling R2-F3).
    /// `periodS` is the configured period, also while a rate limit stretches GitHub's to [#STRETCHED]. Probes: `lastOkAt`
    /// sent as now, or `periodS` from the stretched periods → red.
    @Test
    void sources_carryTheLastSuccessAsStored_andThePeriod_neverAState() {
        await(store.sources().record(Source.GITHUB, new SourceState.Ok(T1)));
        await(store.sources().record(Source.GITHUB, new SourceState.GithubDown("502", T2)));
        await(store.sources().record(Source.MIRROR, new SourceState.Unreachable("no route", T2)));

        Map<String, Map<String, Object>> sources = cast(get(Map.of()).get("sources"));

        assertThat(sources).containsOnlyKeys("github", "mirror", "kiln:highwater");
        assertThat(sources.get("kiln:highwater")).containsOnlyKeys("lastOkAt", "periodS")
                                                 .containsEntry("periodS", 60)
                                                 .extractingByKey("lastOkAt")
                                                 .isNotNull();
        assertThat(sources.get("github")).containsOnlyKeys("lastOkAt", "periodS")
                                         .containsEntry("lastOkAt", T1.toString())
                                         .containsEntry("periodS", 60);
        assertThat(sources.get("mirror")).containsOnlyKeys("lastOkAt", "periodS")
                                         .containsEntry("lastOkAt", null)
                                         .containsEntry("periodS", 300);
    }

    @Test
    void feed_refusesABadEpoch_aNegativeCursor_andAPageOutside1To500() {
        assertThat(status(Map.of("epoch", "not-a-uuid"))).isEqualTo(400);
        assertThat(status(Map.of("after", "-1"))).isEqualTo(400);
        assertThat(status(Map.of("after", "x"))).isEqualTo(400);
        assertThat(status(Map.of("max", "0"))).isEqualTo(400);
        assertThat(status(Map.of("max", "x"))).isEqualTo(400);
        assertThat(status(Map.of("max", "501"))).isEqualTo(400);
        assertThat(status(Map.of("max", "1"))).isEqualTo(200);
        assertThat(status(Map.of("max", "500"))).isEqualTo(200);
    }

    @Test
    void storeFeed_refusesAPageBelow1() {
        store.changes()
             .feed(0, 0)
             .await(KilnPgFixture.WAIT)
             .onSuccess(_ -> fail("expected InvalidLimit"))
             .onFailure(cause -> assertThat(cause).isEqualTo(KilnStoreError.InvalidLimit.FACTORY.apply(0)));
    }

    /// Rebuild control (review H4). Probe: `gen_random_uuid()` in V3 replaced by a constant → both epochs are equal and
    /// this goes red.
    @Test
    void rebuiltSchema_hasANewEpoch_andTheOldEpochIsReset() {
        insertPrStates(2);
        var old = epoch();
        await(store.close());

        var raw = KilnPgFixture.db(config);
        await(Sql.execute(raw.executor(), "DROP SCHEMA public CASCADE"));
        await(Sql.execute(raw.executor(), "CREATE SCHEMA public"));
        await(raw.close());
        store = open();

        assertThat(epoch()).isNotEqualTo(old);
        assertThat(get(Map.of("epoch", old, "after", "2"))).containsEntry("reset", Map.of("epoch", epoch()))
                                                           .containsEntry("head", 0);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private KilnStore open() {
        return await(PgKilnStore.connect(config, StoreFixtures.REPO, highWater, ChangeDiff.CHANGES));
    }

    /// `count` PR_STATE rows, ids 1..count, row n recorded at `T0 + n s`, so a prune by `recorded_at` drops a prefix.
    private void insertPrStates(int count) {
        for (long n = 1; n <= count; n++) {
            insertAt(T0.plusSeconds(n), "PR_STATE", n, "#" + n, "open", "closed", null);
        }
    }

    private void insert(String kind, Long pr, String subject, String before, String after, String payload) {
        insertAt(T1, kind, pr, subject, before, after, payload);
    }

    private void insertAt(Instant recordedAt, String kind, Long pr, String subject, String before, String after, String payload) {
        await(Sql.execute(((PgKilnStore) store).db().executor(),
                          INSERT,
                          Sql.utc(T0),
                          kind,
                          pr,
                          subject,
                          before,
                          after,
                          Sql.utc(recordedAt),
                          StoreFixtures.REPO,
                          payload));
    }

    private String epoch() {
        return await(store.changes().feed(0, 1)).epoch()
                                                .value()
                                                .toString();
    }

    private Router router() {
        var routes = ApiRoutes.apiRoutes(store,
                                         MacIngest.macIngest(store.checkouts(), store.sources(), List.of(), () -> NOW, () -> {}),
                                         List.of(),
                                         List.of(),
                                         () -> none(),
                                         List::of,
                                         () -> Periods.periods(STRETCHED, Duration.ofSeconds(300), Duration.ofSeconds(60), Duration.ofSeconds(120)),
                                         Periods.periods(Duration.ofSeconds(60), Duration.ofSeconds(300), Duration.ofSeconds(60), Duration.ofSeconds(120)),
                                         () -> NOW,
                                         () -> null);

        return Router.router(routes.routes());
    }

    private ApiResponse response(Map<String, String> query) {
        return router().handle(ApiRequest.apiRequest("GET", "/api/v1/changes", query, "")).await(KilnPgFixture.WAIT).unwrap();
    }

    private int status(Map<String, String> query) {
        return response(query).status();
    }

    private Map<String, Object> get(Map<String, String> query) {
        var response = response(query);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.contentType()).isEqualTo(ApiResponse.JSON);
        return MAPPER.readString(response.body(), new TypeToken<Map<String, Object>>() {}).unwrap();
    }

    private static List<Long> ids(Map<String, Object> answer) {
        List<Map<String, Object>> changes = cast(answer.get("changes"));

        return changes.stream().map(change -> ((Number) change.get("id")).longValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
