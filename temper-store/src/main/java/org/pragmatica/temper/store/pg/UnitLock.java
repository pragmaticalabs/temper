package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.HostName;

import static org.pragmatica.lang.Option.option;


/// A `units` row locked `FOR NO KEY UPDATE`, always after its job's [JobLock]. `attempt` is the unit's current attempt
/// (m4-design §4.1): a write about one attempt checks it under this lock.
record UnitLock(long id,
                UnitRef ref,
                UnitState state,
                Option<HostName> host,
                boolean requestsTests,
                boolean rerun,
                Option<Instant> startedAt,
                int attempt) {
    static final String SELECT = "SELECT id, job_id, name, state, host, requests_tests, rerun, started_at, attempt FROM units ";

    static Promise<Option<UnitLock>> lock(QueryExecutor tx, UnitRef ref) {
        return Sql.query(tx,
                         SELECT + "WHERE job_id = $1 AND name = $2 FOR NO KEY UPDATE",
                         ref.job().value(),
                         ref.unit().value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, UnitLock::unitLock));
    }

    /// Every unit of the job, in id order.
    static Promise<List<UnitLock>> lockAll(QueryExecutor tx, JobId job) {
        return Sql.query(tx,
                         SELECT + "WHERE job_id = $1 ORDER BY id FOR NO KEY UPDATE",
                         job.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, UnitLock::unitLock));
    }

    static Result<UnitLock> unitLock(PgRow row) {
        return Result.all(Rows.jobId(row, "job_id"),
                          UnitName.unitName(row.getString("name")),
                          Rows.enumValue(UnitState.class,
                                         row.getString("state")),
                          Values.optional(Rows.text(row, "host"),
                                          HostName::hostName))
                     .map((job, name, state, host) -> new UnitLock(row.getLong("id"),
                                                                   UnitRef.unitRef(job, name),
                                                                   state,
                                                                   host,
                                                                   row.getBoolean("requests_tests"),
                                                                   row.getBoolean("rerun"),
                                                                   option(row.getInstant("started_at")),
                                                                   row.getInt("attempt")))
                     .mapError(Rows.corrupt("units"));
    }

    /// GRANTED, UNKNOWN, RUNNING or CANCELLING.
    boolean active() {
        return ! state.isTerminal() && state != UnitState.QUEUED;
    }
}
