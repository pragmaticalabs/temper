package org.pragmatica.temper.store.api;

import java.nio.file.Path;
import java.time.Instant;

import org.pragmatica.lang.Option;


/// A `maintenance_runs` row (m1-design §3.4): one run of `kind`, whether it succeeded, a free-text detail, and the
/// file it produced with its size.
public record MaintenanceRun(MaintenanceKind kind,
                             Instant startedAt,
                             Instant endedAt,
                             boolean ok,
                             Option<String> detail,
                             Option<Path> path,
                             Option<Long> bytes) {
    public static MaintenanceRun maintenanceRun(MaintenanceKind kind,
                                                Instant startedAt,
                                                Instant endedAt,
                                                boolean ok,
                                                Option<String> detail,
                                                Option<Path> path,
                                                Option<Long> bytes) {
        return new MaintenanceRun(kind, startedAt, endedAt, ok, detail, path, bytes);
    }
}
