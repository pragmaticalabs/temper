package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.lang.Option;


/// Bodies of the lease-token calls (m3-design §6.4, §11.1). Each names the epoch its caller holds; the controller accepts
/// it only where the task is LEASED at that epoch under the presented token (`Authorization: Lease <token>`), and
/// answers `409 lease-lost` otherwise.
public sealed interface LeaseCall {
    long epoch();

    /// `POST tasks/{id}/heartbeat`.
    record Heartbeat(long epoch) implements LeaseCall {}

    /// `POST tasks/{id}/wait`: linked jobs, CI, or both.
    record Wait(long epoch, Option<List<Long>> jobs, Option<Boolean> ci) implements LeaseCall {}

    /// `POST tasks/{id}/release`.
    record Release(long epoch, String reason) implements LeaseCall {}
}
