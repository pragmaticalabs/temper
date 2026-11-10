package org.pragmatica.temper.controller.t2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.store.api.NewVerdict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The server half of `POST fleet/verdicts` validation (positive control G6.e, the A1
/// part): the `verdictLine` the CLI sends is parsed again with the regex and must equal (verdict, repo, pr, sha,
/// base) of the body field by field (422 `VERDICT_LINE_MISMATCH`); a carry has no report fields and is ACCEPT only
/// (400); a reported verdict needs every report field (400).
class T2VerdictParseTest {
    private static final String X = "a".repeat(40);
    private static final String Y = "b".repeat(40);
    private static final String DIGEST = "d".repeat(64);
    private static final String LINE = "VERDICT: ACCEPT o/r#7@" + X + " base=main";

    private static T2Routes.VerdictBody reported(String verdict, String sha, String line) {
        return new T2Routes.VerdictBody("o/r", 7, sha, "main", verdict, "agent", some("/r/report.md"), some(DIGEST), some(line), none());
    }

    private static T2Routes.VerdictBody carry(String verdict, Option<String> path, Option<String> digest, Option<String> line) {
        return new T2Routes.VerdictBody("o/r", 7, Y, "main", verdict, "agent", path, digest, line, some(X));
    }

    private static Object failure(T2Routes.VerdictBody body) {
        return T2Routes.newVerdict(body).fold(cause -> cause, value -> value);
    }

    private static boolean badRequest(Object failure) {
        return failure instanceof ApiError.BadRequest;
    }

    @Test
    void reported_lineEqualToTheBody_isAReportBasis_lowerCased() {
        var verdict = T2Routes.newVerdict(reported("ACCEPT", X.toUpperCase(), LINE)).unwrap();

        assertThat(verdict.sha()).isEqualTo(X);
        assertThat(verdict.basis()).isEqualTo(new NewVerdict.Basis.Report("/r/report.md", DIGEST));
        assertThat(T2Routes.newVerdict(reported("ACCEPT", X, LINE + "  ")).isSuccess()).as("trailing whitespace").isTrue();
    }

    /// G6.e (server part): R2-5's report whose last line names another SHA (Y) while the body registers X is refused;
    /// so is a line differing in any one other field, or not parsing at all.
    @ParameterizedTest
    @ValueSource(strings = {"VERDICT: ACCEPT o/r#7@bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb base=main",
                            "VERDICT: FIX o/r#7@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa base=main",
                            "VERDICT: ACCEPT o/q#7@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa base=main",
                            "VERDICT: ACCEPT o/r#8@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa base=main",
                            "VERDICT: ACCEPT o/r#7@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa base=release",
                            "VERDICT: ACCEPT o/r#07@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa base=main",
                            "VERDICT: ACCEPT o/r#7@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa base=main extra",
                            "VERDICT: FIX o/r#7@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa base=main\nVERDICT: ACCEPT o/r#7@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa base=main",
                            " VERDICT: ACCEPT o/r#7@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa base=main",
                            ""})
    void reported_lineNotNamingTheBody_isVerdictLineMismatch(String line) {
        assertThat(failure(reported("ACCEPT", X, line))).isEqualTo(ApiError.General.VERDICT_LINE_MISMATCH);
    }

    @Test
    void reported_withoutAReportField_isBadRequest() {
        var base = reported("ACCEPT", X, LINE);

        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "main", "ACCEPT", "agent", none(), some(DIGEST), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "main", "ACCEPT", "agent", some("/r"), none(), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "main", "ACCEPT", "agent", some("/r"), some(DIGEST), none(), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "main", "ACCEPT", "agent", some("relative.md"), some(DIGEST), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "main", "ACCEPT", "agent", some("/" + "r".repeat(4096)), some(DIGEST), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "main", "ACCEPT", "agent", some("/" + "r".repeat(4095)), some(DIGEST), some(LINE), none())))
            .as("4096 characters is the limit").isInstanceOf(NewVerdict.class);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "main", "ACCEPT", "agent", some("/r"), some("d".repeat(63)), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(T2Routes.newVerdict(base).isSuccess()).isTrue();
    }

    @Test
    void head_withAnInvalidField_isBadRequest() {
        assertThat(failure(new T2Routes.VerdictBody("o", 7, X, "main", "ACCEPT", "agent", some("/r"), some(DIGEST), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 0, X, "main", "ACCEPT", "agent", some("/r"), some(DIGEST), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X.substring(1), "main", "ACCEPT", "agent", some("/r"), some(DIGEST), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "", "ACCEPT", "agent", some("/r"), some(DIGEST), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "main", "MAYBE", "agent", some("/r"), some(DIGEST), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, X, "main", "ACCEPT", "two words", some("/r"), some(DIGEST), some(LINE), none())))
            .matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody(null, 7, null, null, null, null, none(), none(), none(), none())))
            .matches(T2VerdictParseTest::badRequest);
    }

    @Test
    void carry_isACarriedBasis_whenAcceptWithoutReportFields() {
        var verdict = T2Routes.newVerdict(carry("ACCEPT", none(), none(), none())).unwrap();

        assertThat(verdict.basis()).isEqualTo(new NewVerdict.Basis.Carried(X));
        assertThat(verdict.sha()).isEqualTo(Y);
        assertThat(verdict.verdict()).isEqualTo("ACCEPT");
    }

    /// With `carriedFrom`, any report field is a 400, and so is a verdict other than ACCEPT.
    @Test
    void carry_withAReportField_orNotAccept_isBadRequest() {
        assertThat(failure(carry("ACCEPT", some("/r"), none(), none()))).matches(T2VerdictParseTest::badRequest);
        assertThat(failure(carry("ACCEPT", none(), some(DIGEST), none()))).matches(T2VerdictParseTest::badRequest);
        assertThat(failure(carry("ACCEPT", none(), none(), some(LINE)))).matches(T2VerdictParseTest::badRequest);
        assertThat(failure(carry("FIX", none(), none(), none()))).matches(T2VerdictParseTest::badRequest);
        assertThat(failure(new T2Routes.VerdictBody("o/r", 7, Y, "main", "ACCEPT", "agent", none(), none(), none(), some("abc"))))
            .matches(T2VerdictParseTest::badRequest);
    }
}
