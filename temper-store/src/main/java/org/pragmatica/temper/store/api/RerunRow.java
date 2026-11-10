package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;


/// One rerun job of a [JobRow] (`jobs.rerun_of`, m2-design §5.3): its id, stored state and, once it finished, its
/// record's annotations (`results.annotations`, empty before). A refused rerun request is not one.
public record RerunRow(JobId job, JobState state, List<String> annotations) {
    public RerunRow {
        annotations = List.copyOf(annotations);
    }

    public static RerunRow rerunRow(JobId job, JobState state, List<String> annotations) {
        return new RerunRow(job, state, annotations);
    }
}
