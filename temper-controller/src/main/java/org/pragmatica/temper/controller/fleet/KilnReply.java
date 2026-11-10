package org.pragmatica.temper.controller.fleet;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.some;


/// kiln's `GET /api/v1/changes` reply: exactly one of [Changes], [Reset] or [Gap]. Unknown fields
/// are ignored and unknown change kinds are kept as sent, so kiln can grow (WP2) before temper does.
public sealed interface KilnReply {
    long head();
    Map<String, SourceInfo> sources();

    /// kiln's per-source success, `lastOkAt` = its `last_success_at`; kiln's state word is never sent nor read (F13).
    record SourceInfo(Option<Instant> lastOkAt, int periodS) {}

    record Change(long id,
                  String kind,
                  Option<String> repo,
                  Option<Long> pr,
                  String subject,
                  Option<String> before,
                  Option<String> after,
                  Instant observedAt,
                  Option<Instant> recordedAt,
                  JsonNode payload) {}

    record Changes(String epoch, long floor, long head, Map<String, SourceInfo> sources, List<Change> changes) implements KilnReply {
        public Changes {
            sources = Map.copyOf(sources);
            changes = List.copyOf(changes);
        }
    }

    /// The `epoch` asked for is not kiln's: a rebuilt kiln database.
    record Reset(String epoch, long head, Map<String, SourceInfo> sources) implements KilnReply {
        public Reset {
            sources = Map.copyOf(sources);
        }
    }

    /// Retention deleted the changes after `from`; `to` is kiln's floor.
    record Gap(long from, long to, String epoch, long floor, long head, Map<String, SourceInfo> sources) implements KilnReply {
        public Gap {
            sources = Map.copyOf(sources);
        }
    }

    static Result<KilnReply> decode(JsonNode body) {
        return switch (shape(body)) {
            case "reset" -> reset(body);
            case "gap" -> gap(body);
            default -> changes(body);
        };
    }

    private static String shape(JsonNode body) {
        return body.has("reset")
               ? "reset"
               : gapOrChanges(body);
    }

    private static String gapOrChanges(JsonNode body) {
        return body.has("gap")
               ? "gap"
               : "changes";
    }

    private static Result<KilnReply> reset(JsonNode body) {
        return Result.all(text(body.path("reset"),
                               "epoch"),
                          number(body, "head"),
                          sources(body))
                     .map(Reset::new);
    }

    private static Result<KilnReply> gap(JsonNode body) {
        return Result.all(number(body.path("gap"),
                                 "from"),
                          number(body.path("gap"),
                                 "to"),
                          text(body, "epoch"),
                          number(body, "floor"),
                          number(body, "head"),
                          sources(body))
                     .map(Gap::new);
    }

    private static Result<KilnReply> changes(JsonNode body) {
        return Result.all(text(body, "epoch"),
                          number(body, "floor"),
                          number(body, "head"),
                          sources(body),
                          changeList(body.path("changes")))
                     .map(Changes::new);
    }

    private static Result<List<Change>> changeList(JsonNode changes) {
        return changes.isArray()
               ? Result.allOf(StreamSupport.stream(changes.spliterator(), false).map(KilnReply::change).toList())
               : malformed("changes");
    }

    private static Result<Change> change(JsonNode change) {
        return Result.all(number(change, "id"), text(change, "kind"), instant(change, "observedAt")).map((id, kind, observedAt) -> new Change(id,
                                                                                                                                              kind,
                                                                                                                                              optionalText(change,
                                                                                                                                                           "repo"),
                                                                                                                                              optionalNumber(change,
                                                                                                                                                             "pr"),
                                                                                                                                              optionalText(change,
                                                                                                                                                           "subject").or(""),
                                                                                                                                              optionalText(change,
                                                                                                                                                           "before"),
                                                                                                                                              optionalText(change,
                                                                                                                                                           "after"),
                                                                                                                                              observedAt,
                                                                                                                                              optionalText(change,
                                                                                                                                                           "recordedAt").flatMap(KilnReply::parsed),
                                                                                                                                              change.path("payload")));
    }

    private static Result<Map<String, SourceInfo>> sources(JsonNode body) {
        var sources = body.path("sources");

        return sources.isObject()
               ? Result.allOf(sources.properties().stream().map(KilnReply::source).toList()).map(KilnReply::byName)
               : Result.success(Map.of());
    }

    private static Result<Map.Entry<String, SourceInfo>> source(Map.Entry<String, JsonNode> entry) {
        return number(entry.getValue(), "periodS").map(period -> Map.entry(entry.getKey(),
                                                                           new SourceInfo(optionalText(entry.getValue(),
                                                                                                       "lastOkAt").flatMap(KilnReply::parsed),
                                                                                          Math.toIntExact(Math.max(1,
                                                                                                                   period)))));
    }

    private static Map<String, SourceInfo> byName(List<Map.Entry<String, SourceInfo>> entries) {
        var byName = new LinkedHashMap<String, SourceInfo>();

        entries.forEach(entry -> byName.put(entry.getKey(), entry.getValue()));

        return byName;
    }

    private static Result<String> text(JsonNode node, String field) {
        return optionalText(node, field).toResult(Causes.cause("kiln reply: missing text field " + field));
    }

    private static Result<Long> number(JsonNode node, String field) {
        return optionalNumber(node, field).toResult(Causes.cause("kiln reply: missing number field " + field));
    }

    private static Result<Instant> instant(JsonNode node, String field) {
        return optionalText(node, field).flatMap(KilnReply::parsed)
                           .toResult(Causes.cause("kiln reply: missing instant field " + field));
    }

    private static Option<String> optionalText(JsonNode node, String field) {
        return some(node.path(field)).filter(JsonNode::isString)
                   .map(JsonNode::stringValue);
    }

    private static Option<Long> optionalNumber(JsonNode node, String field) {
        return some(node.path(field)).filter(JsonNode::isIntegralNumber)
                   .filter(JsonNode::canConvertToLong)
                   .map(JsonNode::longValue);
    }

    private static Option<Instant> parsed(String text) {
        return Result.lift(Causes::fromThrowable,
                           () -> Instant.parse(text))
                     .option();
    }

    private static <T> Result<T> malformed(String field) {
        return Causes.cause("kiln reply: malformed " + field).result();
    }
}
