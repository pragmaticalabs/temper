package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.known.KnownEntry;
import org.pragmatica.temper.store.api.KnownListing;
import org.pragmatica.temper.store.api.KnownRow;
import org.pragmatica.temper.store.api.KnownStore;
import org.pragmatica.temper.store.api.StoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// m2-design §4.5 `KnownStoreIT`: the refusal store's lifecycle for the registry. A second active entry for one test is
/// `KnownExists` (the API's 409), an expired one does not block; expire keeps the first expiry; rows are never deleted.
@Tag("pg")
class KnownStoreIT {
    private static StoreHarness harness;
    private static KnownStore known;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
        known = harness.store.known();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private static KnownEntry entry(String testId, int issue) {
        return KnownEntry.knownEntry(testId, issue, "flaky \"restart\"", "#" + issue + " closes", "cto").unwrap();
    }

    @Test
    void add_thenListed_active_everyFieldRoundTrips() {
        var entry = entry("org.x.RoundTripTest#run(int)[1]", 1679);
        var added = known.add(entry).await(PgFixture.WAIT).unwrap();

        assertThat(added.id()).isPositive();
        assertThat(added.entry()).isEqualTo(entry);
        assertThat(added.expiredAt().isEmpty()).isTrue();
        assertThat(known.list().await(PgFixture.WAIT).unwrap()).contains(KnownListing.readable(added));
    }

