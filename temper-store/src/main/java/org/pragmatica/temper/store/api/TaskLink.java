package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.temper.domain.task.Lease;


/// A job submitted under a lease (m3-design §6.4): the lease, and when the submit was made, on the controller's clock.
/// The accepted submit restarts the lease's TTL from `at`, as every accepted lease-token call does.
public record TaskLink(Lease lease, Instant at) {
    public static TaskLink taskLink(Lease lease, Instant at) {
        return new TaskLink(lease, at);
    }
}
