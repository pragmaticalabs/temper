package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.Starvation;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.temper.store.api.SubmitOutcome;
import org.pragmatica.temper.store.api.UnitFinishRow;
import org.pragmatica.temper.store.api.UnitManifestRows;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// A migrated store on a fresh database plus a raw [Db] on the same database, and the common job lifecycle steps.
final class StoreHarness implements AutoCloseable {
    static final TimeSpan LEASE = TimeSpan.timeSpan(30).seconds();

    final DbConfig config;
    final Store store;
    final Db raw;

    StoreHarness() {
        this(PgFixture.freshDatabase());
    }

    /// On `config`, which the caller may have prepared (database settings) before the store connects.
    StoreHarness(DbConfig config) {
        this(config, Judge.UNJUDGED);
    }

    /// On `config`, the store writing as `judge` (m2-design §1.7), as a controller built with that judge would.
    StoreHarness(DbConfig config, Judge judge) {
        this.config = config;
        store = Postgres.connect(config, judge).await(PgFixture.WAIT).unwrap();
        raw = PgFixture.db(config);
        store.hosts().upsert(TestJobs.HOST_CONFIG).await(PgFixture.WAIT).unwrap();
    }

    JobId submit(NewJob job) {
        return store.jobs()
                    .submit(job)
                    .await(PgFixture.WAIT)
                    .unwrap()
                    .job();
    }

    SubmitOutcome submitOutcome(NewJob job) {
        return store.jobs()
                    .submit(job)
                    .await(PgFixture.WAIT)
                    .unwrap();
    }

    /// Grants the head of the queue, one unit.
    List<Granted> grantHead() {
        return grantHead(store);
    }

    /// Grants the head of the queue, one unit, through `through` (another controller's store on the same database).
    static List<Granted> grantHead(Store through) {
        return through.units()
                      .grant(TestJobs.HOST,
                             queued -> GrantDecision.grantDecision(option(queued.isEmpty()
                                                                          ? null
                                                                          : queued.getFirst()
                                                                                  .unitId()),
                                                                   Starvation.NONE),
                             LEASE,
                             1,
                             Option.none())
                      .await(PgFixture.WAIT)
                      .unwrap();
    }

    void start(JobId job) {
        start(store, job);
    }

    static void start(Store through, JobId job) {
        through.units()
               .started(TestJobs.HOST,
                        TestJobs.main(job),
                        attempt(through,
                                TestJobs.main(job)),
                        TestJobs.startedRow())
               .await(PgFixture.WAIT)
               .unwrap();
    }

    CompleteOutcome complete(JobId job, UnitFinishRow finish, List<TestcaseRow> rows) {
        return complete(store, job, finish, rows);
    }

    static CompleteOutcome complete(Store through, JobId job, UnitFinishRow finish, List<TestcaseRow> rows) {
        return through.units()
                      .complete(TestJobs.HOST,
                                TestJobs.main(job),
                                attempt(through,
                                        TestJobs.main(job)),
                                finish,
                                rows,
                                none(),
                                none(),
                                TestJobs::evaluate)
                      .await(PgFixture.WAIT)
                      .unwrap();
    }

    /// submit → grant → started → complete.
    JobId run(NewJob job, UnitFinishRow finish, List<TestcaseRow> rows) {
        var id = submit(job);

        grantHead();
        start(id);
        complete(id, finish, rows);

        return id;
    }

    /// submit → grant → started → complete with a manifest and small evidence, for a single-unit job named `unit`.
    JobId runWith(NewJob job,
                  String unit,
                  List<TestcaseRow> rows,
                  Option<UnitManifestRows> manifest,
                  boolean withEvidence) {
        var id = submit(job);
        var ref = TestJobs.ref(id, unit);

        grantHead();
        store.units().started(TestJobs.HOST, ref, attempt(ref), TestJobs.startedRow()).await(PgFixture.WAIT).unwrap();
        store.units()
             .complete(TestJobs.HOST,
                       ref,
                       attempt(ref),
                       TestJobs.exited(0),
                       rows,
                       manifest,
                       withEvidence
                       ? some(TestJobs.evidence(id, unit, "evidence.tar.gz", 1234))
                       : none(),
                       TestJobs::evaluate)
             .await(PgFixture.WAIT)
             .unwrap();

        return id;
    }

    /// The unit's current attempt as the store has it (m4-design §4.1): the attempt the lifecycle steps write in.
    int attempt(UnitRef ref) {
        return attempt(store, ref);
    }

    static int attempt(Store through, UnitRef ref) {
        return through.units()
                      .attempts(List.of(ref))
                      .await(PgFixture.WAIT)
                      .unwrap()
                      .getFirst()
                      .attempt();
    }

    long count(String sql, Object... params) {
        return raw.executor()
                  .completeQuery(sql, params)
                  .await(PgFixture.WAIT)
                  .unwrap()
                  .index(0)
                  .getLong("n");
    }

    void execute(String sql, Object... params) {
        raw.executor().completeQuery(sql, params).await(PgFixture.WAIT).unwrap();
    }

    /// The host as WP-4's liveness leaves it when it declares LOST (m4-design §2.1, V11 `hosts_lost_state`): the settle
    /// re-reads this inside its transaction.
    void lost(HostName host, Instant at) {
        execute("UPDATE hosts SET state = 'LOST', lost_at = $2, lost_reason = 'link-down' WHERE name = $1",
                host.value(),
                Sql.utc(at));
    }

    /// The host as recovery leaves it (m4-design §2.5): UP, `lost_*` cleared.
    void recovered(HostName host) {
        execute("UPDATE hosts SET state = 'UP', lost_at = NULL, lost_reason = NULL WHERE name = $1", host.value());
    }

    @Override
    public void close() {
        raw.close().await(PgFixture.WAIT);
        store.close();
    }
}
