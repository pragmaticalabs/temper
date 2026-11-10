package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.retention.JobRetention;


/// What the retention GC reads and deletes in the database (m1-design §4.1, §4.2, Q-M1-11). `results`, `jobs`,
/// `units` and `evidence` rows are never deleted here; nothing of a job with `keep`, or of a job that is not
/// terminal, is ever deleted, whatever the caller asks.
public interface RetentionStore {
    /// Terminal jobs without `keep` that finished before `finishedBefore` and still have something retention can
    /// delete (a live evidence row, a host checkout not recorded gone, or `testcases`/`manifests`/`unit_facts` rows,
    /// reported together as `hasTestcases`), by id after `after`, at most `limit`. The caller pages with the last id
    /// it got until a page is shorter than `limit`: a job that stays listed (a checkout the worker keeps, a file whose
    /// delete keeps failing) never hides the jobs after it. `keep` jobs are left out here as well as by the plan.
    /// `hostsWithCheckout` are the hosts a unit of the job was ever granted on (`checkouts`, kept across a requeue),
    /// minus `checkouts_gone`.
    Promise<List<JobRetention>> jobs(Instant finishedBefore, Option<JobId> after, int limit);
    /// The highest event `seq` stored with `at < before`, whatever job it belongs to (events of `keep` jobs too);
    /// none when there is none. `at` is its transaction's start, so it does not rise strictly with `seq`: deleting up
    /// to the horizon can take events up to one transaction's length younger than `before`.
    Promise<Option<Long>> eventHorizon(Instant before);
    /// Deletes the job's `testcases`, `manifests` and `unit_facts` rows and returns how many rows went
    /// (`manifest_units`, the per-unit manifest status, is not in ruling M1-11's list and stays; the job's manifest then
    /// reads `pruned`, ruling R-7). Each batch is its own transaction under `SET LOCAL temper.retention = 'on'` (the only door of the
    /// write-once trigger), at most 10 000 testcases by `ctid` per batch. A job with `keep`, a job that is not
    /// terminal, or an unknown job deletes nothing (0).
    Promise<Long> deleteJobRows(JobId job);
    /// Deletes the events with `seq <= upTo` in steps of at most 10 000, each one transaction under the event order
    /// lock: the step's delete and `floor_seq = GREATEST(floor_seq, <step top>)` (the last step: `upTo`) commit
    /// together, so a reader sees both or neither ([EventStore#pending]). The floor never passes the last allocated
    /// `seq` (`LEAST(upTo, last value of the events sequence)`): a later event always lands above it. Returns how
    /// many events went.
    Promise<Long> deleteEventsUpTo(long upTo);
    /// The worker deleted the job's directory on `host` (`GcDone.deleted`); idempotent.
    Promise<Unit> checkoutGone(HostName host, JobId job);
}
