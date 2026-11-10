package org.pragmatica.temper.cli.alarm;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.protocol.api.HostLossDto;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.MaintenanceRunDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Each condition's decision, pure.
class ConditionsTest {
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofHours(26);
    private static final long GIB = 1024L * 1024 * 1024;

    private static MaintenanceRunDto run(boolean ok) {
        return MaintenanceRunDto.maintenanceRunDto("pg_dump", NOW.minusSeconds(3600), NOW.minusSeconds(3540), ok,
                                                   ok ? none() : some("pg_restore --list failed\n  caused by: rc 1"));
    }

    private static HostView host(String name, String state) {
        return HostView.hostView(name, state, 5, 0, 0, 0, none());
    }

    @Test
    void dumpFailed_firesOnlyWhenTheLatestRunFailed() {
        assertThat(Conditions.dumpFailed(none())).isEqualTo(Condition.clear(AlarmCode.DUMP_FAILED));
        assertThat(Conditions.dumpFailed(some(run(true)))).isEqualTo(Condition.clear(AlarmCode.DUMP_FAILED));
        assertThat(Conditions.dumpFailed(some(run(false))))
            .isEqualTo(Condition.firing(AlarmCode.DUMP_FAILED,
                                        "pg_dump that ended 2026-10-13T09:01:00Z failed: pg_restore --list failed\n  caused by: rc 1"));
    }

    @Test
    void dumpStale_freshDump_isClear() {
        assertThat(Conditions.dumpStale(some(NOW.minus(Duration.ofHours(25))), NOW, STALE_AFTER))
            .isEqualTo(Condition.clear(AlarmCode.DUMP_STALE));
    }

    /// A dump that ended exactly `stale-after` ago still ended within it.
    @Test
    void dumpStale_atTheBoundary_isClear_aSecondPast_fires() {
        assertThat(Conditions.dumpStale(some(NOW.minus(STALE_AFTER)), NOW, STALE_AFTER))
            .isEqualTo(Condition.clear(AlarmCode.DUMP_STALE));
        assertThat(Conditions.dumpStale(some(NOW.minus(STALE_AFTER).minusSeconds(1)), NOW, STALE_AFTER))
            .isEqualTo(Condition.firing(AlarmCode.DUMP_STALE,
                                        "the latest good pg_dump ended 2026-10-12T07:59:59Z, more than PT26H ago"));
    }

    @Test
    void dumpStale_neverAGoodDump_fires() {
        assertThat(Conditions.dumpStale(none(), NOW, STALE_AFTER))
            .isEqualTo(Condition.firing(AlarmCode.DUMP_STALE, "no good pg_dump recorded"));
    }

    @Test
    void diskLow_firesBelowTheThresholdOnAnyPath() {
        var plenty = FreeSpace.freeSpace(Path.of("/home/builder/.temper"), 200 * GIB);
        var exactly = FreeSpace.freeSpace(Path.of("/ev"), 50 * GIB);
        var low = FreeSpace.freeSpace(Path.of("/ev"), 50 * GIB - 1);

        assertThat(Conditions.diskLow(List.of(plenty, exactly), 50 * GIB)).isEqualTo(Condition.clear(AlarmCode.DISK_LOW));
        assertThat(Conditions.diskLow(List.of(plenty, low), 50 * GIB))
            .isEqualTo(Condition.firing(AlarmCode.DISK_LOW, "/ev has 50.0 GiB free (threshold 50 GiB)"));
    }

    @Test
    void workerLost_everyHostUp_isClear() {
        assertThat(Conditions.workerLost(List.of(host("bigboy", "UP"), host("cloudbb-1", "UP"))))
            .isEqualTo(Condition.clear(AlarmCode.WORKER_LOST));
        assertThat(Conditions.workerLost(List.of())).isEqualTo(Condition.clear(AlarmCode.WORKER_LOST));
    }

    /// No grace period: `GET /hosts` has no time of the link's last change, so CONNECTING is lost as DOWN is.
    @Test
    void workerLost_aHostDownOrConnecting_fires() {
        assertThat(Conditions.workerLost(List.of(host("bigboy", "UP"), host("cloudbb-1", "DOWN"), host("cloudbb-2", "CONNECTING"))))
            .isEqualTo(Condition.firing(AlarmCode.WORKER_LOST, "link not up: cloudbb-1 DOWN, cloudbb-2 CONNECTING"));
    }

    /// m4-design §10, ruling Q-M4-10: a retired host with no active unit does not alarm (its deletion is expected); a
    /// retired host still running units does, and so does a LOST host not yet retired.
    @Test
    void workerLost_retiredHost_alarmsOnlyWhileItHasActiveUnits() {
        var drained = retired("cloudbb-3", 0);
        var draining = retired("cloudbb-4", 1);
        var lost = HostView.hostView("cloudbb-4", "DOWN", some(HostLossDto.hostLossDto(Instant.EPOCH, "link-down")), 3, 0, 0, 0,
                                     none(), true, some(Instant.MAX), false);

        assertThat(Conditions.workerLost(List.of(host("bigboy", "UP"), drained))).isEqualTo(Condition.clear(AlarmCode.WORKER_LOST));
        assertThat(Conditions.workerLost(List.of(drained, draining)))
            .isEqualTo(Condition.firing(AlarmCode.WORKER_LOST, "link not up: cloudbb-4 LOST"));
        assertThat(Conditions.workerLost(List.of(lost))).isEqualTo(Condition.firing(AlarmCode.WORKER_LOST, "link not up: cloudbb-4 LOST"));
    }

    private static HostView retired(String name, int active) {
        return HostView.hostView(name, "DOWN", some(HostLossDto.hostLossDto(Instant.EPOCH, "link-down")), 3, active, 0, active,
                                 none(), true, some(Instant.EPOCH), true);
    }
}
