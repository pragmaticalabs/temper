package org.pragmatica.temper.cli.transport;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

import static org.pragmatica.lang.Result.success;


/// One line of a fleet events reply: an event, or the heartbeat that ends every reply. Fields
/// are found by name and every other field is never looked at, so a field WP3 adds cannot break a decode; a field the
/// CLI reads that is missing or of the wrong JSON type fails the line.
///
/// An event keeps its `line` exactly as received: that is what the CLI prints, so a reader sees the controller's line,
/// not a re-rendering of it.
public sealed interface FleetLine {
    String HEARTBEAT = "heartbeat";
    /// The text form's head: `<epoch8>:<seq> <at> <kind> <subject> catchUp=<0|1> …`.
    Pattern TEXT = Pattern.compile("^([0-9a-f]{8}):([0-9]{1,19}) (\\S+) (\\S+) (\\S*) catchUp=([01])(?: .*)?$");

    /// An event line. `epoch` is the full temper epoch of an NDJSON line, its 8-character prefix of a text line; a text
    /// line has no `payload` (missing).
    record Event(String line, String epoch, long seq, String kind, String subject, boolean catchUp, JsonNode payload) implements FleetLine {}

    /// The heartbeat: `stale` per source name as the controller computed it at reply time, and the L0
    /// self-check's verdict with the probes that failed.
    record Heartbeat(String epoch, long seqHead, Map<String, Boolean> stale, boolean selfCheckOk, List<String> failed) implements FleetLine {}

    /// An NDJSON line: a heartbeat when its `kind` is `heartbeat`, else an event.
    static Result<FleetLine> ndjson(JsonMapper json, String line) {
        return json.readTree(line)
                   .flatMap(node -> decoded(line, node));
    }

    /// A line of a `format=text` reply: an event in the text form, or the heartbeat, which stays a JSON line.
    static Result<FleetLine> text(JsonMapper json, String line) {
        return line.startsWith("{")
               ? ndjson(json, line)
               : textEvent(line);
    }

    private static Result<FleetLine> decoded(String line, JsonNode node) {
        return text(node, "kind").flatMap(kind -> byKind(line, kind, node));
    }

    private static Result<FleetLine> byKind(String line, String kind, JsonNode node) {
        return HEARTBEAT.equals(kind)
               ? heartbeat(node)
               : event(line, kind, node);
    }

    private static Result<FleetLine> event(String line, String kind, JsonNode node) {
        return Result.all(text(node, "epoch"), number(node, "seq"), text(node, "subject"), bool(node, "catchUp")).map((epoch, seq, subject, catchUp) -> new Event(line,
                                                                                                                                                                  epoch,
                                                                                                                                                                  seq,
                                                                                                                                                                  kind,
                                                                                                                                                                  subject,
                                                                                                                                                                  catchUp,
                                                                                                                                                                  node.path("payload")));
    }

    private static Result<FleetLine> heartbeat(JsonNode node) {
        var selfCheck = node.path("selfCheck");

        return Result.all(text(node, "epoch"),
                          number(node, "seqHead"),
                          sources(node.path("sources")),
                          bool(selfCheck, "ok"),
                          failed(selfCheck.path("failed")))
                     .map(Heartbeat::new);
    }

    /// `sources` as name → `stale`.
    private static Result<Map<String, Boolean>> sources(JsonNode sources) {
        return Verify.ensure(sources, JsonNode::isObject, badField("sources")).flatMap(FleetLine::staleness);
    }

    private static Result<Map<String, Boolean>> staleness(JsonNode sources) {
        return Result.allOf(sources.properties().stream().map(FleetLine::sourceStale)).map(FleetLine::asMap);
    }

    private static Result<Map.Entry<String, Boolean>> sourceStale(Map.Entry<String, JsonNode> source) {
        return bool(source.getValue(), "stale").map(stale -> Map.entry(source.getKey(), stale));
    }

    private static Map<String, Boolean> asMap(List<Map.Entry<String, Boolean>> entries) {
        return entries.stream()
                      .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /// `selfCheck.failed`: absent reads as none failed.
    private static Result<List<String>> failed(JsonNode failed) {
        return failed.isMissingNode() || failed.isNull()
               ? success(List.of())
               : Verify.ensure(failed, JsonNode::isArray, badField("selfCheck.failed")).flatMap(FleetLine::names);
    }

    private static Result<List<String>> names(JsonNode array) {
        return Result.allOf(array.values().stream().map(FleetLine::name));
    }

    private static Result<String> name(JsonNode node) {
        return Option.from(node.stringValueOpt()).toResult(badField("selfCheck.failed[]"));
    }

    private static Result<FleetLine> textEvent(String line) {
        return Option.some(TEXT.matcher(line))
                     .filter(Matcher::matches)
                     .toResult(Causes.cause("not a fleet event line: " + line))
                     .flatMap(head -> textEvent(line, head));
    }

    private static Result<FleetLine> textEvent(String line, Matcher head) {
        return Number.parseLong(head.group(2)).map(seq -> new Event(line,
                                                                    head.group(1),
                                                                    seq,
                                                                    head.group(4),
                                                                    head.group(5),
                                                                    "1".equals(head.group(6)),
                                                                    MissingNode.getInstance()));
    }

    private static Result<String> text(JsonNode node, String field) {
        return Option.from(node.path(field).stringValueOpt()).toResult(badField(field));
    }

    private static Result<Long> number(JsonNode node, String field) {
        return Verify.ensure(node.path(field),
                             JsonNode::isIntegralNumber,
                             badField(field))
                     .map(JsonNode::longValue);
    }

    private static Result<Boolean> bool(JsonNode node, String field) {
        return Verify.ensure(node.path(field),
                             JsonNode::isBoolean,
                             badField(field))
                     .map(JsonNode::booleanValue);
    }

    private static Cause badField(String field) {
        return Causes.cause("missing or mistyped field " + field);
    }
}
