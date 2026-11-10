package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Maintenance runs (m1-design §3.4).
public interface MaintenanceStore {
    /// Inserts the run and returns its id.
    Promise<Long> record(MaintenanceRun run);
    /// The run of `kind` with `ok` and the latest `ended_at`; none before the first good run.
    Promise<Option<MaintenanceRun>> lastGood(MaintenanceKind kind);
    /// The run of `kind` with the latest `ended_at`, good or not; none before the first run.
    Promise<Option<MaintenanceRun>> latest(MaintenanceKind kind);
}
