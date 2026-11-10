package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.ResultRecord;


/// The read behind `gate <sha>` (m2-design §1.1, §1.2): the Gate candidates at the SHAs a prefix names, newest first,
/// each with its judges and its result. The caller runs it inside ONE `REPEATABLE READ, READ ONLY` transaction
/// ([PgDigestStore#inSnapshot]): its two statements, the jobs with their results and judges, then their units, see the
/// same committed state, so a supersede or a finish that commits meanwhile is either wholly in the answer or not at all.
///
/// A candidate is a job with `recipe_kind = 'gate'` (a rerun job is never one), a pushed source (`NOT unpushed`), not
/// superseded and not REFUSED.
sealed interface GateRows {
    /// `$1` is a validated hex prefix ([ShaPrefix]), so it carries no `LIKE` wildcard. The `jobs_sha` btree (V1) serves
    /// `=` but not this `LIKE` under the database's collation, so the read scans `jobs` [P]: thousands of rows a year, a
    /// few milliseconds. A `text_pattern_ops` index is the remedy if that ever stops being true (m2-design §1.3 adds
    /// none).
    String CANDIDATES = "SELECT " + Rows.JOB_COLUMNS
                      + ", j.judge, r.judge AS result_judge, r.record::text AS record, " + Jobs.grantJudgesOf("j.id")
                      + " AS granted_judges" + Rows.JOB_FROM
                      + " WHERE j.recipe_kind = 'gate' AND NOT j.unpushed AND NOT j.superseded AND j.state <> 'REFUSED'"
                      + " AND j.source_sha LIKE $1::text || '%' ORDER BY j.id DESC";

    static Promise<List<GateRow>> read(QueryExecutor tx, ShaPrefix prefix) {
        return Sql.query(tx,
                         CANDIDATES,
                         prefix.value())
                  .map(Sql::rows)
                  .flatMap(rows -> withUnits(tx, rows));
    }

    private static Promise<List<GateRow>> withUnits(QueryExecutor tx, List<PgRow> rows) {
        return Jobs.withUnits(tx, rows).flatMap(jobs -> gateRows(rows, jobs).async());
    }

    /// [Jobs#withUnits] keeps the order of `rows`, so row `i` is job `i`.
    private static Result<List<GateRow>> gateRows(List<PgRow> rows, List<JobRow> jobs) {
        return Result.allOf(IntStream.range(0,
                                            rows.size())
                                     .mapToObj(index -> gateRow(rows.get(index),
                                                                jobs.get(index)))
                                     .toList());
    }

    private static Result<GateRow> gateRow(PgRow row, JobRow job) {
        return Result.all(Judge.judge(row.getString("judge")),
                          Jobs.grantJudges(Rows.text(row, "granted_judges")),
                          Values.optional(Rows.text(row, "result_judge"),
                                          Judge::judge),
                          Values.optional(Rows.text(row, "record"),
                                          GateRows::record))
                     .map((submit, grants, result, record) -> GateRow.gateRow(job, submit, grants, result, record))
                     .mapError(Rows.corrupt("jobs"));
    }

    private static Result<ResultRecord> record(String json) {
        return Json.read(json, ResultRecordDto.class).flatMap(WireRecords::resultRecord);
    }

    record unused() implements GateRows {}
}
