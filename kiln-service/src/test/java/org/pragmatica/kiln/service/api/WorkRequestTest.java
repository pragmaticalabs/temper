package org.pragmatica.kiln.service.api;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.service.http.ApiError;
import org.pragmatica.kiln.service.store.WorkEvent;
import org.pragmatica.kiln.service.store.WorkPhase;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [WorkRequest]: the ledger's write is read strictly, each refusal a 400 ([ApiError.BadRequest]) naming its field.
class WorkRequestTest {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    private static WorkEvent accepted(String body) {
        return WorkRequest.workEvent(body, NOW).fold(cause -> Assertions.fail("expected an event, got " + cause.message()), event -> event);
    }

    private static Cause refused(String body) {
        return WorkRequest.workEvent(body, NOW).fold(cause -> cause, event -> Assertions.fail("expected a refusal, got " + event));
    }

    private static String body(String fields) {
        return "{\"item\":\"T1-WP5\",\"phase\":\"verify\",\"round\":1" + fields + "}";
    }

    @Test
    void workEvent_readsEveryField() {
        var event = accepted("{\"item\":\"T2-WP-0T\",\"title\":\"V5 base tip\",\"phase\":\"in-pr\",\"round\":3,\"coder\":\"t2wp0t\","
                             + "\"verifier\":\"t2wp0t.v\",\"note\":\"PR #70\",\"at\":\"2026-11-03T11:00:00Z\"}");

        assertThat(event).isEqualTo(new WorkEvent("T2-WP-0T", "T2", some("V5 base tip"), WorkPhase.IN_PR, 3, some("t2wp0t"), some("t2wp0t.v"),
                                                  some("PR #70"), Instant.parse("2026-11-03T11:00:00Z")));
    }

    /// Optional fields absent or `null` are none; `at` absent is kiln's clock; round 0 is a round.
    @Test
    void workEvent_optionalFields_absentOrNull() {
        var bare = accepted("{\"item\":\"T1\",\"phase\":\"design\",\"round\":0}");
        var nulls = accepted(body(",\"title\":null,\"coder\":null,\"verifier\":null,\"note\":null,\"at\":null"));

        assertThat(bare).isEqualTo(new WorkEvent("T1", "T1", none(), WorkPhase.DESIGN, 0, none(), none(), none(), NOW));
        assertThat(nulls).isEqualTo(new WorkEvent("T1-WP5", "T1", none(), WorkPhase.VERIFY, 1, none(), none(), none(), NOW));
    }

    @Test
    void phase_isExactlyALabel() {
        for (var phase : WorkPhase.values()) {
            assertThat(accepted("{\"item\":\"T1-WP5\",\"phase\":\"" + phase.label() + "\",\"round\":1}").phase()).isEqualTo(phase);
        }
        assertThat(WorkPhase.IN_PR.label()).isEqualTo("in-pr");
        for (var unknown : List.of("reviewing", "VERIFY", "in_pr", " verify", "")) {
            assertBadRequest(refused("{\"item\":\"T1-WP5\",\"phase\":\"" + unknown + "\",\"round\":1}"), "phase");
        }
        assertBadRequest(refused("{\"item\":\"T1-WP5\",\"round\":1}"), "phase");
        assertBadRequest(refused("{\"item\":\"T1-WP5\",\"phase\":3,\"round\":1}"), "phase");
    }

