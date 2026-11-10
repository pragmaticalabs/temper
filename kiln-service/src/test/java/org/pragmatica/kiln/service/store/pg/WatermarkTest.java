package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.change;
import static org.pragmatica.lang.Option.none;

/// [Watermark] without a database: writes that begin in the same microsecond share a stamp, the stamp leaves the
/// in-flight set only when the last of them settles, and only a write that recorded a change moves the watermark.
class WatermarkTest {
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final List<Change> ONE_CHANGE = List.of(change(NOW, 1, ChangeKind.PR_STATE, "#1", "", "OPEN"));

    @Test
    void watermark_staysBelowASharedStamp_untilEveryWriteOnItSettles() {
        var committed = Watermark.watermark(() -> NOW, none());
        var stamps = new ArrayList<Instant>();
        var first = Promise.<List<Change>> promise();
        var second = Promise.<List<Change>> promise();
        var one = committed.tracked(stamp -> held(stamps, stamp, first));
        var two = committed.tracked(stamp -> held(stamps, stamp, second));

        first.succeed(ONE_CHANGE);
        one.await();
        var whileOneInFlight = committed.watermark();
        second.succeed(ONE_CHANGE);
        two.await();

        assertThat(stamps).containsExactly(NOW, NOW);
        assertThat(whileOneInFlight).isEqualTo(NOW.minus(1, ChronoUnit.MICROS));
        assertThat(committed.watermark()).isEqualTo(NOW);
    }

    /// Idle, the watermark is the newest committed stamp, never the clock: `Instant.EPOCH` before any change, and a write
    /// that recorded no change leaves it where it was.
    @Test
    void watermark_isTheNewestCommittedStamp_neverTheClock() {
        var committed = Watermark.watermark(() -> NOW, none());

        assertThat(committed.watermark()).isEqualTo(Instant.EPOCH);

        committed.tracked(_ -> Promise.success(List.<Change> of())).await();

        assertThat(committed.watermark()).isEqualTo(Instant.EPOCH);

        committed.tracked(_ -> Promise.success(ONE_CHANGE)).await();

        assertThat(committed.watermark()).isEqualTo(NOW);
    }

    private static Promise<List<Change>> held(List<Instant> stamps, Instant stamp, Promise<List<Change>> release) {
        stamps.add(stamp);
        return release;
    }
}
