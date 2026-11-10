package org.pragmatica.temper.store.pg;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.known.KnownEntry;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.store.api.KnownListing;
import org.pragmatica.temper.store.api.KnownRow;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.StoreError;

import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// A `known` row `KnownEntry` cannot read can exist only through a hand edit or an older schema, because V8's CHECKs are
/// its rules. This class makes one in a database of its own (the reason CHECK dropped there, never in a schema another
/// test reads) and pins ruling R-M2-15: an entry that can affect a verdict is never invisible. The completing
/// transaction's read skips it with a WARN naming its id (fail-safe: it never marks a record, so it never masks a
/// FAILED); `known list` shows it as unreadable, with its id and why; and it can be expired.
@Tag("pg")
class KnownUnreadableRowIT {
    private static StoreHarness harness;
    private static ListAppender<ILoggingEvent> logged;
    private static Logger logger;
    private static KnownRow readable;
    private static long unreadable;
    private static long toExpire;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
        logged = new ListAppender<>();
        logger = (Logger) LoggerFactory.getLogger(PgKnownStore.class);
        logged.start();
        logger.addAppender(logged);
        readable = harness.store.known()
                                .add(KnownEntry.knownEntry("org.x.ReadableTest", 6, "flaky", "#6 closes", "cto").unwrap())
                                .await(PgFixture.WAIT)
                                .unwrap();
        harness.execute("ALTER TABLE known DROP CONSTRAINT known_reason_check");
        harness.execute("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('org.x.UnreadableTest', 7, ' ', 'u', 'cto')");
        unreadable = harness.count("SELECT id AS n FROM known WHERE test_id = 'org.x.UnreadableTest'");
        harness.execute("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('org.x.ExpireMeTest', 8, '', 'u', 'cto')");
        toExpire = harness.count("SELECT id AS n FROM known WHERE test_id = 'org.x.ExpireMeTest'");
        harness.execute("ALTER TABLE known ALTER COLUMN issue DROP NOT NULL");
        harness.execute("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('org.x.NullIssueTest', NULL, 'r', 'u', 'cto')");
    }

    @AfterAll
    static void tearDown() {
        if (logger != null) {
            logger.detachAppender(logged);
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void list_showsTheUnreadableRow_withItsIdStoredValuesAndWhy() {
        var listed = harness.store.known().list().await(PgFixture.WAIT).unwrap();

        assertThat(listed).startsWith(KnownListing.readable(readable));
        assertThat(listed).filteredOn(row -> row.id() == unreadable)
                          .singleElement()
                          .isInstanceOfSatisfying(KnownListing.Unreadable.class, row -> {
                              assertThat(row.testId()).isEqualTo("org.x.UnreadableTest");
                              assertThat(row.issue()).isEqualTo(7);
                              assertThat(row.reason()).isEqualTo(" ");
                              assertThat(row.problem()).isEqualTo("Invalid reason: ' '");
                              assertThat(row.expiredAt().isEmpty()).isTrue();
                          });
    }

    /// A NULL issue (NOT NULL dropped here) neither fails the list nor the finishing read: it is UNREADABLE, issue 0.
    @Test
    void list_showsANullIssueRow_asUnreadable() {
        var listed = harness.store.known().list().await(PgFixture.WAIT).unwrap();

        assertThat(listed).filteredOn(KnownListing.Unreadable.class::isInstance)
                          .map(KnownListing.Unreadable.class::cast)
                          .filteredOn(row -> row.testId().equals("org.x.NullIssueTest"))
                          .singleElement()
                          .satisfies(row -> assertThat(row.issue()).isZero())
                          .satisfies(row -> assertThat(row.problem()).contains("issue"));
    }

    /// An add for a test whose active row is unreadable is a 409 (`known_active` holds): the operator sees the row in
    /// the list as UNREADABLE and expires it first; afterwards the add succeeds.
    @Test
    void add_overAnUnreadableActiveRow_isKnownExists_untilItIsExpired() {
        harness.execute("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('org.x.ReplaceMeTest', 9, ' ', 'u', 'cto')");
        var id = harness.count("SELECT id AS n FROM known WHERE test_id = 'org.x.ReplaceMeTest'");
        var entry = KnownEntry.knownEntry("org.x.ReplaceMeTest", 9, "flaky", "#9 closes", "cto").unwrap();

        harness.store.known()
                     .add(entry)
                     .await(PgFixture.WAIT)
                     .onSuccess(row -> fail("expected KnownExists, got " + row))
                     .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.KnownExists.FACTORY.apply("org.x.ReplaceMeTest")));
        harness.store.known().expire(id).await(PgFixture.WAIT).unwrap();
        assertThat(harness.store.known().add(entry).await(PgFixture.WAIT).isSuccess()).isTrue();
    }

    /// Expiring an unreadable row succeeds and answers it, expired; nothing is deleted.
    @Test
    void expire_ofAnUnreadableRow_succeeds() {
        var expired = harness.store.known().expire(toExpire).await(PgFixture.WAIT).unwrap();

        assertThat(expired).isInstanceOfSatisfying(KnownListing.Unreadable.class, row -> {
            assertThat(row.id()).isEqualTo(toExpire);
            assertThat(row.expiredAt().isPresent()).isTrue();
            assertThat(row.problem()).contains("reason");
        });
        assertThat(harness.count("SELECT count(*) AS n FROM known WHERE id = " + toExpire + " AND expired_at IS NOT NULL")).isEqualTo(1);
    }

    /// The row's own failure alone: not marked, and the job stays FAILED; the WARN names the row.
    @Test
    void finish_withOnlyTheUnreadableRowsFailure_staysFailed_unmarked_andWarns() {
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                              TestJobs.exited(1),
                              List.of(TestJobs.row("org.x.UnreadableTest", "two", Outcome.FAILED)));
        var record = harness.store.results().result(job).await(PgFixture.WAIT).unwrap().unwrap();

        assertThat(record.verdict()).isEqualTo("FAILED");
        assertThat(record.failed()).containsExactly(ResultRecord.Failed.failed("org.x.UnreadableTest", "two", none()));
        assertThat(record.annotations()).noneMatch(annotation -> annotation.startsWith("KNOWN("));
        assertThat(logged.list).as("the skipped row is logged with its id, at WARN")
                               .anySatisfy(event -> {
                                   assertThat(event.getLevel()).isEqualTo(Level.WARN);
                                   assertThat(event.getFormattedMessage()).isEqualTo("known row " + unreadable
                                                                                     + " unreadable, not applied: Invalid reason: ' '");
                               });
    }

    @Test
    void finish_skipsTheUnreadableRow_soAHiddenEntryNeverMarks() {
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                              TestJobs.exited(1),
                              List.of(TestJobs.row("org.x.ReadableTest", "one", Outcome.FAILED),
                                      TestJobs.row("org.x.UnreadableTest", "one", Outcome.FAILED)));
        var record = harness.store.results().result(job).await(PgFixture.WAIT).unwrap().unwrap();

        assertThat(record.verdict()).isEqualTo("FAILED");
        assertThat(record.failed()).containsExactlyInAnyOrder(ResultRecord.Failed.failed("org.x.ReadableTest", "one", some("#6")),
                                                              ResultRecord.Failed.failed("org.x.UnreadableTest", "one", none()));
        assertThat(record.annotations()).contains("KNOWN(#6)").doesNotContain("KNOWN(#7)");
    }
}
