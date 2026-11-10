package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// `GET /api/v1/maintenance` answer (m1-design §3.4): the latest `pg_dump` run whatever its outcome, and when the
/// latest good one ended. Each is absent before the first such run.
public record MaintenanceView(Option<MaintenanceRunDto> latestDump, Option<Instant> lastGoodDumpEndedAt) {
    public static MaintenanceView maintenanceView(Option<MaintenanceRunDto> latestDump,
                                                  Option<Instant> lastGoodDumpEndedAt) {
        return new MaintenanceView(latestDump, lastGoodDumpEndedAt);
    }
}
