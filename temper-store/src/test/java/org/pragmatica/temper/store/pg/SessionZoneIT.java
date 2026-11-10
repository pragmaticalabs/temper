package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.UnitRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// The store's real write and read paths under a non-UTC session (m0-design §2.5, risk R4). The test container runs
/// `timezone=UTC`, where a raw `Instant` bound as `timestamp` round-trips by accident; here the database default is
/// `Asia/Kolkata` (UTC+05:30, no DST), so every bound `Instant` that is not a UTC `OffsetDateTime` is stored five and
/// a half hours early and these assertions fail; the half-hour offset also catches whole-hour arithmetic.
@Tag("pg")
class SessionZoneIT {
    private static final String ZONE = "Asia/Kolkata";
    private StoreHarness harness;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness(nonUtc(PgFixture.freshDatabase()));
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void sessionZone_isNotUtc_forNewSessions() {
        assertThat(text("SELECT current_setting('TimeZone') AS v")).isEqualTo(ZONE);
    }

    @Test
    void unitTimestamps_roundTripExactly_inNonUtcSession() {
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                              TestJobs.exited(0),
                              List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED)));
        var unit = mainUnit(job);

        assertThat(unit.startedAt()).isEqualTo(some(TestJobs.startedRow().startedAt()));
        assertThat(unit.endedAt()).isEqualTo(some(TestJobs.exited(0).endedAt()));
        assertThat(text("SELECT started_at::text AS v FROM units WHERE job_id = " + job.value()))
            .isEqualTo("2026-10-13 15:31:00+05:30");
    }

    @Test
    void hostFactsTimestamp_roundTripsExactly_inNonUtcSession() {
        harness.store.hosts().facts(TestJobs.HOST, TestJobs.facts()).await(PgFixture.WAIT).unwrap();

        assertThat(instant("SELECT facts_at AS v FROM hosts")).isEqualTo(TestJobs.facts().at());
        assertThat(text("SELECT facts_at::text AS v FROM hosts")).isEqualTo("2026-10-13 15:30:00.123456+05:30");
    }

    @Test
    void serverDefaultTimestamps_readAsCurrentInstant_inNonUtcSession() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var submittedAt = harness.store.jobs().job(job).await(PgFixture.WAIT).unwrap().map(row -> row.submittedAt());

        assertThat(submittedAt.or(Instant.EPOCH)).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    /// Sets the database default zone before the store connects, so every pooled session of the store inherits it.
    private static DbConfig nonUtc(DbConfig config) {
        var admin = PgFixture.db(config);

        admin.executor()
             .completeScript("ALTER DATABASE " + config.database() + " SET timezone = '" + ZONE + "'")
             .await(PgFixture.WAIT)
             .unwrap();
        admin.close().await(PgFixture.WAIT);
        return config;
    }

    private UnitRow mainUnit(JobId job) {
        return harness.store.jobs().job(job).await(PgFixture.WAIT).unwrap().unwrap().units().getFirst();
    }

    private String text(String sql) {
        return harness.raw.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap().index(0).getString("v");
    }

    private Instant instant(String sql) {
        return harness.raw.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap().index(0).getInstant("v");
    }
}
