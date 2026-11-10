package org.pragmatica.temper.domain.known;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.model.OwnerTag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// m2-design §4.2: an entry names a class or a case, waits on a positive issue, says why and until when, and who added
/// it.
class KnownEntryTest {
    @ParameterizedTest
    @ValueSource(strings = {"org.x.EmberAmnesiacRestartTest",
                            "org.x.Outer$Inner",
                            "FooTest",
                            "org.x.FooTest#one",
                            "org.x.FooTest#run(int)[1] #2",
                            "_a.$b.C1"})
    void knownEntry_acceptsAClassOrACase(String testId) {
        assertThat(ok(KnownEntry.knownEntry(testId, 1679, "flaky", "#1679 closes", "cto")).testId()).isEqualTo(testId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "org.x.", ".FooTest", "1org.FooTest", "org.x.FooTest#", "org x.FooTest", "org.x-y.FooTest",
                            "#one"})
    void knownEntry_refusesAMalformedTestId(String testId) {
        assertThat(failure(KnownEntry.knownEntry(testId, 1, "flaky", "until", "cto"))).isNotNull();
    }

    /// The bound counts code points after the first `#`, as Postgres's `length` does; a class alone has no bound.
    @Test
    void knownEntry_boundsTheCaseNameAt512CodePoints_only() {
        assertThat(field(KnownEntry.knownEntry("a.B#" + "x".repeat(513), 1, "r", "u", "cto"))).isEqualTo("testId");
        assertThat(ok(KnownEntry.knownEntry("a.B#" + "x".repeat(512), 1, "r", "u", "cto")).issue()).isEqualTo(1);
        assertThat(ok(KnownEntry.knownEntry("a.B#" + "\uD83D\uDE00".repeat(512), 1, "r", "u", "cto")).issue()).as("512 emoji")
                                                                                                  .isEqualTo(1);
        assertThat(field(KnownEntry.knownEntry("a.B#" + "\uD83D\uDE00".repeat(513), 1, "r", "u", "cto"))).isEqualTo("testId");
        assertThat(ok(KnownEntry.knownEntry("a." + "B".repeat(600), 1, "r", "u", "cto")).issue()).as("a long class").isEqualTo(1);
    }

    /// `.` matches every character, a line terminator included, as Postgres's `.` does in the table's CHECK.
    @Test
    void knownEntry_caseName_mayHoldALineTerminator_asTheTableAllows() {
        assertThat(ok(KnownEntry.knownEntry("a.B#line\nbreak", 1, "r", "u", "cto")).testId()).isEqualTo("a.B#line\nbreak");
    }

    /// What Postgres cannot store as given is refused here, as a 400, not stored as `?` or failed as a 500.
    @Test
    void knownEntry_refusesNulAndUnpairedSurrogates_inEveryText() {
        assertThat(field(KnownEntry.knownEntry("a.B#x\u0000", 1, "r", "u", "cto"))).isEqualTo("testId");
        assertThat(field(KnownEntry.knownEntry("a.B#x\uD800", 1, "r", "u", "cto"))).isEqualTo("testId");
        assertThat(field(KnownEntry.knownEntry("a.B#x\uDC00y", 1, "r", "u", "cto"))).isEqualTo("testId");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "x\u0000y", "u", "cto"))).isEqualTo("reason");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "x\uD83D", "u", "cto"))).isEqualTo("reason");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "r", "u\u0000", "cto"))).isEqualTo("until");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "r", "\uDE00u", "cto"))).isEqualTo("until");
        assertThat(ok(KnownEntry.knownEntry("a.B#\uD83D\uDE00", 1, "r \uD83D\uDE00", "u", "cto")).issue()).as("a paired surrogate")
                                                                                                    .isEqualTo(1);
    }

    @Test
    void knownEntry_namesTheFieldThatDoesNotParse() {
        assertThat(field(KnownEntry.knownEntry("a.B", 0, "r", "u", "cto"))).isEqualTo("issue");
        assertThat(field(KnownEntry.knownEntry("a.B", -3, "r", "u", "cto"))).isEqualTo("issue");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, " ", "u", "cto"))).isEqualTo("reason");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "\u001C", "u", "cto"))).as("FS is blank to Java").isEqualTo("reason");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "r", " \u001F ", "cto"))).as("US is blank to Java").isEqualTo("until");
        assertThat(ok(KnownEntry.knownEntry("a.B", 1, "\u00A0", "u", "cto")).reason()).as("NBSP is not").isEqualTo("\u00A0");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, null, "u", "cto"))).isEqualTo("reason");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "r", "", "cto"))).isEqualTo("until");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "r", null, "cto"))).isEqualTo("until");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "r", "u", "c-to"))).isEqualTo("createdBy");
        assertThat(field(KnownEntry.knownEntry("a.B", 1, "r", "u", "x".repeat(65)))).isEqualTo("createdBy");
        assertThat(field(KnownEntry.knownEntry(null, 1, "r", "u", "cto"))).isEqualTo("testId");
        assertThat(ok(KnownEntry.knownEntry("a.B", 1, "r", "u", "cto")).createdBy()).isEqualTo(OwnerTag.ownerTag("cto").unwrap());
    }

    /// The fields named by the failure (`Result.all` collects them in a composite), comma-separated.
    private static String field(Result<KnownEntry> result) {
        return failure(result).stream()
                              .map(KnownEntryTest::fieldOf)
                              .collect(Collectors.joining(","));
    }

    private static String fieldOf(Cause cause) {
        return switch (cause) {
            case DomainError.InvalidValue invalid -> invalid.field();
            case Cause other -> "not an InvalidValue: " + other.message();
        };
    }
}
