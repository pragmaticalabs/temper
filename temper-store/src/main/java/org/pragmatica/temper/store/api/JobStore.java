package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.refusal.Refusal;


/// Jobs (spec §2, m0-design §5.3). Every write is one transaction.
public interface JobStore {
    /// One transaction: `pg_advisory_xact_lock(7071, hashtext(dedup_key))`; unless `fresh`, the latest job with the
    /// same key in a reusable state (QUEUED, UNKNOWN, RUNNING, PASSED, FAILED, EMPTY) answers
    /// [SubmitOutcome.Deduplicated]; otherwise the job and its units are inserted, earlier QUEUED/UNKNOWN/RUNNING jobs
    /// of the same `(ref, recipe)` (the canonical recipe with its parameters; options, priority and keep excluded) not
    /// yet superseded are superseded under `pg_advisory_xact_lock(7072, …)` (spec §2.4) and the events `queued`
    /// (+ `superseded` / `cancelled`) are appended.
    Promise<SubmitOutcome> submit(NewJob job);
    /// State REFUSED with the refusal's code and detail; no units, no event (Q-16).
    Promise<JobId> recordRefused(NewJob job, Refusal refusal);
    /// The job with its units and, once terminal, its verdict.
    Promise<Option<JobRow>> job(JobId id);
    /// Newest first: at most `q.limit()` jobs, only those older than `q.before()` when present (keyset paging).
    Promise<List<JobRow>> list(JobQuery q);
    /// No unit GRANTED, UNKNOWN, RUNNING or CANCELLING: queued units and the job → CANCELLED, with the result row and
    /// the event `cancelled`. Otherwise: queued units → CANCELLED, job and active units → CANCELLING; the outcome names
    /// the units to kill. A terminal job is left alone. An unknown job fails with [StoreError.JobNotFound].
    Promise<CancelOutcome> requestCancel(JobId id);
    /// The Gate candidates whose SHA starts with `sha` (m2-design §1.1): `recipe_kind = 'gate'`, pushed, not superseded,
    /// not REFUSED; newest first, each with its units, judges and result. One `REPEATABLE READ, READ ONLY` transaction,
    /// so every row is from the same committed state (§1.2). Candidates at several SHAs are all returned; choosing one is
    /// the caller's.
    Promise<List<GateRow>> gateCandidates(ShaPrefix sha);
}
