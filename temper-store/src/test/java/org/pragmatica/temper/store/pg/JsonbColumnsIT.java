package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.store.api.Checkpoint;
import org.pragmatica.temper.store.api.MacProcess;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.NewMacPost;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;


/// Every JSON column of V1, V2, V10, V16 and V20 (the task tables, m3-design §5.3) is really `jsonb` (m0-design §5.2). The store binds `$n::jsonb`, and Postgres assigns a
/// jsonb value to a `text` column without complaint, so a column declared `text` would pass every store test while
/// losing jsonb operators and indexing. The catalog is read directly, and a jsonb-only operator runs on stored rows.
@Tag("pg")
class JsonbColumnsIT {
    private static final List<String> JSONB_COLUMNS = List.of("bb_snapshot.tickets",
                                                              "task_checkpoint.checklist",
                                                              "task_checkpoint.read_map",
                                                              "gh_budget.team_members",
                                                              "events.payload",
                                                              "fleet_events.payload",
                                                              "hosts.facts",
                                                              "jobs.recipe",
                                                              "jobs.source",
                                                              "mac_post.processes",
                                                              "refusals.matcher",
                                                              "results.counts",
                                                              "results.record",
                                                              "subscribers.selectors",
                                                              "task_events.payload",
                                                              "task_evidence.checks",
                                                              "task_evidence.document",
                                                              "task_rulings.request",
                                                              "tasks.brief",
                                                              "unit_facts.facts",
                                                              "units.args",
                                                              "units.manifest_spec");

    private static StoreHarness harness;

    /// One full job lifecycle, a job with a manifest spec, host facts, a subscriber, and a task with accepted evidence and
    /// a ruling request, so every jsonb column holds at least one row.
    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
        harness.run(TestJobs.bySha(TestJobs.key(), false),
                    TestJobs.exited(0),
                    List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED)));
        harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", true)),
                                          false));
        harness.store.hosts().facts(TestJobs.HOST, TestJobs.facts()).await(PgFixture.WAIT).unwrap();
        harness.store.events().subscriber("jsonb-probe").await(PgFixture.WAIT).unwrap();
        var tasks = harness.store.tasks();
        var task = TaskFixture.inCode(tasks, TaskFixture.T0);
        var lease = TaskFixture.claim(tasks, task.id(), "h", TaskFixture.T0).unwrap();

        tasks.checkpoint(lease,
                         new Checkpoint.New("c".repeat(40),
                                            List.of(new Checkpoint.Item("item", true)),
                                            List.of(new Checkpoint.Entry("f.md", "1-2")),
                                            "n"),
                         TaskFixture.T0)
             .await(PgFixture.WAIT)
             .unwrap();
        tasks.evidence(lease,
                       TaskFixture.evidence(),
                       TaskFixture.next(Stage.VERIFY),
                       TaskFixture.T0)
             .await(PgFixture.WAIT)
             .unwrap();
        tasks.command(task.id(),
                      new Command.Park(TaskFixture.request(),
                                       false),
                      "cto",
                      TaskFixture.T0)
             .await(PgFixture.WAIT)
             .unwrap();
        harness.store.fleet()
                     .append(List.of(NewFleetEvent.newFleetEvent(TaskFixture.T0,
                                                                 "controller",
                                                                 "self-check-ok",
                                                                 "selfcheck",
                                                                 "{\"reason\":\"\"}")))
                     .await(PgFixture.WAIT)
                     .unwrap();
        harness.store.mac()
                     .post(NewMacPost.newMacPost(TaskFixture.T0,
                                                 TaskFixture.T0,
                                                 List.of(MacProcess.macProcess(10, 1793667998L, "CTO"))))
                     .await(PgFixture.WAIT)
                     .unwrap();
        Sql.execute(harness.raw.executor(),
                    "INSERT INTO gh_budget (identity, lim, remaining, used, reset_at, observed_at, source, team_members) "
                   + "VALUES ('reviewer', 5000, 4000, 1000, now(), now(), 'probe', '[\"a\"]')")
           .await(PgFixture.WAIT)
           .unwrap();
        Sql.execute(harness.raw.executor(),
                    "INSERT INTO bb_snapshot (host, taken_at, tickets) VALUES ('bigboy', now(), '[]')")
           .await(PgFixture.WAIT)
           .unwrap();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /// Exactly these columns are jsonb: a JSON column declared otherwise, or a new one left out of this list, fails.
    @Test
    void jsonbColumns_matchV1AndV2JsonColumns_inPublicSchema() {
        var columns = Sql.rows(query("SELECT table_name || '.' || column_name AS c FROM information_schema.columns "
                                    + "WHERE table_schema = 'public' AND udt_name = 'jsonb'"))
                         .stream()
                         .map(row -> row.getString("c"))
                         .toList();

        assertThat(columns).containsExactlyInAnyOrderElementsOf(JSONB_COLUMNS);
    }

    @ParameterizedTest
    @MethodSource("jsonbColumns")
    void jsonbColumn_answersJsonbOperators_whenDeclaredJsonb(String table, String column) {
        var declared = query("SELECT format_type(a.atttypid, a.atttypmod) AS t FROM pg_attribute a "
                            + "WHERE a.attrelid = $1::regclass AND a.attname = $2 AND NOT a.attisdropped",
                             table,
                             column).index(0)
                            .getString("t");

        assertThat(declared).as(table + "." + column).isEqualTo("jsonb");
        var stored = count("SELECT count(*) AS n FROM " + table + " WHERE " + column + " IS NOT NULL");
        var viaOperator = count("SELECT count(*) AS n FROM " + table
                               + " WHERE jsonb_typeof(" + column
                               + ") IS NOT NULL "
                               + "AND " + column
                               + " @> (" + column
                               + " #> '{}')");

        assertThat(stored).as(table + "." + column + " rows").isPositive();
        assertThat(viaOperator).as(table + "." + column + " via jsonb operators").isEqualTo(stored);
    }

    static Stream<Arguments> jsonbColumns() {
        return JSONB_COLUMNS.stream().map(column -> Arguments.of((Object[]) column.split("\\.")));
    }

    private static long count(String sql) {
        return query(sql).index(0)
                    .getLong("n");
    }

    private static PgResultSet query(String sql, Object... params) {
        return harness.raw.executor()
                          .completeQuery(sql, params)
                          .await(PgFixture.WAIT)
                          .unwrap();
    }
}
