package org.pragmatica.temper.store.pg;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.UnitRow;


/// Job reads shared by the repositories; each runs on the executor it is given (the pool or a transaction).
sealed interface Jobs {
    static Promise<Option<JobRow>> load(QueryExecutor executor, JobId id) {
        return units(executor, id).flatMap(units -> job(executor, id, units));
    }

    static Promise<List<UnitRow>> units(QueryExecutor executor, JobId id) {
        return Sql.query(executor,
                         "SELECT " + Rows.UNIT_COLUMNS + " FROM units u WHERE u.job_id = $1 ORDER BY u.id",
                         id.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, Rows::unitRow));
    }

    /// The job's testcases, ordered by unit, class and name; `outcome` (wire form) filters when present.
    static Promise<List<TestcaseRow>> testcases(QueryExecutor executor, JobId id, Option<String> outcome) {
        return Sql.query(executor,
                         Rows.TESTCASE_SELECT
                        + "WHERE t.job_id = $1 AND ($2::text IS NULL OR t.outcome = $2::text) "
                        + "ORDER BY u.name, t.class, t.name",
                         id.value(),
                         Sql.nullable(outcome))
                  .flatMap(resultSet -> Sql.parseAll(resultSet, Rows::testcaseRow));
    }

    /// The grant judges of the job's units (`units.granted_judge`, V6) as one text, comma-separated in grant order, NULL
    /// when no unit holds a grant: a scalar subquery over the job id expression `job` (`$1`, or `j.id` correlated in
    /// [GateRows]). Ruling R-M2-11: a job is judged across a deploy when any of them is not its finishing judge. A
    /// requeue clears a unit's grant judge with the rest of its attempt.
    static String grantJudgesOf(String job) {
        return "(SELECT string_agg(g.granted_judge, ',' ORDER BY g.granted_at, g.id) FROM units g WHERE g.job_id = " + job
             + " AND g.granted_judge IS NOT NULL)";
    }

    /// The grant judges of the job, in grant order ([#grantJudgesOf]).
    static Promise<List<Judge>> grantJudges(QueryExecutor executor, JobId id) {
        return Sql.query(executor,
                         "SELECT " + grantJudgesOf("$1") + " AS granted_judges",
                         id.value())
                  .flatMap(resultSet -> grantJudgesIn(resultSet).async());
    }

    private static Result<List<Judge>> grantJudgesIn(PgResultSet resultSet) {
        return grantJudges(Sql.first(resultSet).flatMap(row -> Rows.text(row, "granted_judges"))).mapError(Rows.corrupt("units"));
    }

    /// `a,b` as [#grantJudgesOf] joins them; each must parse. The caller names the table a failure is corrupt in.
    static Result<List<Judge>> grantJudges(Option<String> joined) {
        return Result.allOf(joined.stream().flatMap(Jobs::judgesIn).toList());
    }

    private static Stream<Result<Judge>> judgesIn(String joined) {
        return Arrays.stream(joined.split(",")).map(Judge::judge);
    }

    /// The jobs of `jobRows` with their units, in the order of `jobRows`.
    static Promise<List<JobRow>> withUnits(QueryExecutor executor, List<PgRow> jobRows) {
        return Sql.query(executor,
                         "SELECT " + Rows.UNIT_COLUMNS
                        + " FROM units u WHERE u.job_id = ANY($1::bigint[]) ORDER BY u.job_id, u.id",
                         (Object) jobRows.stream().map(row -> row.getLong("id")).toArray(Long[]::new))
                  .flatMap(resultSet -> unitsByJob(resultSet).async())
                  .flatMap(byJob -> assemble(jobRows, byJob).async());
    }

    private static Promise<Option<JobRow>> job(QueryExecutor executor, JobId id, List<UnitRow> units) {
        return Sql.query(executor,
                         "SELECT " + Rows.JOB_COLUMNS + Rows.JOB_FROM + " WHERE j.id = $1",
                         id.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet,
                                                       row -> Rows.jobRow(row, units)));
    }

    private static Result<Map<Long, List<UnitRow>>> unitsByJob(PgResultSet resultSet) {
        return Result.allOf(Sql.rows(resultSet).stream().map(Rows::ownedUnit).toList()).map(entries -> entries.stream()
                                                                                                              .collect(Collectors.groupingBy(Map.Entry::getKey,
                                                                                                                                             Collectors.mapping(Map.Entry::getValue,
                                                                                                                                                                Collectors.toList()))));
    }

    private static Result<List<JobRow>> assemble(List<PgRow> jobRows, Map<Long, List<UnitRow>> byJob) {
        return Result.allOf(jobRows.stream()
                                   .map(row -> Rows.jobRow(row,
                                                           byJob.getOrDefault(row.getLong("id"),
                                                                              List.of())))
                                   .toList());
    }

    record unused() implements Jobs {}
}
