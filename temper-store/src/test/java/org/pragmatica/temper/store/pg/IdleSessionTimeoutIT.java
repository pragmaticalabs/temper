package org.pragmatica.temper.store.pg;

import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.store.api.StoreError;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The start guard against a real server: a database whose sessions are closed after 60 s idle (Postgres reports it as 1min) refuses the connect
/// (the lock session would be terminated under a slow review); one with 150 s or none connects.
@Tag("pg")
class IdleSessionTimeoutIT {
    @Test
    void aShortIdleSessionTimeout_refusesTheConnect() {
        var config = PgFixture.freshDatabase();

        setOnDatabase(config.database(), "'60s'");
        var outcome = Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT);

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> {
            assertThat(cause).isInstanceOf(StoreError.IdleSessionTimeout.class);
            assertThat(cause.message()).contains("idle_session_timeout", "1min", "150");
        });
    }

    @Test
    void aSufficientOrDisabledIdleSessionTimeout_connects() {
        var long_ = PgFixture.freshDatabase();

        setOnDatabase(long_.database(), "'150s'");
        Postgres.connect(long_, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap().close();
        Postgres.connect(PgFixture.freshDatabase(), Judge.UNJUDGED).await(PgFixture.WAIT).unwrap().close();
    }

    private static void setOnDatabase(String database, String value) {
        var admin = PgFixture.db(PgFixture.config("temper"));

        try {
            admin.executor()
                 .completeScript("ALTER DATABASE " + database + " SET idle_session_timeout = " + value)
                 .await(PgFixture.WAIT)
                 .unwrap();
        } finally {
            admin.close();
        }
    }
}
