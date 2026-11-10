package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.store.api.MacPost;
import org.pragmatica.temper.store.api.MacProcess;
import org.pragmatica.temper.store.api.MacSession;
import org.pragmatica.temper.store.api.MacStore;
import org.pragmatica.temper.store.api.NewMacPost;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// V15 and the Mac store: the tables' CHECKs, the one transaction of a post (latest post,
/// sessions, 30-day retention, the `mac` source) and its all-or-nothing rollback.
@Tag("pg")
class MacStoreIT {
    private static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");

    private StoreHarness harness;
    private MacStore mac;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        mac = harness.store.mac();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    // ---- V15 ---------------------------------------------------------------------------------------------------
    @Test
    void v15_createsTheFourTables_andTheirChecksRefuseABadRow() {
        assertThat(text("SELECT string_agg(table_name, ',' ORDER BY table_name) AS v FROM information_schema.tables "
                       + "WHERE table_schema = 'public' AND table_name IN ('mac_post', 'mac_session', 'bb_snapshot', 'bb_collect')")).isEqualTo("bb_collect,bb_snapshot,mac_post,mac_session");
        assertThat(execute("INSERT INTO mac_post (id, received_at, posted_at, processes) VALUES (2, now(), now(), '[]')").isFailure()).as("one row only")
                  .isTrue();
        assertThat(execute("INSERT INTO mac_post (id, received_at, posted_at, processes) VALUES (1, now(), now(), '{}')").isFailure()).as("processes is an array")
                  .isTrue();
        assertThat(execute("INSERT INTO mac_session (pid, start_s, label, first_seen, last_seen) VALUES (0, 1700000000, 'a', now(), now())").isFailure()).as("pid > 0")
                  .isTrue();
        assertThat(execute("INSERT INTO mac_session (pid, start_s, label, first_seen, last_seen) VALUES (1, 999999999, 'a', now(), now())").isFailure()).as("start >= 1e9")
                  .isTrue();
        assertThat(execute("INSERT INTO mac_session (pid, start_s, label, first_seen, last_seen) VALUES (1, 1700000000, 'a b', now(), now())").isFailure()).as("label regex")
                  .isTrue();
        assertThat(execute("INSERT INTO bb_snapshot (host, taken_at, tickets) VALUES ('-bad', now(), '[]')").isFailure()).as("host regex")
                  .isTrue();
        assertThat(execute("INSERT INTO bb_snapshot (host, taken_at, tickets) VALUES ('bigboy', now(), '{}')").isFailure()).as("tickets array")
                  .isTrue();
        assertThat(execute("INSERT INTO bb_collect (host, last_error) VALUES ('bigboy', '" + "x".repeat(513) + "')").isFailure()).as("error <= 512")
                  .isTrue();
        assertThat(execute("INSERT INTO bb_collect (host, last_error) VALUES ('bigboy', '" + "x".repeat(512) + "')").isSuccess()).isTrue();
    }

    // ---- post --------------------------------------------------------------------------------------------------
    @Test
    void post_writesLatest_sessions_andTheMacSource_atTheReceiptTime() {
        var receipt = mac.post(post(T0,
                                    T0.minusSeconds(30),
                                    process(10, 1793667998L, "CTO"),
                                    process(11, 1793667999L, "oss")))
                         .await(PgFixture.WAIT)
                         .unwrap();

        assertThat(receipt).isEqualTo(T0);
        assertThat(mac.latest().await(PgFixture.WAIT).unwrap()).isEqualTo(some(MacPost.macPost(T0,
                                                                                               T0.minusSeconds(30),
                                                                                               List.of(process(10,
                                                                                                               1793667998L,
                                                                                                               "CTO"),
                                                                                                       process(11,
                                                                                                               1793667999L,
                                                                                                               "oss")))));
        assertThat(mac.sessions().await(PgFixture.WAIT).unwrap()).containsExactly(MacSession.macSession(10,
                                                                                                        1793667998L,
                                                                                                        "CTO",
                                                                                                        T0,
                                                                                                        T0),
                                                                                  MacSession.macSession(11,
                                                                                                        1793667999L,
                                                                                                        "oss",
                                                                                                        T0,
                                                                                                        T0));
        var source = harness.store.fleet().source("mac").await(PgFixture.WAIT).unwrap().unwrap();

        assertThat(source.lastOkAt()).isEqualTo(some(T0));
        assertThat(source.periodS()).isEqualTo(60);
    }

    @Test
    void latest_beforeAnyPost_isNone() {
        assertThat(mac.latest().await(PgFixture.WAIT).unwrap()).isEqualTo(none());
        assertThat(mac.sessions().await(PgFixture.WAIT).unwrap()).isEmpty();
    }

