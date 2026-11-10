package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.store.api.BbTicket;

import tools.jackson.databind.JsonNode;


/// The completeness proof of a `bb-tickets` reply. Success = every line parses as a ticket
/// line (`v` 1, a ticket id, `dir`, `waiter`, string `fields`, string `dropped`) and the LAST line is the trailer
/// (`end` true, `count` = the ticket lines, `queue`, `at`). Anything else is a failed collection, never a partial
/// snapshot: a line that does not parse is `malformed line <n>` (the first such), a reply without a trailer or with a
/// wrong `count` is `truncated`. A queue root that does not exist (`queue` absent, zero lines) is a success with no tickets.
public final class TicketParse {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final List<String> DIRS = List.of("running", "waiting", "done");

    /// One line as read: a ticket, or the trailer with its `count`.
    private sealed interface Line {
        record Ticket(BbTicket ticket) implements Line {}

        record Trailer(int count, String queue, long at) implements Line {}
    }

    /// A proven reply: the tickets, the trailer's `queue` (`present` | `absent`) and its `at` (epoch seconds, host clock).
    public record Reply(List<BbTicket> tickets, String queue, Instant at) {}

    private TicketParse() {}

    public static Result<Reply> parse(JsonMapper mapper, String stdout) {
        return lines(mapper, stdout).flatMap(lines -> proven(lines));
    }

    /// All lines, or the FIRST one that does not parse (allOf alone would report every bad line).
    private static Result<List<Line>> lines(JsonMapper mapper, String stdout) {
        var rows = stdout.lines().toList();
        var results = IntStream.range(0,
                                      rows.size())
                               .mapToObj(number -> line(mapper,
                                                        rows.get(number)).mapError(_ -> TicketError.Malformed.malformed(number + 1)))
                               .toList();

        return results.stream()
                      .flatMap(result -> result.fold(Stream::of,
                                                     _ -> Stream.<Cause> empty()))
                      .findFirst()
                      .map(cause -> cause.<List<Line>> result())
                      .orElseGet(() -> Result.allOf(results));
    }

    /// Complete = the last line is the trailer, no other line is, and `count` matches.
    private static Result<Reply> proven(List<Line> lines) {
        var tickets = lines.stream()
                           .flatMap(line -> line instanceof Line.Ticket ticket
                                            ? Stream.of(ticket.ticket())
                                            : Stream.<BbTicket> empty())
                           .toList();
        var trailers = lines.size() - tickets.size();

        return lines.isEmpty() || !(lines.getLast() instanceof Line.Trailer(var count, var queue, var at))
               ? TicketError.Truncated.TRUNCATED.result()
               : trailers != 1
                 ? TicketError.Malformed.malformed(lines.size()).result()
                 : count == tickets.size()
                   ? Result.success(new Reply(tickets, queue, Instant.ofEpochSecond(at)))
                   : TicketError.Truncated.TRUNCATED.result();
    }

    private static Result<Line> line(JsonMapper mapper, String text) {
        return mapper.readTree(text)
                     .filter(TicketError.Malformed.malformed(0),
                             JsonNode::isObject)
                     .flatMap(TicketParse::line);
    }

    private static Result<Line> line(JsonNode node) {
        return node.path("v")
                   .isIntegralNumber() && node.path("v")
                                              .intValue() == 1
               ? node.has("end")
                 ? trailer(node)
                 : ticket(node)
               : TicketError.Malformed.malformed(0).result();
    }

    private static Result<Line> trailer(JsonNode node) {
        var count = node.path("count");
        var queue = node.path("queue");

        return node.path("end")
                   .isBoolean() && node.path("end")
                                       .booleanValue() && count.isIntegralNumber() && count.canConvertToInt() && count.intValue() >= 0 && queue.isString() && !(count.intValue() > 0 && "absent".equals(queue.stringValue())) && List.of("present",
                                                                                                                                                                                                                                         "absent").contains(queue.stringValue()) && node.path("at")
                                                                                                                                                                                                                                                                                        .isIntegralNumber() && node.path("at")
                                                                                                                                                                                                                                                                                                                   .canConvertToLong() && inInstantRange(node.path("at")
                                                                                                                                                                                                                                                                                                                                                             .longValue())
               ? Result.success(new Line.Trailer(count.intValue(),
                                                 queue.stringValue(),
                                                 node.path("at").longValue()))
               : TicketError.Malformed.malformed(0).result();
    }

    /// An `at` beyond what an [Instant] holds is not a time.
    private static boolean inInstantRange(long at) {
        return at >= Instant.MIN.getEpochSecond() && at <= Instant.MAX.getEpochSecond();
    }

    private static Result<Line> ticket(JsonNode node) {
        var id = node.path("ticket");
        var dir = node.path("dir");
        var waiter = node.path("waiter");

        return id.isString() && ID.matcher(id.stringValue()).matches() && dir.isString() && DIRS.contains(dir.stringValue()) && (waiter.isNull() || waiter.isString() && List.of("alive",
                                                                                                                                                                                 "gone").contains(waiter.stringValue()))
               ? strings(node.path("fields")).flatMap(fields -> keys(node.path("dropped")).map(dropped -> new Line.Ticket(BbTicket.bbTicket(1,
                                                                                                                                            id.stringValue(),
                                                                                                                                            dir.stringValue(),
                                                                                                                                            waiter.isString()
                                                                                                                                            ? Option.some(waiter.stringValue())
                                                                                                                                            : Option.none(),
                                                                                                                                            fields,
                                                                                                                                            dropped))))
               : TicketError.Malformed.malformed(0).result();
    }

    private static Result<Map<String, String>> strings(JsonNode node) {
        return node.isObject() && node.properties()
                                      .stream()
                                      .allMatch(entry -> entry.getValue()
                                                              .isString())
               ? Result.success(node.properties()
                                    .stream()
                                    .collect(LinkedHashMap::new,
                                             (map, entry) -> map.put(entry.getKey(),
                                                                     entry.getValue().stringValue()),
                                             Map::putAll))
               : TicketError.Malformed.malformed(0).result();
    }

    private static Result<List<String>> keys(JsonNode node) {
        var items = StreamSupport.stream(node.spliterator(), false).toList();

        return node.isArray() && items.stream()
                                      .allMatch(JsonNode::isString)
               ? Result.success(items.stream().map(JsonNode::stringValue).toList())
               : TicketError.Malformed.malformed(0).result();
    }
}
