package org.pragmatica.temper.domain.known;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Values;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A known-failure registry entry to add through the management API (spec §9, m2-design §4.2): the test it covers, the
/// issue it waits on, why, the free-text condition that ends it, and who added it.
///
/// - `testId` is `class` or `class#name`, `class` being the report's classname (nested: `Outer$Inner`). A class entry
///   covers every case of that class, a case entry exactly that case ([KnownMatch]). It is judged as the
///   `known.test_id` CHECK judges it: [#TEST_ID] with `.` matching any character (Postgres's `.` does), and a case name
///   of at most [#MAX_CASE_NAME] characters, counted as Postgres counts them (code points), after the first `#`.
/// - `until` is required: the registry exists so that an entry does not outlive its condition (m2-design §4.4).
/// - every text is storable as given ([#isStorable]), so what was added is what is matched and listed.
/// - `createdBy` is the CLI's owner tag: attribution, not authority. With one write token, anyone holding it can add or
///   expire.
public record KnownEntry(String testId, int issue, String reason, String until, OwnerTag createdBy) {
    /// A dotted Java class name, then optionally `#` and a non-empty case name.
    public static final Pattern TEST_ID = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*(#.+)?$",
                                                          Pattern.DOTALL);

    public static final int MAX_CASE_NAME = 512;

    /// `testId` must be a class or a case of one, `issue` positive, `reason` and `until` present, `createdBy` an owner
    /// tag.
    public static Result<KnownEntry> knownEntry(String testId,
                                                int issue,
                                                String reason,
                                                String until,
                                                String createdBy) {
        return Result.all(testIdOf(testId),
                          Verify.ensure(issue,
                                        Verify.Is::positive,
                                        invalid("issue")),
                          text(reason, "reason"),
                          text(until, "until"),
                          OwnerTag.ownerTag(createdBy, "createdBy"))
                     .map(KnownEntry::new);
    }

    private static Result<String> testIdOf(String raw) {
        return Values.matching(raw, TEST_ID, "testId")
                     .flatMap(testId -> Verify.ensure(testId,
                                                      KnownEntry::caseNameFits,
                                                      invalid("testId")))
                     .flatMap(testId -> Verify.ensure(testId,
                                                      KnownEntry::isStorable,
                                                      invalid("testId")));
    }

    private static Result<String> text(String raw, String field) {
        return Verify.ensure(raw, Verify.Is::present, invalid(field)).flatMap(present -> Verify.ensure(present,
                                                                                                       KnownEntry::isStorable,
                                                                                                       invalid(field)));
    }

    /// Text Postgres stores as given: no NUL (a `text` value cannot hold one; the insert would fail as a 500, not a 400)
    /// and no unpaired surrogate (UTF-8 cannot encode one; it would be stored as `?` and the entry would match nothing).
    private static boolean isStorable(String text) {
        return text.codePoints()
                   .noneMatch(codePoint -> codePoint == 0 || Character.getType(codePoint) == Character.SURROGATE);
    }

    /// The text after the first `#` (a class has none) is at most [#MAX_CASE_NAME] code points.
    private static boolean caseNameFits(String testId) {
        var hash = testId.indexOf('#');

        return hash < 0 || testId.codePointCount(hash + 1, testId.length()) <= MAX_CASE_NAME;
    }
}
