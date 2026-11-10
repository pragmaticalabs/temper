package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.dedup.DedupKey;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Priority;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.task.Lease;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A job to persist. [JobStore#submit] needs `source`, `dedupKey` and at least one unit; a job refused before
/// source resolution or planning ([JobStore#recordRefused]) may lack them. `request` is the source as submitted
/// (`jobs.source`); `ref`/`source_sha` come from `source`, or from the request while unresolved. `task` is the lease a
/// `submit --task` runs under (m3-design §6.4): the store links the job to the task at that epoch only while the lease
/// holds, in the transaction that inserts the job, and fails with `TaskError.LEASE_LOST` otherwise. `dedicatedCpu`
/// (`jobs.dedicated_cpu`, m4-design §9.2) keeps the job's units off `sharedCpu` hosts; false unless [#dedicated] says so.
public record NewJob(OwnerTag submittedBy,
                     Priority priority,
                     boolean keep,
                     boolean fresh,
                     SourceRequest request,
                     Option<PushedSource> source,
                     Recipe recipe,
                     Option<DedupKey> dedupKey,
                     List<UnitSpec> units,
                     Option<TaskLink> task,
                     boolean dedicatedCpu) {
    public NewJob {
        units = List.copyOf(units);
    }

    public static NewJob newJob(OwnerTag submittedBy,
                                Priority priority,
                                boolean keep,
                                boolean fresh,
                                SourceRequest request,
                                Option<PushedSource> source,
                                Recipe recipe,
                                Option<DedupKey> dedupKey,
                                List<UnitSpec> units) {
        return new NewJob(submittedBy, priority, keep, fresh, request, source, recipe, dedupKey, units, none(), false);
    }

    /// The same job with its CPU class: `dedicatedCpu` true is never granted to a `sharedCpu` host.
    public NewJob dedicated(boolean dedicatedCpu) {
        return new NewJob(submittedBy,
                          priority,
                          keep,
                          fresh,
                          request,
                          source,
                          recipe,
                          dedupKey,
                          units,
                          task,
                          dedicatedCpu);
    }

    /// The same job submitted under `lease`.
    public NewJob underLease(Lease lease, Instant at) {
        return new NewJob(submittedBy,
                          priority,
                          keep,
                          fresh,
                          request,
                          source,
                          recipe,
                          dedupKey,
                          units,
                          some(TaskLink.taskLink(lease, at)),
                          dedicatedCpu);
    }
}
