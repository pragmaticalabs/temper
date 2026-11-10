package org.pragmatica.kiln.core.temper;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.temper.GateRead.Answered;
import org.pragmatica.kiln.core.temper.GateRead.SchemaBroken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §4.5 with E-1: kiln's own lenient gate read requires only `sha` and `answer`; everything else, unknown fields
/// included, may be absent. The bodies follow temper's `GateView` (`temper-protocol` api), written as temper sends it.
class GateReadTest {
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";
    private static final String PASSED = """
        {"sha":"%s","judge":"rc5-2026-10-30","answer":"PASSED","job":4711,"state":"PASSED",\
        "counts":{"total":10,"failed":0},"annotations":[],"evidence":"stored","previous":null,"conflict":[],\
        "running":null,"older":[],"judgeChanged":[],"unjudged":[],"reruns":[]}""".formatted(SHA);
    private static final String NONE = """
        {"sha":"0cf5631","answer":"NONE","state":null,"counts":null,"annotations":[],"evidence":null,\
        "previous":null,"conflict":[],"running":null,"older":[],"judgeChanged":[],"unjudged":[],"reruns":[]}""";

    @Test
    void decode_readsAFullAnswer_ignoringFieldsItDoesNotKnow() {
        assertThat(GateRead.decode(PASSED)).isEqualTo(new Answered(SHA, "PASSED", some(4711L), some("rc5-2026-10-30")));
        assertThat(GateRead.decode(PASSED.replace("\"reruns\":[]", "\"reruns\":[],\"futureField\":{\"a\":1}")))
            .isEqualTo(new Answered(SHA, "PASSED", some(4711L), some("rc5-2026-10-30")));
    }

    /// E-1: a NONE answer has no `job` and no `judge`, and is an answer, never TEMPER-SCHEMA.
    @Test
    void decode_readsANoneAnswer_withoutJobOrJudge() {
        assertThat(GateRead.decode(NONE)).isEqualTo(new Answered("0cf5631", "NONE", none(), none()));
        assertThat(GateRead.decode(NONE.replace("\"answer\":\"NONE\"", "\"answer\":\"NONE\",\"job\":null,\"judge\":null")))
            .isEqualTo(new Answered("0cf5631", "NONE", none(), none()));
    }

    /// Only `sha` and `answer` are required: a `job` or `judge` of another type reads as none.
    @Test
    void decode_readsAMistypedJobOrJudgeAsNone() {
        var body = PASSED.replace("\"job\":4711", "\"job\":\"4711\"")
                         .replace("\"judge\":\"rc5-2026-10-30\"", "\"judge\":7");

        assertThat(GateRead.decode(body)).isEqualTo(new Answered(SHA, "PASSED", none(), none()));
        assertThat(GateRead.decode(PASSED.replace("\"job\":4711", "\"job\":47.5")))
            .isEqualTo(new Answered(SHA, "PASSED", none(), some("rc5-2026-10-30")));
        assertThat(GateRead.decode(PASSED.replace("\"job\":4711", "\"job\":99999999999999999999")))
            .isEqualTo(new Answered(SHA, "PASSED", none(), some("rc5-2026-10-30")));
    }

    @Test
    void decode_isSchemaBroken_withoutAnswer() {
        assertThat(GateRead.decode(NONE.replace("\"answer\":\"NONE\",", ""))).isEqualTo(new SchemaBroken("answer"));
        assertThat(GateRead.decode(NONE.replace("\"answer\":\"NONE\"", "\"answer\":null")))
            .isEqualTo(new SchemaBroken("answer"));
        assertThat(GateRead.decode(NONE.replace("\"answer\":\"NONE\"", "\"answer\":\" \"")))
            .isEqualTo(new SchemaBroken("answer"));
        assertThat(GateRead.decode(NONE.replace("\"answer\":\"NONE\"", "\"answer\":{\"v\":\"NONE\"}")))
            .isEqualTo(new SchemaBroken("answer"));
    }

    /// The old spec name `verdict` is not `answer`: a body carrying only it is broken (E-1).
    @Test
    void decode_isSchemaBroken_whenTheResultTravelsAsVerdict() {
        assertThat(GateRead.decode(PASSED.replace("\"answer\"", "\"verdict\""))).isEqualTo(new SchemaBroken("answer"));
    }

    @Test
    void decode_isSchemaBroken_withoutSha_namingShaFirst() {
        assertThat(GateRead.decode(PASSED.replace("\"sha\":\"" + SHA + "\",", ""))).isEqualTo(new SchemaBroken("sha"));
        assertThat(GateRead.decode("{\"judge\":\"j\"}")).isEqualTo(new SchemaBroken("sha"));
        assertThat(GateRead.decode("[]")).isEqualTo(new SchemaBroken("sha"));
    }

    @Test
    void decode_isSchemaBroken_forABodyThatIsNotJson() {
        assertThat(GateRead.decode("<html>502 Bad Gateway</html>")).isEqualTo(new SchemaBroken("body"));
        assertThat(GateRead.decode("{\"sha\":")).isEqualTo(new SchemaBroken("body"));
    }

    /// An empty body holds no JSON at all: `body`, never a missing `sha`.
    @Test
    void decode_isSchemaBroken_body_forAnEmptyBody() {
        assertThat(GateRead.decode("")).isEqualTo(new SchemaBroken("body"));
        assertThat(GateRead.decode(" \n")).isEqualTo(new SchemaBroken("body"));
        assertThat(GateRead.decode("{}")).isEqualTo(new SchemaBroken("sha"));
    }

    /// A key given twice, required or not, breaks the body: last-wins would pick an answer silently.
    @Test
    void decode_isSchemaBroken_body_forADuplicateKey() {
        assertThat(GateRead.decode(PASSED.replace("\"answer\":\"PASSED\"", "\"answer\":\"FAILED\",\"answer\":\"PASSED\"")))
            .isEqualTo(new SchemaBroken("body"));
        assertThat(GateRead.decode(PASSED.replace("\"reruns\":[]", "\"reruns\":[],\"reruns\":[]")))
            .isEqualTo(new SchemaBroken("body"));
        assertThat(GateRead.decode(PASSED.replace("\"reruns\":[]", "\"reruns\":[],\"rerunz\":[]")))
            .isEqualTo(new Answered(SHA, "PASSED", some(4711L), some("rc5-2026-10-30")));
    }

    /// Stated residual (GateRead javadoc): the parser's limits apply to ignored fields too. An unknown number over 1000
    /// digits or nesting 2000 deep reads as `body`; the same fields well inside the limits are ignored.
    @Test
    void decode_isSchemaBroken_body_forAnUnknownFieldPastTheParserLimits() {
        assertThat(GateRead.decode(withUnknown("1".repeat(1001)))).isEqualTo(new SchemaBroken("body"));
        assertThat(GateRead.decode(withUnknown("[".repeat(2000) + "]".repeat(2000)))).isEqualTo(new SchemaBroken("body"));
        assertThat(GateRead.decode(withUnknown("1".repeat(900)))).isEqualTo(new Answered(SHA, "PASSED", some(4711L), some("rc5-2026-10-30")));
        assertThat(GateRead.decode(withUnknown("[".repeat(100) + "]".repeat(100))))
            .isEqualTo(new Answered(SHA, "PASSED", some(4711L), some("rc5-2026-10-30")));
    }

    private static String withUnknown(String value) {
        return PASSED.replace("\"reruns\":[]", "\"reruns\":[],\"future\":" + value);
    }
}
