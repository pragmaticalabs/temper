package org.pragmatica.temper.controller.cancel;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.store.api.CancelOutcome;
import org.pragmatica.temper.store.api.JobStore;

import static org.pragmatica.lang.Unit.unit;


/// `POST /jobs/{id}/cancel` (m0-design §6.6): `JobStore.requestCancel`, then `CancelUnit` for the units it left
/// CANCELLING (also on a repeated request, so a lost `CancelUnit` can be resent). The verdict after the kill is
/// CANCELLED unless VOID or ERROR applies. An unknown job fails with `StoreError.JobNotFound` (404).
@FunctionalInterface
public interface CancelJob {
    record Request(JobId job) {
        public static Request request(JobId job) {
            return new Request(job);
        }
    }

    /// The store's outcome; its `state()` is the job's state after the request.
    record Response(CancelOutcome outcome) {
        public static Response response(CancelOutcome outcome) {
            return new Response(outcome);
        }
    }

    Promise<Response> execute(Request request);

    static CancelJob cancelJob(JobStore jobs, UnitCanceller canceller, Wake wake) {
        return request -> cancel(jobs, canceller, wake, request);
    }

    private static Promise<Response> cancel(JobStore jobs, UnitCanceller canceller, Wake wake, Request request) {
        return jobs.requestCancel(request.job())
                   .withSuccess(outcome -> killed(canceller, wake, outcome))
                   .map(Response::response);
    }

    private static Unit killed(UnitCanceller canceller, Wake wake, CancelOutcome outcome) {
        return switch (outcome) {
            case CancelOutcome.Cancelling cancelling -> killAndWake(canceller, wake, cancelling);
            case CancelOutcome.Cancelled _ -> wake.signal();
            case CancelOutcome.AlreadyTerminal _ -> unit();
        };
    }

    private static Unit killAndWake(UnitCanceller canceller, Wake wake, CancelOutcome.Cancelling cancelling) {
        canceller.cancel(cancelling.toKill());

        return wake.signal();
    }
}
