package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Contract;


/// The Postgres store: one connection pool shared by every repository, opened by the adapter's `Postgres.connect`
/// (ruling R-10: the port names no adapter, so the factory left this interface).
public interface Store extends AutoCloseable {
    JobStore jobs();
    UnitStore units();
    ResultStore results();
    EventStore events();
    RefusalStore refusals();
    KnownStore known();
    HostStore hosts();
    EvidenceStore evidence();
    RetentionStore retention();
    MaintenanceStore maintenance();
    DigestStore digest();
    TaskStore tasks();
    FleetStore fleet();
    T2Store t2();
    ReviewStore reviews();
    MacStore mac();
    BbStore bb();

    /// Closes the pool and waits for it; never throws. `void` is dictated by `AutoCloseable` (try-with-resources).
    @Contract
    @Override
    void close();
}
