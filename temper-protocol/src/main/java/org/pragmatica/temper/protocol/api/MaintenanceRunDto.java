package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// One `maintenance_runs` row in `GET /api/v1/maintenance` (m1-design §3.4): its kind as stored (`pg_dump`), when it
/// ran, whether it succeeded, and its free-text detail (absent when the run left none).
public record MaintenanceRunDto(String kind, Instant startedAt, Instant endedAt, boolean ok, Option<String> detail) {
    public static MaintenanceRunDto maintenanceRunDto(String kind,
                                                      Instant startedAt,
                                                      Instant endedAt,
                                                      boolean ok,
                                                      Option<String> detail) {
        return new MaintenanceRunDto(kind, startedAt, endedAt, ok, detail);
    }
}