    @Test
    void round_isAnIntegerZeroOrMore() {
        for (var round : List.of("-1", "1.5", "\"2\"", "null", "2147483648", "true")) {
            assertBadRequest(refused("{\"item\":\"T1-WP5\",\"phase\":\"verify\",\"round\":" + round + "}"), "round");
        }
        assertBadRequest(refused("{\"item\":\"T1-WP5\",\"phase\":\"verify\"}"), "round");
        assertThat(accepted("{\"item\":\"T1-WP5\",\"phase\":\"verify\",\"round\":2147483647}").round()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void item_isAWorkPackageId() {
        for (var id : List.of("T1 WP5", "T1--WP5", "-T1", "T1-", "T1_WP5", "T1/WP5", "", "<b>", "x".repeat(65))) {
            assertBadRequest(refused("{\"item\":\"" + id + "\",\"phase\":\"verify\",\"round\":1}"), "item");
        }
        assertBadRequest(refused("{\"phase\":\"verify\",\"round\":1}"), "item");
        assertThat(accepted("{\"item\":\"" + "x".repeat(64) + "\",\"phase\":\"verify\",\"round\":1}").item()).isEqualTo("X".repeat(64));
        assertThat(WorkRequest.item("T2-WP-0T")).isEqualTo("T2");
        assertThat(WorkRequest.item("kiln")).isEqualTo("kiln");
    }

    @Test
    void coderAndVerifier_areAgentNames() {
        assertBadRequest(refused(body(",\"coder\":\"t1 wp5\"")), "coder");
        assertBadRequest(refused(body(",\"verifier\":\"-v\"")), "verifier");
        assertBadRequest(refused(body(",\"verifier\":\"" + "v".repeat(65) + "\"")), "verifier");
        assertBadRequest(refused(body(",\"coder\":7")), "coder");
        assertThat(accepted(body(",\"coder\":\"" + "c".repeat(64) + "\"")).coder()).isEqualTo(some("c".repeat(64)));
    }

    /// Lengths are code points, as Postgres' `char_length` counts them: 300 astral characters (600 UTF-16 units) is a
    /// note, 301 is not.
    @Test
    void noteAndTitle_lengthsInCodePoints() {
        var astral = "😀";

        assertThat(accepted(body(",\"note\":\"" + astral.repeat(300) + "\"")).note()).isEqualTo(some(astral.repeat(300)));
        assertBadRequest(refused(body(",\"note\":\"" + "n".repeat(301) + "\"")), "note");
        assertThat(accepted(body(",\"title\":\"" + "t".repeat(200) + "\"")).title()).isEqualTo(some("t".repeat(200)));
        assertBadRequest(refused(body(",\"title\":\"" + "t".repeat(201) + "\"")), "title");
        assertBadRequest(refused(body(",\"title\":\"  \"")), "title");
        assertThat(accepted(body(",\"note\":\"\"")).note()).isEqualTo(some(""));
    }

    @Test
    void at_isAnInstant_atMostAMinuteAhead() {
        assertThat(accepted(body(",\"at\":\"" + NOW.plusSeconds(60) + "\"")).at()).isEqualTo(NOW.plusSeconds(60));
        assertBadRequest(refused(body(",\"at\":\"" + NOW.plusSeconds(61) + "\"")), "at");
        assertBadRequest(refused(body(",\"at\":\"yesterday\"")), "at");
        assertBadRequest(refused(body(",\"at\":1730000000")), "at");
    }

    /// Fix 1: an `at` more than 30 days before kiln's clock is refused, so a mistyped `--at` can never move an item's
    /// submission back for good; 30 days exactly is accepted (a two-day backfill fits well inside).
    @Test
    void at_isAtMostThirtyDaysOld() {
        assertThat(accepted(body(",\"at\":\"" + NOW.minus(java.time.Duration.ofDays(30)) + "\"")).at()).isEqualTo(NOW.minus(java.time.Duration.ofDays(30)));
        assertBadRequest(refused(body(",\"at\":\"" + NOW.minus(java.time.Duration.ofDays(30)).minusSeconds(1) + "\"")), "at");
        assertBadRequest(refused(body(",\"at\":\"1970-01-01T00:00:00Z\"")), "at");
        assertThat(accepted(body(",\"at\":\"" + NOW.minus(java.time.Duration.ofDays(2)) + "\"")).at()).isEqualTo(NOW.minus(java.time.Duration.ofDays(2)));
    }

    /// Fix 4: `at` is truncated to microseconds, Postgres' precision, so the answer echoes what the store holds; the
    /// default (kiln's clock) too.
    @Test
    void at_isTruncatedToMicroseconds() {
        assertThat(accepted(body(",\"at\":\"2026-11-03T11:00:00.123456789Z\"")).at()).isEqualTo(Instant.parse("2026-11-03T11:00:00.123456Z"));
        assertThat(WorkRequest.workEvent(body(""), NOW.plusNanos(1_999)).unwrap().at()).isEqualTo(NOW.plusNanos(1_000));
    }

    /// Fix 5: the server is the gate: a C0 or C1 control character (NUL, ESC, a newline, U+0085) or a lone surrogate in
    /// a text is a 400 naming the field; a valid surrogate pair is a character like any other.
    @Test
    void controlCharacters_andLoneSurrogates_areRefused() {
        for (var bad : List.of("\\u0000", "\\u001b[31m", "a\\nb", "\\u0085", "\\u007f", "x\\ud800y", "\\udc00")) {
            assertBadRequest(refused(body(",\"title\":\"" + bad + "\"")), "title");
            assertBadRequest(refused(body(",\"note\":\"" + bad + "\"")), "note");
        }
        assertThat(accepted(body(",\"note\":\"\\ud83d\\ude00 ok\"")).note()).isEqualTo(some("\uD83D\uDE00 ok"));
    }

    /// Fix 7: the id is upper-cased, so `t1-wp5` and `T1-WP5` are one card; the item is the upper-cased first segment.
    @Test
    void id_isUpperCased() {
        var event = accepted("{\"item\":\"t2-wp-0t\",\"phase\":\"verify\",\"round\":1}");

        assertThat(event.id()).isEqualTo("T2-WP-0T");
        assertThat(event.item()).isEqualTo("T2");
    }

    /// Not an object, not JSON, a field given twice, or a field outside the list: the body is refused whole.
    @Test
    void body_strict() {
        for (var body : List.of("not json", "[]", "\"x\"", "", body(",\"verifer\":\"x\""),
                                "{\"item\":\"T1-WP5\",\"item\":\"T1-WP6\",\"phase\":\"verify\",\"round\":1}")) {
            assertBadRequest(refused(body), "body");
        }
    }

    /// Every failing field is reported, not only the first.
    @Test
    void everyFailingField_isNamed() {
        var cause = refused("{\"item\":\"bad id\",\"phase\":\"nope\",\"round\":-1}");

        assertThat(cause.message()).contains("item", "phase", "round");
    }

    private static void assertBadRequest(Cause cause, String field) {
        assertThat(cause.stream().filter(ApiError.BadRequest.class::isInstance).map(ApiError.BadRequest.class::cast).map(ApiError.BadRequest::parameter))
            .as(cause.message())
            .contains(field);
    }
}
