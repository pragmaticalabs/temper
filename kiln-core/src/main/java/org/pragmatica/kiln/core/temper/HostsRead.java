package org.pragmatica.kiln.core.temper;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// kiln's own tolerant read of temper's `GET /api/v1/fleet/alarms`. Unknown fields are
/// ignored, because temper and kiln are deployed separately and later work packages add fields. It never decodes with
/// temper's classes.
public sealed interface HostsRead {
    /// An answer: every required field was there.
    record Answered(List<HostRow> hosts) implements HostsRead {
        public Answered {
            hosts = List.copyOf(hosts);
        }
    }

    /// A required field is missing: shown as `TEMPER-SCHEMA(<field>)`. `body` when the answer is not JSON at all.
    record SchemaBroken(String field) implements HostsRead {}

    /// One host: its `status` (`ok`, `UNREACHABLE`, `NOT-COLLECTED`, verbatim), when its snapshot was taken, its
    /// configured retirement and measured skew, and its current alarms.
    record HostRow(String host,
                   String status,
                   Option<Instant> takenAt,
                   Option<Instant> retireAt,
                   Option<Integer> skewS,
                   List<AlarmRow> alarms) {
        public HostRow {
            alarms = List.copyOf(alarms);
        }
    }

    /// One current alarm; the figures that do not belong to its kind are absent.
    record AlarmRow(String kind,
                    Option<String> ticket,
                    Option<Integer> ageMin,
                    Option<Integer> days,
                    Option<Integer> skewS) {}

    /// Requires `v`, `hosts`, and per host `host`, `status` and `alarms`. An alarm without `kind` reads as kind
    /// `unknown`. Everything else reads as absent when missing or of another type.
    static HostsRead decode(String body) {
        // FER: a body that is not JSON degrades to TEMPER-SCHEMA(body); the field name is the whole diagnosis the source
        // state carries.
        return GateJson.MAPPER.readTree(body)
                              .option()
                              .filter(HostsRead::isPresent)
                              .map(HostsRead::read)
                              .or(new SchemaBroken("body"));
    }

    private static boolean isPresent(JsonNode root) {
        return ! root.isMissingNode();
    }

    private static HostsRead read(JsonNode root) {
        if (!root.path("v").isIntegralNumber()) {
            return new SchemaBroken("v");
        }

        var hosts = root.path("hosts");

        if (!hosts.isArray()) {
            return new SchemaBroken("hosts");
        }

        return elements(hosts).flatMap(host -> missing(host).stream())
                       .findFirst()
                       .<HostsRead> map(SchemaBroken::new)
                       .orElseGet(() -> new Answered(elements(hosts).map(HostsRead::hostRow).toList()));
    }

    /// The first required field of a host (or of its alarms) that is not there, in the order the decoder requires them.
    private static Option<String> missing(JsonNode host) {
        if (text(host, "host").isEmpty()) {
            return some("host");
        }

        if (text(host, "status").isEmpty()) {
            return some("status");
        }

        if (!host.path("alarms").isArray()) {
            return some("alarms");
        }

        return none();
    }

    private static HostRow hostRow(JsonNode host) {
        return new HostRow(text(host, "host").or(""),
                           text(host, "status").or(""),
                           instant(host, "takenAt"),
                           instant(host, "retireAt"),
                           integer(host, "skewS"),
                           elements(host.path("alarms")).map(HostsRead::alarm).toList());
    }

    private static AlarmRow alarm(JsonNode alarm) {
        return new AlarmRow(text(alarm, "kind").or("unknown"),
                            text(alarm, "ticket"),
                            integer(alarm, "ageMin"),
                            integer(alarm, "days"),
                            integer(alarm, "skewS"));
    }

    private static Stream<JsonNode> elements(JsonNode array) {
        return IntStream.range(0,
                               array.size())
                        .mapToObj(array::get);
    }

    private static Option<String> text(JsonNode node, String field) {
        return some(node.path(field)).filter(JsonNode::isString)
                   .map(JsonNode::stringValue)
                   .filter(Verify.Is::notBlank);
    }

    private static Option<Integer> integer(JsonNode node, String field) {
        return some(node.path(field)).filter(JsonNode::isIntegralNumber)
                   .filter(JsonNode::canConvertToInt)
                   .map(JsonNode::intValue);
    }

    /// An ISO instant, or absent when missing, null, not text or not parseable: a time that cannot be read is not shown.
    private static Option<Instant> instant(JsonNode node, String field) {
        return text(node, field).flatMap(HostsRead::parsed);
    }

    private static Option<Instant> parsed(String text) {
        return DateTime.parseInstant(text).option();
    }
}
