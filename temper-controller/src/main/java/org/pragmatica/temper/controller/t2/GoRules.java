package org.pragmatica.temper.controller.t2;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.GoBasis;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The fleet-row invalidation rules, shared by the `t2:detector` and the GO creation scan.
/// A row matches a GO only when its seq is above the GO's `fleetSeq` (events at or below it were
/// committed before the gate's server reads began, so the gate judged them):
///
/// - `checks-changed` (R3-3): kinds `check-concluded`, `check-cell`, `checks-settled` with `payload.repo` and
///   `payload.pr` the GO's, and `payload.head` absent or the GO's sha. `check-cell` carries no head, so any cell change
///   of the PR invalidates: fail-safe.
/// - `base-moved`: kind `base-moved` with `payload.treeChanged == true`, `payload.repo` and `payload.base` the GO's,
///   and `payload.after` not the GO's base tip (a GO computed at the new tip judged it already).
///
/// The detector's own kinds (`merged`, `ungated-merge`, `queue-advanced`, `go-invalidated`, `gap`) match none.
public sealed interface GoRules {
    String CHECKS_CHANGED = "checks-changed";
    String BASE_MOVED = "base-moved";
    Set<String> CHECK_KINDS = Set.of("check-concluded", "check-cell", "checks-settled");

    /// The reason of the first row in `rows` (seq order) that matches `go`.
    static Option<String> first(GoBasis go, List<FleetEventRow> rows) {
        return Option.from(rows.stream().flatMap(row -> reason(go, row).stream()).findFirst());
    }

    /// The reason `row` invalidates `go` for, if it does.
    static Option<String> reason(GoBasis go, FleetEventRow row) {
        return some(row).filter(candidate -> candidate.seq() > go.fleetSeq())
                   .flatMap(candidate -> TemperJson.mapper()
                                                   .readTree(candidate.payload())
                                                   .option())
                   .flatMap(payload -> matched(go,
                                               row.kind(),
                                               payload));
    }

    private static Option<String> matched(GoBasis go, String kind, JsonNode payload) {
        return CHECK_KINDS.contains(kind)
               ? checks(go, payload)
               : base(go, kind, payload);
    }

    private static Option<String> checks(GoBasis go, JsonNode payload) {
        return some(CHECKS_CHANGED).filter(_ -> samePr(go, payload) && headAbsentOr(go.sha(), payload));
    }

    private static Option<String> base(GoBasis go, String kind, JsonNode payload) {
        return kind.equals(BASE_MOVED)
               ? some(BASE_MOVED).filter(_ -> movedUnder(go, payload))
               : none();
    }

    private static boolean samePr(GoBasis go, JsonNode payload) {
        return is(payload, "repo", go.repo())
               && payload.path("pr")
                         .isIntegralNumber()
               && payload.path("pr")
                         .longValue() == go.pr();
    }

    /// An absent (or JSON-null) head is fail-safe: it matches every head.
    private static boolean headAbsentOr(String sha, JsonNode payload) {
        return text(payload, "head").map(sha::equals)
                   .or(true);
    }

    /// An absent `after` is fail-safe: it is not the GO's tip.
    private static boolean movedUnder(GoBasis go, JsonNode payload) {
        return treeChanged(payload)
               && is(payload, "repo", go.repo())
               && is(payload, "base", go.base())
               && !is(payload, "after", go.baseTip());
    }

    private static boolean treeChanged(JsonNode payload) {
        return payload.path("treeChanged")
                      .isBoolean() && payload.path("treeChanged")
                                             .booleanValue();
    }

    private static boolean is(JsonNode payload, String key, String value) {
        return text(payload, key).filter(value::equals)
                   .isPresent();
    }

    private static Option<String> text(JsonNode payload, String key) {
        return some(payload.path(key)).filter(JsonNode::isString)
                   .map(JsonNode::stringValue);
    }

    record unused() implements GoRules {}
}
