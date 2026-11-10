package org.pragmatica.temper.store.pg;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.verdict.Verdicts;


/// Cancel and supersede transitions shared by `requestCancel` and `submit` (spec §2.4). The caller holds the
/// job's [JobLock] and has locked its units.
sealed interface Cancellation {
    String MARK = "UPDATE jobs SET cancel_requested_at = coalesce(cancel_requested_at, now()), "
                + "superseded = superseded OR $2, superseded_by = coalesce(superseded_by, $3) WHERE id = $1";

    String CANCEL_QUEUED_UNITS = "UPDATE units SET state = 'CANCELLED', ended_at = now() WHERE job_id = $1 AND state = 'QUEUED'";

    /// Nothing active: queued units → CANCELLED and the job is finished now (result row, `cancelled` event), its result
    /// written as `judge`.
    static Promise<Finishing.Finished> cancelNow(QueryExecutor tx,
                                                 JobLock job,
                                                 Option<JobId> supersededBy,
                                                 Judge judge) {
        return mark(tx,
                    job.id(),
                    supersededBy).flatMap(_ -> Sql.execute(tx,
                                                           CANCEL_QUEUED_UNITS,
                                                           job.id().value()))
                   .flatMap(_ -> Finishing.snapshot(tx,
                                                    job.id()))
                   .flatMap(snapshot -> Finishing.finish(tx,
                                                         job,
                                                         snapshot,
                                                         Verdicts.evaluate(snapshot.input()),
                                                         judge));
    }

    /// Something granted or running: queued units → CANCELLED, job and active units → CANCELLING. Returns the
    /// units the worker must kill.
    static Promise<List<UnitRef>> cancelRunning(QueryExecutor tx, JobLock job, Option<JobId> supersededBy) {
        return mark(tx,
                    job.id(),
                    supersededBy).flatMap(_ -> Sql.execute(tx,
                                                           CANCEL_QUEUED_UNITS,
                                                           job.id().value()))
                   .flatMap(_ -> Sql.execute(tx,
                                             "UPDATE jobs SET state = 'CANCELLING' WHERE id = $1",
                                             job.id().value()))
                   .flatMap(_ -> activeToCancelling(tx,
                                                    job.id()));
    }

    /// A `keep` job runs on; it is only marked superseded (an annotation).
    static Promise<Unit> markSuperseded(QueryExecutor tx, JobLock job, JobId by) {
        return Sql.execute(tx,
                           "UPDATE jobs SET superseded = true, superseded_by = coalesce(superseded_by, $2) WHERE id = $1",
                           job.id().value(),
                           by.value());
    }

    private static Promise<Unit> mark(QueryExecutor tx, JobId job, Option<JobId> supersededBy) {
        return Sql.execute(tx,
                           MARK,
                           job.value(),
                           supersededBy.isPresent(),
                           Sql.nullable(supersededBy.map(JobId::value)));
    }

    private static Promise<List<UnitRef>> activeToCancelling(QueryExecutor tx, JobId job) {
        return Sql.query(tx,
                         "UPDATE units SET state = 'CANCELLING' WHERE job_id = $1 "
                        + "AND state IN " + PgUnitStore.ACTIVE_STATES
                        + " RETURNING name",
                         job.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, Rows::unitName))
                  .map(names -> names.stream()
                                     .map(name -> UnitRef.unitRef(job, name))
                                     .toList());
    }

    record unused() implements Cancellation {}
}
