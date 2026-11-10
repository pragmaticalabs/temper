package org.pragmatica.temper.store.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;


/// Write-once results and testcases (spec §5.4); rows are written by [UnitStore#complete] and
/// [JobStore#requestCancel] only.
public interface ResultStore {
    /// Absent until the job is terminal (and always for a REFUSED job).
    Promise<Option<ResultRecord>> result(JobId id);
    /// Ordered by unit, class and name; `outcome` filters when present. Empty once retention deleted them (90 d).
    Promise<List<TestcaseRow>> testcases(JobId id, Option<Outcome> outcome);
    /// The stored `results.record` jsonb as text, the bytes `result.json` holds (m1-design §3.3); absent until the
    /// job is terminal.
    Promise<Option<String>> recordJson(JobId id);
    /// Jobs with a result row whose job finished at or after `since`, by id (the `result.json` sweep, §3.3).
    Promise<List<JobId>> finishedSince(Instant since);
    /// The job's manifest; absent when no unit of the job has a manifest spec. Once retention deleted the job's
    /// `manifests` rows (90 d), `entries` is empty while `computed`, `notReached` and `unresolvedAnnotations` remain.
    Promise<Option<JobManifest>> manifest(JobId id);
    /// Both sides of `temper diff` (m2-design §3.2): each job with its record and every testcase row, in ONE
    /// `REPEATABLE READ, READ ONLY` transaction. A terminal job's rows are write-once, so the snapshot holds every row its
    /// completing transaction wrote, less what retention had committed deleting before the snapshot; a retention batch
    /// committing during the read is not seen at all. An unknown job fails with [StoreError.JobNotFound].
    Promise<StoredSides> diffSides(JobId a, JobId b);

    /// `diff <job> --base <sha>` (m2-design §3.1, §3.2; maintainer s44): in ONE `REPEATABLE READ, READ ONLY` transaction, side B
    /// (`job`; an unknown job fails with [StoreError.JobNotFound] before anything else), the Gate candidates at the SHAs
    /// `sha` names (as [JobStore#gateCandidates] reads them), `choose` applied to them (pure: the base job, none, or a
    /// failure that fails the read), and side A when it named a base. The selection and both sides therefore see the
    /// same committed state.
    Promise<BaseRead> diffBase(ShaPrefix sha, JobId job, Fn1<Result<Option<JobId>>, List<GateRow>> choose);

    /// The rerun bound's class history (maintainer ruling on the rerun bound): per class in `classNames`, the largest Σ
    /// `testcases.time_s` of that class and the classes nested under it (`Class$…`), over the last `lastJobs` terminal
    /// jobs (a result row exists) that are not reruns (`jobs.rerun_of` unset, maintainer ruling (b)) and stored rows of it. A
    /// class with no such rows (none ran, or retention deleted them at 90 d) is absent. One `REPEATABLE READ, READ ONLY`
    /// snapshot.
    Promise<Map<String, Duration>> classDurations(List<String> classNames, int lastJobs);
}
