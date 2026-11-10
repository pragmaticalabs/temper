package org.pragmatica.temper.store.pg;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.StoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// m4-design §2.1, §2.5, §6: the host's LOST mark. `lost` writes `state = LOST` with `lost_at`/`lost_reason` and keeps
/// the first mark on a repeat; `loss` reads it back (a LOST host stays LOST across a controller restart); only
/// `recovered` clears it, and remembers it, so a mark write that commits after recovery is refused (V12, §2.5 late
/// commits). Each test gets its own database.
@Tag("pg")
class HostLossIT {
    private static final HostName HOST = TestJobs.HOST;
    private static final Instant FIRST = Instant.parse("2026-10-26T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-26T12:05:00Z");

    private StoreHarness harness;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void loss_none_hostNeverLost() {
        assertThat(loss()).isEqualTo(none());
    }

    @Test
    void lost_marksLost_readBack() {
        harness.store.hosts().lost(HOST, HostLoss.hostLoss(FIRST, "host-key")).await(PgFixture.WAIT).unwrap();

        assertThat(loss()).isEqualTo(some(HostLoss.hostLoss(FIRST, "host-key")));
        assertThat(harness.count("SELECT count(*) AS n FROM hosts WHERE name = $1 AND state = 'LOST'", HOST.value())).isEqualTo(1);
    }

    @Test
    void lost_keepsFirstMark_repeated() {
        harness.store.hosts().lost(HOST, HostLoss.hostLoss(FIRST, "link-down")).await(PgFixture.WAIT).unwrap();
        harness.store.hosts().lost(HOST, HostLoss.hostLoss(LATER, "host-key")).await(PgFixture.WAIT).unwrap();

        assertThat(loss()).isEqualTo(some(HostLoss.hostLoss(FIRST, "link-down")));
    }

    @Test
    void recovered_clearsMark_remembersIt() {
        harness.store.hosts().lost(HOST, HostLoss.hostLoss(FIRST, "link-down")).await(PgFixture.WAIT).unwrap();
        harness.store.hosts().recovered(HOST, HostLoss.hostLoss(FIRST, "link-down")).await(PgFixture.WAIT).unwrap();

        assertThat(loss()).isEqualTo(none());
        assertThat(harness.count("SELECT count(*) AS n FROM hosts WHERE name = $1 AND state = 'UP' AND lost_at IS NULL "
                                 + "AND lost_reason IS NULL AND cleared_lost_at = $2", HOST.value(), Sql.utc(FIRST))).isEqualTo(1);
    }

    /// §2.5 late commits: a link-state write never clears a mark (only recovery does), so a late one cannot either.
    @Test
    void state_keepsLostRow() {
        harness.store.hosts().lost(HOST, HostLoss.hostLoss(FIRST, "link-down")).await(PgFixture.WAIT).unwrap();
        harness.store.hosts().state(HOST, "DOWN").await(PgFixture.WAIT).unwrap();

        assertThat(loss()).isEqualTo(some(HostLoss.hostLoss(FIRST, "link-down")));
        assertThat(harness.count("SELECT count(*) AS n FROM hosts WHERE name = $1 AND state = 'LOST'", HOST.value())).isEqualTo(1);
    }

    /// §2.5 late commits (verifier p6late): the mark write that commits after recovery is refused, the host stays UP
    /// and recovered, so the settle guard (`hosts.state = 'LOST'`) keeps refusing; an older mark is refused too.
    @Test
    void lost_afterRecovery_refusedStale_hostStaysUp() {
        var mark = HostLoss.hostLoss(LATER, "link-down");
        harness.store.hosts().lost(HOST, mark).await(PgFixture.WAIT).unwrap();
        harness.store.hosts().recovered(HOST, mark).await(PgFixture.WAIT).unwrap();

        assertStale(harness.store.hosts().lost(HOST, mark).await(PgFixture.WAIT));
        assertStale(harness.store.hosts().lost(HOST, HostLoss.hostLoss(FIRST, "host-key")).await(PgFixture.WAIT));
        assertThat(loss()).isEqualTo(none());
        assertThat(harness.count("SELECT count(*) AS n FROM hosts WHERE name = $1 AND state = 'UP'", HOST.value())).isEqualTo(1);
    }

    /// A loss after the cleared mark is a new loss: it is written.
    @Test
    void lost_afterClearedMark_newLossWritten() {
        harness.store.hosts().lost(HOST, HostLoss.hostLoss(FIRST, "link-down")).await(PgFixture.WAIT).unwrap();
        harness.store.hosts().recovered(HOST, HostLoss.hostLoss(FIRST, "link-down")).await(PgFixture.WAIT).unwrap();
        harness.store.hosts().lost(HOST, HostLoss.hostLoss(LATER, "host-key")).await(PgFixture.WAIT).unwrap();

        assertThat(loss()).isEqualTo(some(HostLoss.hostLoss(LATER, "host-key")));
    }

    /// Recovery clears only the mark it saw; another mark (or none) stays as it is.
    @Test
    void recovered_otherMark_refusedStale() {
        harness.store.hosts().lost(HOST, HostLoss.hostLoss(FIRST, "link-down")).await(PgFixture.WAIT).unwrap();

        assertStale(harness.store.hosts().recovered(HOST, HostLoss.hostLoss(LATER, "link-down")).await(PgFixture.WAIT));
        assertThat(loss()).isEqualTo(some(HostLoss.hostLoss(FIRST, "link-down")));
        harness.store.hosts().recovered(HOST, HostLoss.hostLoss(FIRST, "link-down")).await(PgFixture.WAIT).unwrap();
        assertStale(harness.store.hosts().recovered(HOST, HostLoss.hostLoss(FIRST, "link-down")).await(PgFixture.WAIT));
    }

    @Test
    void recovered_hostNotFound_unknownHost() {
        var written = harness.store.hosts()
                             .recovered(HostName.hostName("nowhere").unwrap(), HostLoss.hostLoss(FIRST, "link-down"))
                             .await(PgFixture.WAIT);

        written.onSuccess(_ -> {throw new AssertionError("an unknown host recovered");})
               .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.HostNotFound.class));
    }

    /// `LOST` has a mark; the plain state write cannot produce one, so the table's CHECK refuses it.
    @Test
    void state_refusesLost_noMark() {
        var written = harness.store.hosts().state(HOST, "LOST").await(PgFixture.WAIT);

        assertThat(written.isFailure()).isTrue();
    }

    @Test
    void lost_hostNotFound_unknownHost() {
        var written = harness.store.hosts()
                             .lost(HostName.hostName("nowhere").unwrap(), HostLoss.hostLoss(FIRST, "link-down"))
                             .await(PgFixture.WAIT);

        written.onSuccess(_ -> {throw new AssertionError("an unknown host was marked LOST");})
               .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.HostNotFound.class));
    }

    private static void assertStale(org.pragmatica.lang.Result<org.pragmatica.lang.Unit> written) {
        written.onSuccess(_ -> {throw new AssertionError("a stale mark write was applied");})
               .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.StaleLoss.class));
    }

    private Object loss() {
        return harness.store.hosts().loss(HOST).await(PgFixture.WAIT).unwrap();
    }
}
