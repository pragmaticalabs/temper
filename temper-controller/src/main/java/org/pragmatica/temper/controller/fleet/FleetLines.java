package org.pragmatica.temper.controller.fleet;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.api.FleetEventLine;
import org.pragmatica.temper.store.api.FleetEventRow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;


/// The two renderings of fleet lines: NDJSON, and the text form
/// `<epoch8>:<seq> <at> <kind> <subject> catchUp=<0|1> <k=v ...>` (single spaces; a value with any whitespace, `=`
/// or a non-ASCII character, or empty, or one that starts with `b64:` itself, is `b64:` + unpadded base64url; a key not
/// matching `[A-Za-z0-9_.-]+` the same), so one event is always one line, and a reader that decodes every `b64:` value
/// gets the raw text back (lead ruling F3). The heartbeat line is always JSON.
public sealed interface FleetLines {
    /// Printable ASCII without space and `=` (so no whitespace, no control and no non-ASCII character).
    Pattern PLAIN_VALUE = Pattern.compile("[\\x21-\\x3C\\x3E-\\x7E]+");
    Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_.-]+");
    /// The encoded form's prefix; a raw value that starts with it is encoded too, so the text form stays injective.
    String B64 = "b64:";

    enum Format {
        NDJSON,
        TEXT
    }

    static Result<FleetEventLine> line(JsonMapper mapper, FleetEventRow row) {
        return mapper.readTree(row.payload())
                     .map(payload -> FleetEventLine.fleetEventLine(row.epoch(),
                                                                   row.seq(),
                                                                   row.at(),
                                                                   row.observedAt(),
                                                                   row.kind(),
                                                                   row.subject(),
                                                                   row.catchUp(),
                                                                   row.source(),
                                                                   payload));
    }

    static Result<String> render(JsonMapper mapper, Format format, FleetEventLine line) {
        return switch (format) {
            case NDJSON -> mapper.writeAsString(line);
            case TEXT -> Result.success(text(line));
        };
    }

    static String text(FleetEventLine line) {
        return Stream.concat(Stream.of(epoch8(line.epoch()) + ":" + line.seq(),
                                       line.at().toString(),
                                       line.kind(),
                                       value(line.subject()),
                                       "catchUp=" + (line.catchUp()
                                                     ? 1
                                                     : 0)),
                             fields(line.payload()))
                     .collect(Collectors.joining(" "));
    }

    /// A value as the text form carries it: as is, or `b64:` base64url when it is empty, holds any whitespace, `=` or
    /// a non-ASCII character, or starts with `b64:` (lead ruling F3).
    static String value(String raw) {
        return raw.isEmpty() || raw.startsWith(B64) || !PLAIN_VALUE.matcher(raw).matches()
               ? b64(raw)
               : raw;
    }

    /// A payload key as the text form carries it: as is when it matches `[A-Za-z0-9_.-]+`, else `b64:`.
    static String key(String raw) {
        return PLAIN_KEY.matcher(raw).matches()
               ? raw
               : b64(raw);
    }

    private static String b64(String raw) {
        return B64 + Base64.getUrlEncoder()
                           .withoutPadding()
                           .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /// The payload's top-level fields as `k=v`; a string is its text, anything else its JSON.
    private static Stream<String> fields(JsonNode payload) {
        return payload.isObject()
               ? payload.properties()
                        .stream()
                        .map(field -> key(field.getKey()) + "=" + value(scalar(field.getValue())))
               : Stream.empty();
    }

    static String scalar(JsonNode node) {
        return node.isString()
               ? node.stringValue()
               : node.toString();
    }

    private static String epoch8(String epoch) {
        return epoch.length() > 8
               ? epoch.substring(0, 8)
               : epoch;
    }

    /// An empty JSON object, for synthetic lines built without a payload.
    static JsonNode empty() {
        return JsonNodeFactory.instance.objectNode();
    }

    record unused() implements FleetLines {}
}
