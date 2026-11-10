package org.pragmatica.temper.store.pg;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.Evaluation;
import org.pragmatica.temper.domain.verdict.UnitOutcome;
import org.pragmatica.temper.domain.verdict.Verdict;
import org.pragmatica.temper.domain.verdict.VerdictInput;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.UnitRow;

import static org.pragmatica.lang.Option.none;


/// Finishing a job inside a transaction: the verdict input rebuilt from the stored rows, then the terminal state,
/// the write-once result row and the `finished` / `cancelled` event. The result names the finishing controller's
/// judge (`results.judge` and the record's `judge`, m2-design §1.7), written by its one INSERT: `results` is
/// write-once, so the judge is never added afterwards. The same holds for the KNOWN marks (m2-design §4.3): the active
/// `known` rows are read in this transaction, after the job is locked, and the record is written once with them.
sealed interface Finishing {
    /// The job's rows, its stored manifest and evidence, and its verdict input. `cancelled` = a cancel was requested
    /// (by request or supersede); `superseded` comes from the job; `manifest` is `some` only when every unit with a
    /// manifest spec delivered a computed manifest (m1-design §2.5), so coverage is judged only on a complete
    /// manifest. A unit with a spec whose manifest is missing or unsupported leaves it `none`: an unsupported one is
    /// already `ERROR(manifest: …)` through its error reason ([PgUnitStore]).
    record Snapshot(JobRow job, Option<JobManifest> manifest, EvidenceRows.Summary evidence, VerdictInput input) {
        boolean allUnitsTerminal() {
            return job.units()
                      .stream()
                      .allMatch(unit -> unit.state()
                                            .isTerminal());
        }
    }

    /// The job's new terminal state and the event to append last.
    record Finished(JobState state, PendingEvent event) {}

    static Promise<Snapshot> snapshot(QueryExecutor tx, JobId id) {
        return Jobs.load(tx, id)
                   .flatMap(job -> job.async(StoreError.JobNotFound.FACTORY.apply(id)))
                   .flatMap(job -> withTestcases(tx, job));
    }

    /// The grant judges are read in this transaction, so the record's `judge-changed` (ruling R-M2-11) is decided on
    /// the same rows the gate read later compares. The active `known` rows are read in it too: a record carries KNOWN
    /// for exactly the entries active at the known read, one statement inside the completing transaction, after the
    /// job lock (READ COMMITTED, ruling R-M2-14; m2-design §4.3). The verdict is the evaluation's, which never saw the
    /// registry.
    static Promise<Finished> finish(QueryExecutor tx,
                                    JobLock lock,
                                    Snapshot snapshot,
                                    Evaluation evaluation,
                                    Judge judge) {
        return marks(tx, snapshot, evaluation).flatMap(marks -> written(tx, lock, snapshot, evaluation, judge, marks));
    }

    /// Strictly one statement after another, as everything on a transaction's connection. A rerun job's annotations
    /// (m2-design §5.3) come from the snapshot alone: its recipe and its units' outcomes.
    private static Promise<RecordMarks> marks(QueryExecutor tx, Snapshot snapshot, Evaluation evaluation) {
        return Jobs.grantJudges(tx,
                                snapshot.job().id())
                   .mapWith(_ -> PgKnownStore.active(tx),
                            (grantJudges, known) -> RecordMarks.recordMarks(grantJudges, known, evaluation, snapshot));
    }

    private static Promise<Finished> written(QueryExecutor tx,
                                             JobLock lock,
                                             Snapshot snapshot,
                                             Evaluation evaluation,
                                             Judge judge,
                                             RecordMarks marks) {
        var job = snapshot.job();
        var record = ResultRecords.record(job, evaluation, snapshot.manifest(), snapshot.evidence(), judge, marks);
        var state = JobState.fromVerdict(evaluation.verdict());

        return Result.all(Json.write(record.counts()),
                          Json.write(record))
                     .map(ResultJson::new)
                     .async()
                     .flatMap(json -> writeResult(tx,
                                                  job.id(),
                                                  state,
                                                  record,
                                                  json,
                                                  judge))
                     .map(_ -> new Finished(state,
                                            PendingEvent.pendingEvent(job.id(),
                                                                      eventKind(evaluation.verdict()),
                                                                      lock.verdictPayload(evaluation.verdict(),
                                                                                          evaluation.counts(),
                                                                                          marks.knownAnnotations()))));
    }

