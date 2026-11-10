package org.pragmatica.temper.controller.maintenance;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// When the dump runs (m1-design §3.4): daily at `[pgDump] at` in the host's zone, and at start when the newest good
/// dump is older than 24 h; and which file names are verified dumps.
class DumpScheduleTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalTime AT = LocalTime.of(3, 30);

    private static ZonedDateTime local(String text) {
        return ZonedDateTime.of(LocalDateTime.parse(text), ZONE);
    }

    @Test
    void untilNext_isLaterToday_beforeTheTime() {
        assertThat(DumpSchedule.untilNext(AT, local("2026-10-13T01:00:00"))).isEqualTo(Duration.ofMinutes(150));
    }

    @Test
    void untilNext_isTomorrow_atOrAfterTheTime() {
        assertThat(DumpSchedule.untilNext(AT, local("2026-10-13T03:30:00"))).isEqualTo(Duration.ofHours(24));
        assertThat(DumpSchedule.untilNext(AT, local("2026-10-13T10:00:00"))).isEqualTo(Duration.ofMinutes(17 * 60 + 30));
    }

    /// The daylight-saving change (Europe, 2026-10-25): the next 03:30 is local time, 25 h away.
    @Test
    void untilNext_followsLocalTime_acrossTheClockChange() {
        assertThat(DumpSchedule.untilNext(AT, local("2026-10-24T03:30:00"))).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void isStale_withoutAGoodDump_orOneOlderThan24h() {
        var now = Instant.parse("2026-10-13T10:00:00Z");

        assertThat(DumpSchedule.isStale(none(), now)).isTrue();
        assertThat(DumpSchedule.isStale(some(run(now.minus(Duration.ofHours(25)))), now)).isTrue();
        assertThat(DumpSchedule.isStale(some(run(now.minus(Duration.ofHours(23)))), now)).isFalse();
    }

    @Test
    void takenAt_readsOnlyVerifiedDumpNames() {
        assertThat(PgDump.takenAt("temper-20261013T033000Z.dump")).isEqualTo(some(Instant.parse("2026-10-13T03:30:00Z")));
        assertThat(PgDump.takenAt("temper-20261013T033000Z.dump.partial")).isEqualTo(none());
        assertThat(PgDump.takenAt("other.dump")).isEqualTo(none());
    }

    private static MaintenanceRun run(Instant ended) {
        return MaintenanceRun.maintenanceRun(MaintenanceKind.PG_DUMP, ended.minusSeconds(60), ended, true, none(),
                                             some(Path.of("/d")), some(1L));
    }
}
