package org.pragmatica.temper.domain.retention;

import java.time.Instant;

import org.pragmatica.temper.domain.model.JobId;


/// One deletion the retention GC performs (m1-design §4.2).
public sealed interface RetentionAction {
    /// Delete the tarball, then mark its `evidence` row `deleted_at` while the row is still the one stored at
    /// `storedAt` (a later pull that replaced it keeps it live).
    record DeleteEvidence(long evidenceId, Instant storedAt) implements RetentionAction {}

    /// Delete the job's `testcases`, `manifests` and `unit_facts` rows (inside `temper.retention = 'on'`).
    record DeleteTestcases(JobId job) implements RetentionAction {}

    /// Ask `host`'s worker to delete the job's directory (`GcJobs`).
    record DeleteCheckout(String host, JobId job) implements RetentionAction {}

    /// Delete events up to `seq` and raise the event floor to it, in one transaction.
    record DeleteEventsUpTo(long seq) implements RetentionAction {}

    /// Delete one `pg_dump` file.
    record DeletePgDump(String name) implements RetentionAction {}
}
