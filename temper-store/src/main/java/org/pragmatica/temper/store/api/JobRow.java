package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.dedup.DedupKey;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.state.JobState;


/// A `jobs` row with its units (by id) and, once a result exists, its verdict and reason. `reruns` are its rerun jobs
/// (m2-design §5.3), oldest first, read in the same statement as the row. `dedicatedCpu` is `jobs.dedicated_cpu`
/// (m4-design §9.2), which a rerun inherits; false unless [#dedicated] says so.
public record JobRow(JobId id,
                     OwnerTag submittedBy,
                     Instant submittedAt,
                     int priority,
                     boolean keep,
                     boolean fresh,
                     boolean unpushed,
                     Option<Ref> ref,
                     Option<Sha> sha,
                     Recipe recipe,
                     Option<DedupKey> dedupKey,
                     JobState state,
                     Option<String> refusalCode,
                     Option<String> refusalDetail,
                     boolean superseded,
                     Option<JobId> supersededBy,
                     Option<Instant> cancelRequestedAt,
                     Option<Instant> finishedAt,
                     Option<String> verdict,
                     Option<String> reason,
                     List<UnitRow> units,
                     List<RerunRow> reruns,
                     boolean dedicatedCpu) {
    public JobRow {
        units = List.copyOf(units);
        reruns = List.copyOf(reruns);
    }

    public static JobRow jobRow(JobId id,
                                OwnerTag submittedBy,
                                Instant submittedAt,
                                int priority,
                                boolean keep,
                                boolean fresh,
                                boolean unpushed,
                                Option<Ref> ref,
                                Option<Sha> sha,
                                Recipe recipe,
                                Option<DedupKey> dedupKey,
                                JobState state,
                                Option<String> refusalCode,
                                Option<String> refusalDetail,
                                boolean superseded,
                                Option<JobId> supersededBy,
                                Option<Instant> cancelRequestedAt,
                                Option<Instant> finishedAt,
                                Option<String> verdict,
                                Option<String> reason,
                                List<UnitRow> units,
                                List<RerunRow> reruns) {
        return new JobRow(id,
                          submittedBy,
                          submittedAt,
                          priority,
                          keep,
                          fresh,
                          unpushed,
                          ref,
                          sha,
                          recipe,
                          dedupKey,
                          state,
                          refusalCode,
                          refusalDetail,
                          superseded,
                          supersededBy,
                          cancelRequestedAt,
                          finishedAt,
                          verdict,
                          reason,
                          units,
                          reruns,
                          false);
    }

    /// The same row with its CPU class.
    public JobRow dedicated(boolean dedicatedCpu) {
        return new JobRow(id,
                          submittedBy,
                          submittedAt,
                          priority,
                          keep,
                          fresh,
                          unpushed,
                          ref,
                          sha,
                          recipe,
                          dedupKey,
                          state,
                          refusalCode,
                          refusalDetail,
                          superseded,
                          supersededBy,
                          cancelRequestedAt,
                          finishedAt,
                          verdict,
                          reason,
                          units,
                          reruns,
                          dedicatedCpu);
    }
}
