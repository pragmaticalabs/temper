package org.pragmatica.temper.store.pg;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;


/// An event a transaction appends last, after all its row work (see [EventWriter]).
record PendingEvent(Option<JobId> job, EventRow.Kind kind, EventPayload payload) {
    static PendingEvent pendingEvent(JobId job, EventRow.Kind kind, EventPayload payload) {
        return new PendingEvent(Option.some(job), kind, payload);
    }
}
