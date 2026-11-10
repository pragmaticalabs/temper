package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.pipeline.MergedPr;
import org.pragmatica.kiln.core.pipeline.PrMeasure;
import org.pragmatica.kiln.service.store.PipelineStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.postgres.PgRow;


/// [PipelineStore] over `pipeline_pr`, `pipeline_run` and `pipeline_attempt` (V7). The measurement is kiln's own jsonb ([Json]); a row whose measurement does
/// not decode fails the read as corrupt, so the tab shows the failure instead of a short table.
record PgPipelineStore(Db db) implements PipelineStore {
    private static final String TABLE = "pipeline_pr";

    private static final TypeToken<PrMeasure> MEASURE = new TypeToken<>() {};

    private static final String LIST = "INSERT INTO pipeline_pr (number, merged_at, node, listed_at) VALUES ($1, $2, $3, $4) "
                                     + "ON CONFLICT (number) DO NOTHING";

    private static final String MEASURE_ONE = "UPDATE pipeline_pr SET measure = $2::jsonb, complete = $3, measured_at = $4 "
                                            + "WHERE number = $1 AND NOT complete";

    private static final String RUN = "INSERT INTO pipeline_run (number, run, workflow, event, head_sha, head_branch, attempts, completed) "
                                    + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (number, run) "
                                    + "DO UPDATE SET attempts = EXCLUDED.attempts, completed = EXCLUDED.completed";

    private static final String PR_RUNS_LISTED = "UPDATE pipeline_pr SET pr_runs_listed = true WHERE number = $1";
    private static final String QUEUE_RUNS_LISTED = "UPDATE pipeline_pr SET queue_runs_listed = true WHERE number = $1";

    private static final String ATTEMPT = "INSERT INTO pipeline_attempt (number, run, attempt, jobs, final) "
                                        + "VALUES ($1, $2, $3, $4::jsonb, $5) ON CONFLICT (number, run, attempt) "
                                        + "DO UPDATE SET jobs = EXCLUDED.jobs, final = EXCLUDED.final WHERE NOT pipeline_attempt.final";

    private static final String LISTINGS = "SELECT pr_runs_listed, queue_runs_listed FROM pipeline_pr WHERE number = $1";

    private static final String RUNS = "SELECT run, workflow, event, head_sha, head_branch, attempts, completed FROM pipeline_run "
                                     + "WHERE number = $1 ORDER BY run";

    private static final String ATTEMPTS = "SELECT run, attempt, jobs::text AS jobs, final FROM pipeline_attempt WHERE number = $1 "
                                         + "ORDER BY run, attempt";

    private static final String ROWS = "SELECT number, merged_at, node, measure::text AS measure FROM pipeline_pr "
                                     + "ORDER BY merged_at, number";

    @Override
    public Promise<Unit> listed(List<MergedPr.Listed> prs, Instant at) {
        return Sql.sequence(prs,
                            listed -> Sql.execute(db.executor(),
                                                  LIST,
                                                  listed.pr().number(),
                                                  Sql.utc(listed.pr().mergedAt()),
                                                  Sql.text(listed.node()),
                                                  Sql.utc(at)))
                  .mapToUnit();
    }

    @Override
    public Promise<List<Row>> rows() {
        return Sql.query(db.executor(),
                         ROWS)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgPipelineStore::row));
    }

    @Override
    public Promise<Unit> measured(PrMeasure measure, Instant at) {
        return Json.write(measure)
                   .async()
                   .flatMap(json -> Sql.execute(db.executor(),
                                                MEASURE_ONE,
                                                measure.number(),
                                                json,
                                                measure.complete(),
                                                Sql.utc(at)));
    }

    @Override
    public Promise<Progress> progress(long number) {
        return Sql.query(db.executor(),
                         LISTINGS,
                         number)
                  .map(resultSet -> Sql.readFirst(resultSet, PgPipelineStore::listings).or(UNLISTED))
                  .flatMap(listings -> runsAndAttempts(number, listings));
    }

    private Promise<Progress> runsAndAttempts(long number, Listings listings) {
        return Sql.query(db.executor(),
                         RUNS,
                         number)
                  .map(resultSet -> Sql.readAll(resultSet, PgPipelineStore::run))
                  .flatMap(runs -> Sql.query(db.executor(),
                                             ATTEMPTS,
                                             number)
                                      .map(resultSet -> new Progress(listings.pr(),
                                                                     listings.queue(),
                                                                     runs,
                                                                     Sql.readAll(resultSet, PgPipelineStore::attempt))));
    }

    @Override
    public Promise<Unit> runsListed(long number, Listing listing, List<Run> runs) {
        return Sql.sequence(runs, listed -> run(number, listed)).flatMap(_ -> Sql.execute(db.executor(),
                                                                                          listing == Listing.PR
                                                                                          ? PR_RUNS_LISTED
                                                                                          : QUEUE_RUNS_LISTED,
                                                                                          number));
    }

    @Override
    public Promise<Unit> run(long number, Run run) {
        return Sql.execute(db.executor(),
                           RUN,
                           number,
                           run.run(),
                           run.workflow(),
                           run.event(),
                           run.headSha(),
                           run.headBranch(),
                           run.attempts(),
                           run.completed());
    }

    @Override
    public Promise<Unit> attempt(long number, Attempt attempt) {
        return Sql.execute(db.executor(),
                           ATTEMPT,
                           number,
                           attempt.run(),
                           attempt.attempt(),
                           attempt.jobs(),
                           attempt.settled());
    }

    /// A PR's two listing flags.
    private record Listings(boolean pr, boolean queue) {}

    private static final Listings UNLISTED = new Listings(false, false);

    private static Listings listings(PgRow row) {
        return new Listings(row.getBoolean("pr_runs_listed"), row.getBoolean("queue_runs_listed"));
    }

    private static Run run(PgRow row) {
        return new Run(row.getLong("run"),
                       row.getString("workflow"),
                       row.getString("event"),
                       row.getString("head_sha"),
                       row.getString("head_branch"),
                       row.getLong("attempts"),
                       row.getBoolean("completed"));
    }

    private static Attempt attempt(PgRow row) {
        return new Attempt(row.getLong("run"), row.getLong("attempt"), row.getString("jobs"), row.getBoolean("final"));
    }

    private static Result<Row> row(PgRow row) {
        return Rows.optional(Rows.text(row, "measure"),
                             json -> Json.read(json, MEASURE))
                   .map(measure -> new Row(row.getLong("number"),
                                           row.getInstant("merged_at"),
                                           row.getString("node"),
                                           measure))
                   .mapError(Rows.corrupt(TABLE));
    }
}
