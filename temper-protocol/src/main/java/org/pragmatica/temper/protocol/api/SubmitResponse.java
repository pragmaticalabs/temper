package org.pragmatica.temper.protocol.api;

import org.pragmatica.temper.domain.state.JobState;


/// `200` answer to a submit: the new job, or the reused one with `deduplicated = true`.
public record SubmitResponse(long job, JobState state, boolean deduplicated) {
    public static SubmitResponse submitResponse(long job, JobState state, boolean deduplicated) {
        return new SubmitResponse(job, state, deduplicated);
    }
}