    @Test
    void add_secondActiveEntryForTheTest_failsKnownExists_evenWithAnotherIssue() {
        known.add(entry("org.x.TwiceTest", 1)).await(PgFixture.WAIT).unwrap();

        known.add(entry("org.x.TwiceTest", 2))
             .await(PgFixture.WAIT)
             .onSuccess(row -> fail("expected KnownExists, got " + row))
             .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.KnownExists.FACTORY.apply("org.x.TwiceTest")));
        assertThat(harness.count("SELECT count(*) AS n FROM known WHERE test_id = 'org.x.TwiceTest'")).isEqualTo(1);
    }

    @Test
    void add_afterTheActiveEntryExpired_isANewRow_andTheOldOneStays() {
        var first = known.add(entry("org.x.AgainTest", 1)).await(PgFixture.WAIT).unwrap();

        known.expire(first.id()).await(PgFixture.WAIT).unwrap();
        var second = known.add(entry("org.x.AgainTest", 2)).await(PgFixture.WAIT).unwrap();

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(harness.count("SELECT count(*) AS n FROM known WHERE test_id = 'org.x.AgainTest'")).isEqualTo(2);
        assertThat(harness.count("SELECT count(*) AS n FROM known WHERE test_id = 'org.x.AgainTest' AND expired_at IS NULL")).isEqualTo(1);
    }

    @Test
    void expire_keepsTheFirstExpiry() throws InterruptedException {
        var added = known.add(entry("org.x.ExpireTest", 3)).await(PgFixture.WAIT).unwrap();
        var first = known.expire(added.id()).await(PgFixture.WAIT).unwrap();

        Thread.sleep(20);
        var second = known.expire(added.id()).await(PgFixture.WAIT).unwrap();

        assertThat(first.expiredAt().isPresent()).isTrue();
        assertThat(second.expiredAt()).isEqualTo(first.expiredAt());
        assertThat(second).isEqualTo(KnownListing.readable(KnownRow.knownRow(added.id(), added.entry(), added.createdAt(),
                                                                             first.expiredAt())));
        assertThat(known.list().await(PgFixture.WAIT).unwrap()).contains(second);
    }

    /// What the domain accepts, the table accepts and `list` reads back: a case name with a line terminator (Postgres's
    /// `.` matches it, and so does `KnownEntry`'s) does not make the whole list unreadable.
    @Test
    void add_caseNameWithALineTerminator_roundTrips() {
        var added = known.add(entry("org.x.LineTest#one\ntwo", 4)).await(PgFixture.WAIT).unwrap();

        assertThat(known.list().await(PgFixture.WAIT).unwrap()).contains(KnownListing.readable(added));
    }

    /// The table's blank test is Java's (`KnownEntry`'s `present` is `Character.isWhitespace`), derived here from
    /// `isWhitespace` over every code point: each whitespace character alone, and all of them together, is refused;
    /// each separator Java does not count (the non-breaking spaces) and the format characters next to them are accepted.
    @Test
    void table_blankTest_isJavasIsWhitespace() {
        for (var nonBlank : List.of("\u00A0", "\u2007", "\u202F", "\u0085", "\u180E", "\u200B", "\uFEFF", "\u0008", "\u000E", "\u001B")) {
            var testId = "a.NonBlank" + (int) nonBlank.charAt(0);

            assertThat(insertTexts(testId, nonBlank).isSuccess()).as("U+%04X is not blank", (int) nonBlank.charAt(0)).isTrue();
            assertThat(KnownEntry.knownEntry("a.NonBlank", 1, nonBlank, nonBlank, "cto").isSuccess()).isTrue();
        }
    }

    /// Every text Java's `isWhitespace` counts as blank: each whitespace code point alone, all of them together, and none.
    private static List<String> blanks() {
        var each = IntStream.rangeClosed(0, Character.MAX_CODE_POINT)
                            .filter(Character::isWhitespace)
                            .mapToObj(Character::toString)
                            .toList();

        assertThat(each).as("Java's whitespace set").hasSize(25);
        return Stream.concat(Stream.of("", String.join("", each)), each.stream()).toList();
    }

    private static Result<?> insertTexts(String testId, String text) {
        return harness.raw.executor()
                          .completeQuery("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ($1, 1, $2, $2, 'cto')",
                                         testId,
                                         text)
                          .await(PgFixture.WAIT);
    }

    /// `list` answers in id order, oldest first, as `known list` prints it.
    @Test
    void list_isOrderedById() {
        known.add(entry("org.x.OrderOneTest", 8)).await(PgFixture.WAIT).unwrap();
        known.add(entry("org.x.OrderTwoTest", 9)).await(PgFixture.WAIT).unwrap();

        assertThat(known.list().await(PgFixture.WAIT).unwrap()).extracting(KnownListing::id).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void expire_unknownId_failsKnownNotFound() {
        known.expire(987654L)
             .await(PgFixture.WAIT)
             .onSuccess(row -> fail("expected KnownNotFound, got " + row))
             .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.KnownNotFound.FACTORY.apply(987654L)));
    }

    /// The table's CHECKs are the domain's patterns: a row the domain refuses is refused by the database too, and the
    /// case name's 512 bound is enforced (a length check: a Postgres regex cannot count to 512).
    @Test
    void table_refusesWhatTheDomainRefuses() {
        for (var testId : List.of("org.x.", "a.B#", "#one", "1a.B", "a.1B", "a..B", "a.B#" + "x".repeat(513))) {
            assertThat(harness.raw.executor()
                                  .completeQuery("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ($1, 1, 'r', 'u', 'cto')",
                                                 testId)
                                  .await(PgFixture.WAIT)
                                  .isFailure()).as(testId).isTrue();
        }
        assertThat(known.add(entry("a.Bound#" + "x".repeat(512), 1)).await(PgFixture.WAIT).isSuccess()).isTrue();
        assertThat(known.add(entry("a.Hash#run#2", 1)).await(PgFixture.WAIT).isSuccess()).isTrue();
        assertThat(harness.raw.executor()
                              .completeQuery("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('a.Nbsp', 1, $1, $1, 'cto')",
                                             "\u00A0")
                              .await(PgFixture.WAIT)
                              .isSuccess()).as("NBSP is not blank to Java, nor to the table").isTrue();
        assertThat(known.add(entry("a." + "C".repeat(600), 1)).await(PgFixture.WAIT).isSuccess()).as("a class has no bound")
                                                                                         .isTrue();
        for (var blank : blanks()) {
            assertThat(harness.raw.executor()
                                  .completeQuery("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('a.Blank', 1, $1, 'u', 'cto')",
                                                 blank)
                                  .await(PgFixture.WAIT)
                                  .isFailure()).as("reason " + blank).isTrue();
            assertThat(harness.raw.executor()
                                  .completeQuery("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('a.Blank', 1, 'r', $1, 'cto')",
                                                 blank)
                                  .await(PgFixture.WAIT)
                                  .isFailure()).as("until " + blank).isTrue();
        }
        assertThat(harness.raw.executor()
                              .completeQuery("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('org.x.', 1, 'r', 'u', 'cto')")
                              .await(PgFixture.WAIT)
                              .isFailure()).isTrue();
        assertThat(harness.raw.executor()
                              .completeQuery("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('a.B', 0, 'r', 'u', 'cto')")
                              .await(PgFixture.WAIT)
                              .isFailure()).isTrue();
        assertThat(harness.raw.executor()
                              .completeQuery("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('a.B', 1, 'r', 'u', 'c-to')")
                              .await(PgFixture.WAIT)
                              .isFailure()).isTrue();
        assertThat(harness.raw.executor()
                              .completeQuery("INSERT INTO known (test_id, issue, reason, until_text, created_by) VALUES ('a.B', 1, 'r', 'u', $1)",
                                             "x".repeat(65))
                              .await(PgFixture.WAIT)
                              .isFailure()).isTrue();
    }
}
