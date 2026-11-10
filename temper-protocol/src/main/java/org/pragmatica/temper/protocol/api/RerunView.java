package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.temper.domain.state.JobState;

import static org.pragmatica.lang.Option.option;


/// One rerun of a job, as `JobView.reruns` and `GateView.reruns` list it (m2-design §5.3, the read-side link of ruling
/// R-M2-9): the rerun job, its stored state (its verdict once terminal) and its per-class outcomes
/// (`rerun(<class>)=PASSED|FAILED|NOT-RUN(<reason>)`, empty until it finished). A live view over immutable records: the
/// parent's record never changes. A refused rerun request is not listed. An absent `outcomes` reads as none, as an absent
/// `reruns` does on the views.
public record RerunView(long job, JobState state, List<String> outcomes) {
    public RerunView {
        outcomes = option(outcomes).map(List::copyOf).or(List.of());
    }

    public static RerunView rerunView(long job, JobState state, List<String> outcomes) {
        return new RerunView(job, state, outcomes);
    }
}
