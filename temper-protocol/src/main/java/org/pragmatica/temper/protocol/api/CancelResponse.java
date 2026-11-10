package org.pragmatica.temper.protocol.api;

import org.pragmatica.temper.domain.state.JobState;


/// `POST /jobs/{id}/cancel` answer: the job's state after the request (CANCELLING, CANCELLED, or the
/// terminal state it already had).
public record CancelResponse(JobState state) {
    public static CancelResponse cancelResponse(JobState state) {
        return new CancelResponse(state);
    }
}
