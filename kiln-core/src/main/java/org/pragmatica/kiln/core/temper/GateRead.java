package org.pragmatica.kiln.core.temper;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Verify;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.some;


/// kiln's own lenient read of temper's `GET /api/v1/gate/{sha}` (spec §4.5, E-1). It never decodes with temper's
/// classes, so a temper deploy never forces a kiln deploy.
public sealed interface GateRead {
    /// An answer: `sha` and `answer` are required; `job` and `judge` are absent on a NONE answer.
    record Answered(String sha, String answer, Option<Long> job, Option<String> judge) implements GateRead {}

    /// A required field is missing: shown as `TEMPER-SCHEMA(<field>)`, distinct from `TEMPER-DOWN`. `body` when the
    /// answer is not JSON at all: empty, malformed, or holding a key twice.
    record SchemaBroken(String field) implements GateRead {}

    /// Requires only `sha` and `answer` (non-blank strings) and ignores every other field: a `job` or `judge` that is
    /// absent, null or of another type reads as none, never as a broken answer.
    ///
    /// Stated residual: the parser's default limits still apply to fields it ignores, so an unknown field holding a
    /// number of more than 1000 digits, or nesting deeper than about 1000 levels, reads as `TEMPER-SCHEMA(body)`. That
    /// is the safe direction (no answer is shown), and temper sends neither.
    static GateRead decode(String body) {
        // FER: a body that is not JSON degrades to TEMPER-SCHEMA(body), shown apart from TEMPER-DOWN; GateRead has no
        // slot for the parser's cause, and the field name is the whole diagnosis the source state carries.
        return GateJson.MAPPER.readTree(body)
                              .option()
                              .filter(GateRead::isPresent)
                              .map(GateRead::read)
                              .or(new SchemaBroken("body"));
    }

    /// An empty body parses to a missing node: no JSON at all.
    private static boolean isPresent(JsonNode gate) {
        return ! gate.isMissingNode();
    }

    private static GateRead read(JsonNode gate) {
        return Option.all(text(gate, "sha"),
                          text(gate, "answer"))
                     .<GateRead> map((sha, answer) -> new Answered(sha,
                                                                   answer,
                                                                   job(gate),
                                                                   text(gate, "judge")))
                     .or(() -> new SchemaBroken(missing(gate)));
    }

    /// The first required field that is not there, in the order the decoder requires them.
    private static String missing(JsonNode gate) {
        return text(gate, "sha").isPresent()
               ? "answer"
               : "sha";
    }

    private static Option<String> text(JsonNode gate, String field) {
        return some(gate.path(field)).filter(JsonNode::isString)
                   .map(JsonNode::stringValue)
                   .filter(Verify.Is::notBlank);
    }

    private static Option<Long> job(JsonNode gate) {
        return some(gate.path("job")).filter(JsonNode::isIntegralNumber)
                   .filter(JsonNode::canConvertToLong)
                   .map(JsonNode::longValue);
    }
}