    /// The outcome of a stored unit: `reason` is the void reason of a VOID unit and the error reason of an ERROR
    /// unit (the unit's own verdict detail, see `PgUnitStore`).
    static UnitOutcome outcome(UnitRow unit, List<TestcaseRow> rows) {
        return UnitOutcome.unitOutcome(unit.name(),
                                       unit.requestsTests(),
                                       unit.rerun(),
                                       unit.rc(),
                                       unit.killedBy(),
                                       reasonWhen(unit, UnitState.VOID),
                                       reasonWhen(unit, UnitState.ERROR),
                                       rows.stream().filter(row -> row.unit()
                                                                      .equals(unit.name())).toList(),
                                       unit.testsAttr().or(0L));
    }

    record ResultJson(String counts, String record) {}

    /// Strictly one statement after another: a transaction's connection takes one statement at a time.
    private static Promise<Snapshot> withTestcases(QueryExecutor tx, JobRow job) {
        return Jobs.testcases(tx,
                              job.id(),
                              none())
                   .flatMap(rows -> withManifest(tx, job, rows));
    }

    private static Promise<Snapshot> withManifest(QueryExecutor tx, JobRow job, List<TestcaseRow> rows) {
        return Manifests.load(tx,
                              job.id())
                        .flatMap(manifest -> withEvidence(tx, job, rows, manifest));
    }

    private static Promise<Snapshot> withEvidence(QueryExecutor tx,
                                                  JobRow job,
                                                  List<TestcaseRow> rows,
                                                  Option<JobManifest> manifest) {
        return EvidenceRows.summary(tx,
                                    job.id())
                           .map(evidence -> new Snapshot(job,
                                                         manifest,
                                                         evidence,
                                                         input(job, rows, manifest)));
    }

    private static VerdictInput input(JobRow job, List<TestcaseRow> rows, Option<JobManifest> manifest) {
        return VerdictInput.verdictInput(job.units().stream().map(unit -> outcome(unit, rows)).toList(),
                                         job.cancelRequestedAt().isPresent(),
                                         job.superseded(),
                                         Manifests.forVerdict(manifest));
    }

    private static Option<String> reasonWhen(UnitRow unit, UnitState state) {
        return unit.reason()
                   .filter(_ -> unit.state() == state);
    }

    private static Promise<Unit> writeResult(QueryExecutor tx,
                                             JobId job,
                                             JobState state,
                                             ResultRecordDto record,
                                             ResultJson json,
                                             Judge judge) {
        return Sql.execute(tx,
                           "UPDATE jobs SET state = $2, finished_at = now() WHERE id = $1",
                           job.value(),
                           state.name())
                  .flatMap(_ -> Sql.execute(tx,
                                            "INSERT INTO results (job_id, verdict, reason, annotations, counts, record, judge) "
                                           + "VALUES ($1, $2, $3, $4::text[], $5::jsonb, $6::jsonb, $7)",
                                            job.value(),
                                            record.verdict(),
                                            Sql.nullable(record.reason()),
                                            Sql.texts(record.annotations().stream()),
                                            json.counts(),
                                            json.record(),
                                            judge.value()));
    }

    private static EventRow.Kind eventKind(Verdict verdict) {
        return verdict instanceof Verdict.Cancelled
               ? EventRow.Kind.CANCELLED
               : EventRow.Kind.FINISHED;
    }

    record unused() implements Finishing {}
}