    @Test
    void secondPost_replacesLatest_refreshesLastSeenAndLabel_keepsFirstSeen() {
        mac.post(post(T0, T0, process(10, 1793667998L, "pid:10"))).await(PgFixture.WAIT).unwrap();
        mac.post(post(T0.plusSeconds(60),
                      T0.plusSeconds(59),
                      process(10, 1793667998L, "CTO"),
                      process(10, 1793668100L, "other")))
           .await(PgFixture.WAIT)
           .unwrap();
        assertThat(mac.latest().await(PgFixture.WAIT).unwrap().map(MacPost::receivedAt)).isEqualTo(some(T0.plusSeconds(60)));
        assertThat(mac.sessions().await(PgFixture.WAIT).unwrap()).containsExactly(MacSession.macSession(10,
                                                                                                        1793667998L,
                                                                                                        "CTO",
                                                                                                        T0,
                                                                                                        T0.plusSeconds(60)),
                                                                                  MacSession.macSession(10,
                                                                                                        1793668100L,
                                                                                                        "other",
                                                                                                        T0.plusSeconds(60),
                                                                                                        T0.plusSeconds(60)));
        assertThat(text("SELECT count(*)::text AS v FROM mac_post")).isEqualTo("1");
    }

    /// A late or concurrent post (an older receipt time) never moves `last_seen` or the latest post's `received_at`
    /// backwards. Mutation: drop `GREATEST` → red.
    @Test
    void latePost_neverMovesLastSeenOrReceivedAtBackwards_andNeverReplacesANewerRow() {
        var t2 = T0.plusSeconds(120);
        var t1 = T0.plusSeconds(60);

        mac.post(post(t2, t2, process(10, 1793667998L, "CTO"))).await(PgFixture.WAIT).unwrap();
        mac.post(post(t1,
                      t1,
                      process(10, 1793667998L, "old"),
                      process(11, 1793667999L, "stale")))
           .await(PgFixture.WAIT)
           .unwrap();
        assertThat(mac.latest().await(PgFixture.WAIT).unwrap()).as("the whole latest row stays the newer post: received, posted and processes together")
                  .isEqualTo(some(MacPost.macPost(t2,
                                                  t2,
                                                  List.of(process(10, 1793667998L, "CTO")))));
        assertThat(mac.sessions().await(PgFixture.WAIT).unwrap()).as("the late post neither moves last_seen back nor overwrites the newer label")
                  .contains(MacSession.macSession(10, 1793667998L, "CTO", t2, t2));
    }

    /// A session no longer listed keeps its row (DEAD(last seen <t>) reads it); one unseen for 30 days is deleted.
    @Test
    void post_keepsAnAbsentSession_andDeletesOneUnseenFor30Days() {
        mac.post(post(T0, T0, process(10, 1793667998L, "a"))).await(PgFixture.WAIT).unwrap();
        mac.post(post(T0.plusSeconds(60), T0, process(11, 1793667999L, "b"))).await(PgFixture.WAIT).unwrap();
        assertThat(mac.sessions().await(PgFixture.WAIT).unwrap()).extracting(MacSession::pid).containsExactly(10, 11);
        mac.post(post(T0.plusSeconds(30L * 86400), T0)).await(PgFixture.WAIT).unwrap();
        assertThat(mac.sessions().await(PgFixture.WAIT).unwrap()).as("pid 10 was last seen exactly 30 days before: kept")
                  .extracting(MacSession::pid)
                  .containsExactly(10, 11);
        mac.post(post(T0.plusSeconds(30L * 86400 + 30), T0)).await(PgFixture.WAIT).unwrap();
        assertThat(mac.sessions().await(PgFixture.WAIT).unwrap()).as("pid 10: 30 days and 30 s; pid 11: 30 days minus 30 s")
                  .extracting(MacSession::pid)
                  .containsExactly(11);
    }

    /// One transaction: a process the table refuses (a label the CHECK rejects) rolls back the latest post, the other
    /// sessions and the source's success.
    @Test
    void post_whenASessionRowIsRefused_rollsBackEverything() {
        mac.post(post(T0, T0, process(10, 1793667998L, "kept"))).await(PgFixture.WAIT).unwrap();
        var failed = mac.post(post(T0.plusSeconds(60),
                                   T0,
                                   process(12, 1793668000L, "fine"),
                                   process(13, 1793668001L, "no good")))
                        .await(PgFixture.WAIT);

        assertThat(failed.isFailure()).isTrue();
        assertThat(mac.latest().await(PgFixture.WAIT).unwrap().map(MacPost::receivedAt)).isEqualTo(some(T0));
        assertThat(mac.sessions().await(PgFixture.WAIT).unwrap()).extracting(MacSession::pid).containsExactly(10);
        assertThat(harness.store.fleet().source("mac").await(PgFixture.WAIT).unwrap().unwrap().lastOkAt()).isEqualTo(some(T0));
    }

    private static NewMacPost post(Instant received, Instant posted, MacProcess... processes) {
        return NewMacPost.newMacPost(received, posted, List.of(processes));
    }

    private static MacProcess process(int pid, long start, String label) {
        return MacProcess.macProcess(pid, start, label);
    }

    private Result<Unit> execute(String sql) {
        return Sql.execute(harness.raw.executor(),
                           sql)
                  .await(PgFixture.WAIT);
    }

    private String text(String sql) {
        return Sql.query(harness.raw.executor(),
                         sql)
                  .map(resultSet -> Sql.first(resultSet)
                                       .unwrap()
                                       .getString("v"))
                  .await(PgFixture.WAIT)
                  .unwrap();
    }
}
