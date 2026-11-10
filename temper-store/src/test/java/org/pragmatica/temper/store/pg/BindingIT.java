package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.postgres.PgResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the postgres-async typing hazards (m0-design §2.5, risk R4): timestamps bind as UTC `OffsetDateTime`
/// (an `Instant` binds as `timestamp` and shifts by the session zone), jsonb needs `$n::jsonb`, `String[]` needs
/// `$n::text[]` and survives every array-literal metacharacter.
@Tag("pg")
class BindingIT {
    private static final Instant MOMENT = Instant.parse("2026-10-13T10:15:30.123456Z");
    private static Db db;

    @BeforeAll
    static void setUp() {
        db = PgFixture.db(PgFixture.freshDatabase());
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.close().await(PgFixture.WAIT);
        }
    }

    @Test
    void offsetDateTime_roundTripsExactly_inUtcSession() {
        var read = db.executor()
                     .completeQuery("SELECT $1::timestamptz AS t", Sql.utc(MOMENT))
                     .await(PgFixture.WAIT)
                     .unwrap()
                     .index(0)
                     .getInstant("t");

        assertThat(read).isEqualTo(MOMENT);
    }

    @Test
    void offsetDateTime_roundTripsExactly_evenWhenSessionZoneIsNotUtc() {
        var read = db.inTransaction(tx -> tx.completeQuery("SET LOCAL timezone = 'America/New_York'")
                                            .flatMap(_ -> tx.completeQuery("SELECT $1::timestamptz AS t, $1::timestamptz::text AS shown",
                                                                           Sql.utc(MOMENT))))
                     .await(PgFixture.WAIT)
                     .unwrap()
                     .index(0);

        assertThat(read.getInstant("t")).isEqualTo(MOMENT);
        assertThat(read.getString("shown")).isEqualTo("2026-10-13 06:15:30.123456-04");
    }

    /// The hazard the rule exists for: a bare `Instant` is declared `timestamp`, so a non-UTC session reads it as
    /// local time and the stored instant is wrong by the zone offset.
    @Test
    void instant_isShiftedBySessionZone_whichIsWhyItIsNeverBound() {
        var read = db.inTransaction(tx -> tx.completeQuery("SET LOCAL timezone = 'America/New_York'")
                                            .flatMap(_ -> tx.completeQuery("SELECT $1::timestamptz AS t", MOMENT)))
                     .await(PgFixture.WAIT)
                     .unwrap()
                     .index(0)
                     .getInstant("t");

        assertThat(read).isEqualTo(MOMENT.plus(4, ChronoUnit.HOURS));
    }

    @Test
    void nowDefault_readsAsCurrentInstant() {
        var read = db.executor()
                     .completeQuery("SELECT now() AS t")
                     .await(PgFixture.WAIT)
                     .unwrap()
                     .index(0)
                     .getInstant("t");

        assertThat(read).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    void jsonb_roundTripsThroughExplicitCast() {
        var json = "{\"a\":\"quote \\\" backslash \\\\ unicode é ☃\",\"n\":[1,2,{\"x\":null}]}";
        var read = db.executor()
                     .completeQuery("SELECT $1::jsonb AS j, ($1::jsonb)->>'a' AS a", json)
                     .await(PgFixture.WAIT)
                     .unwrap()
                     .index(0);

        assertThat(read.getString("a")).isEqualTo("quote \" backslash \\ unicode é ☃");
        assertThat(Json.MAPPER.readTree(read.getString("j")).unwrap())
            .isEqualTo(Json.MAPPER.readTree(json).unwrap());
    }

    @Test
    void textArray_roundTripsEveryMetacharacter() {
        var values = new String[]{"plain", "with,comma", "with \"quote\"", "back\\slash", "{braces}", "NULL", "", " spaced ",
                                  "unicode é ☃", "[1] a, b"};
        var resultSet = db.executor()
                          .completeQuery("SELECT x FROM unnest($1::text[]) WITH ORDINALITY AS u(x, i) ORDER BY i", (Object) values)
                          .await(PgFixture.WAIT)
                          .unwrap();

        assertThat(column(resultSet)).containsExactly(values);
    }

    @Test
    void textArray_readsBackFromTextArrayColumn() {
        var values = new String[]{"superseded", "KNOWN(#1679)", "a,b"};
        var read = db.executor()
                     .completeQuery("SELECT $1::text[] AS a", (Object) values)
                     .await(PgFixture.WAIT)
                     .unwrap()
                     .index(0)
                     .getArray("a", String[].class);

        assertThat(read).containsExactly(values);
    }

    private static List<String> column(PgResultSet resultSet) {
        return Sql.rows(resultSet).stream()
                  .map(row -> row.getString("x"))
                  .toList();
    }
}
