package org.pragmatica.temper.store.pg;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.MaintenanceStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `maintenance_runs` (m1-design §3.4): every run is recorded; the newest good one decides whether a dump is due.
@Tag("pg")
class MaintenanceStoreIT {
    private StoreHarness harness;
    private MaintenanceStore maintenance;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        maintenance = harness.store.maintenance();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void lastGood_noRun_isNone() {
        assertThat(maintenance.lastGood(MaintenanceKind.PG_DUMP).await(PgFixture.WAIT).unwrap()).isEqualTo(none());
    }

    @Test
    void lastGood_isTheNewestOkRun_failedRunsIgnored() {
        var older = run("2026-10-11T03:30:00Z", true);
        var newer = run("2026-10-12T03:30:00Z", true);
        var failed = run("2026-10-13T03:30:00Z", false);

        maintenance.record(newer).await(PgFixture.WAIT).unwrap();
        maintenance.record(older).await(PgFixture.WAIT).unwrap();
        maintenance.record(failed).await(PgFixture.WAIT).unwrap();

        assertThat(maintenance.lastGood(MaintenanceKind.PG_DUMP).await(PgFixture.WAIT).unwrap()).isEqualTo(some(newer));
        assertThat(harness.count("SELECT count(*) AS n FROM maintenance_runs")).isEqualTo(3);
    }

    @Test
    void latest_noRun_isNone() {
        assertThat(maintenance.latest(MaintenanceKind.PG_DUMP).await(PgFixture.WAIT).unwrap()).isEqualTo(none());
    }

    /// The alarm's `dump-failed` reads the newest run whatever its outcome: a failed run after a good one is the latest.
    @Test
    void latest_isTheNewestRun_failedOrNot() {
        var good = run("2026-10-12T03:30:00Z", true);
        var failed = run("2026-10-13T03:30:00Z", false);
        var older = run("2026-10-11T03:30:00Z", false);

        maintenance.record(failed).await(PgFixture.WAIT).unwrap();
        maintenance.record(good).await(PgFixture.WAIT).unwrap();
        maintenance.record(older).await(PgFixture.WAIT).unwrap();

        assertThat(maintenance.latest(MaintenanceKind.PG_DUMP).await(PgFixture.WAIT).unwrap()).isEqualTo(some(failed));

        var recovered = run("2026-10-14T03:30:00Z", true);
        maintenance.record(recovered).await(PgFixture.WAIT).unwrap();

        assertThat(maintenance.latest(MaintenanceKind.PG_DUMP).await(PgFixture.WAIT).unwrap()).isEqualTo(some(recovered));
    }

    private static MaintenanceRun run(String endedAt, boolean ok) {
        var end = Instant.parse(endedAt);

        return MaintenanceRun.maintenanceRun(MaintenanceKind.PG_DUMP,
                                             end.minusSeconds(60),
                                             end,
                                             ok,
                                             ok ? none() : some("pg_restore --list failed"),
                                             ok ? some(Path.of("/ev/pgdump/temper-" + end.getEpochSecond() + ".dump")) : none(),
                                             ok ? some(4096L) : none());
    }
}
