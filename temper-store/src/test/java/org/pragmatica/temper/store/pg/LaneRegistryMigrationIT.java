package org.pragmatica.temper.store.pg;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.store.api.DbConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// V20's one-time backfill of the heartbeat clock (`task_report.last_hb_at`) for tasks LEASED before the migration: the
/// latest 'leased' or 'heartbeat' event of the task's CURRENT epoch, so a lane that died around the deploy has an
/// `hbAge` and can be stale. An older epoch's events, a 'lease-extended' event and a task that is not LEASED give no
/// time. Mutation: drop the INSERT from V20 → [#leasedBeforeV20_getsItsLastHeartbeatOrClaimOfTheCurrentEpoch] fails.
@Tag("pg")
class LaneRegistryMigrationIT {
    private static final int V20 = MigrationRunner.MIGRATIONS.stream()
                                                             .filter(migration -> migration.name()
                                                                                           .equals("lane_registry"))
                                                             .findFirst()
                                                             .orElseThrow()
                                                             .version();

    private DbConfig config;
    private Db raw;

    @BeforeEach
    void setUp() {
        config = PgFixture.freshDatabase();
        raw = PgFixture.db(config);
        MigrationRunner.migrate(raw,
                                MigrationRunner.MIGRATIONS.stream()
                                                          .filter(migration -> migration.version() < V20)
                                                          .map(migration -> MigrationRunner.load(migration).unwrap())
                                                          .toList())
                       .await(PgFixture.WAIT)
                       .unwrap();
    }

    @AfterEach
    void tearDown() {
        if (raw != null) {
            raw.close().await(PgFixture.WAIT);
        }
    }

    @Test
    void leasedBeforeV20_getsItsLastHeartbeatOrClaimOfTheCurrentEpoch() {
        var heartbeated = leasedTask("hb", 2);
        var claimOnly = leasedTask("claim", 1);
        var extendedAfter = leasedTask("ext", 1);
        var noEvent = leasedTask("none", 1);
        var olderEpochOnly = leasedTask("older", 2);
        var ready = readyTask("ready");

        event(heartbeated, "leased", 1, "2026-11-01 10:00:00+00");
        event(heartbeated, "heartbeat", 1, "2026-11-01 10:20:00+00");
        event(heartbeated, "leased", 2, "2026-11-01 11:00:00+00");
        event(heartbeated, "heartbeat", 2, "2026-11-01 11:10:00+00");
        event(heartbeated, "job-linked", 2, "2026-11-01 11:50:00+00");
        event(claimOnly, "leased", 1, "2026-11-01 09:00:00+00");
        event(extendedAfter, "leased", 1, "2026-11-01 09:00:00+00");
        event(extendedAfter, "lease-extended", 1, "2026-11-01 12:00:00+00");
        event(olderEpochOnly, "heartbeat", 1, "2026-11-01 10:30:00+00");
        event(ready, "heartbeat", 0, "2026-11-01 08:00:00+00");
        migrateAll();
        assertThat(lastHb(heartbeated)).as("latest heartbeat of epoch 2, not the older epoch's, not job-linked")
                  .isEqualTo("2026-11-01 11:10:00+00");
        assertThat(lastHb(claimOnly)).as("the claim when nothing came after it").isEqualTo("2026-11-01 09:00:00+00");
        assertThat(lastHb(extendedAfter)).as("controller-start extension is not a heartbeat")
                  .isEqualTo("2026-11-01 09:00:00+00");
        assertThat(lastHb(noEvent)).as("no event, no row").isEqualTo("none");
        assertThat(lastHb(olderEpochOnly)).as("only an older epoch's heartbeat: that holder is gone, no row")
                  .isEqualTo("none");
        assertThat(lastHb(ready)).as("not leased, no row").isEqualTo("none");
    }

    private long leasedTask(String slug, long epoch) {
        return task(slug, "CODE", "LEASED", epoch, "'" + "a".repeat(64) + "', 'w', now(), now()");
    }

    private long readyTask(String slug) {
        return task(slug, "CODE", "READY", 0, "NULL, NULL, NULL, NULL");
    }

    private long task(String slug, String stage, String status, long epoch, String lease) {
        execute("INSERT INTO tasks (slug, title, brief, repo, base_ref, branch, stage, status, lease_epoch, lease_hash, "
               + "lease_holder, lease_until, step_deadline, ready_since, created_by) VALUES ('" + slug
               + "', 't', '{}', 'a/b', "
               + "'main', 'agent/" + slug
               + "', '" + stage
               + "', '" + status
               + "', " + epoch
               + ", " + lease
               + ", now(), 'cto')").unwrap();

        return Long.parseLong(text("SELECT id::text AS v FROM tasks WHERE slug = '" + slug + "'"));
    }

    private void event(long task, String kind, long epoch, String at) {
        execute("INSERT INTO task_events (at, task_id, kind, stage, status, visit, epoch, actor, payload) VALUES ('" + at
               + "', " + task
               + ", '" + kind
               + "', 'CODE', 'LEASED', 1, " + epoch
               + ", 'w', '{}')").unwrap();
    }

    private String lastHb(long task) {
        return text("SELECT coalesce((SELECT to_char(last_hb_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00' "
                   + "FROM task_report WHERE task_id = " + task
                   + "), 'none') AS v");
    }

    private void migrateAll() {
        Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap().close();
    }

    private Result<?> execute(String sql) {
        return raw.inTransaction(tx -> Sql.execute(tx, sql))
                  .await(PgFixture.WAIT);
    }

    private String text(String sql) {
        return raw.executor()
                  .completeQuery(sql)
                  .await(PgFixture.WAIT)
                  .unwrap()
                  .index(0)
                  .getString("v");
    }
}
