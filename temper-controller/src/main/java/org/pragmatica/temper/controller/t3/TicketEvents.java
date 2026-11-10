package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.NewFleetEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// The ticket diff: the `ticket-queued`, `ticket-started` and `ticket-finished` events a collection makes of the
/// baseline and the new ticket list. Pure but for one INFO line: no baseline means no event at all (`baseline <host>`
/// is logged), an unchanged queue means none, and the transitions follow the contract's table, per ticket id in id
/// order. The baseline is the one the collection's transaction read from Postgres, so a restart keeps it.
public final class TicketEvents {
    private static final Logger log = LoggerFactory.getLogger(TicketEvents.class);
    private static final String SOURCE = "controller";
    private static final String ABSENT = "absent";
    /// A ticket's `pr` is a positive number of at most ten digits, without leading zeros.
    private static final Pattern PR = Pattern.compile("^[1-9][0-9]{0,9}$");
    private static final long NANOS = 1_000_000_000L;
    private static final String GONE = "gone";
    private static final Pattern NUMBER = Pattern.compile("^([0-9]{1,18}|[0-8][0-9]{18})$");

    private TicketEvents() {}

    public static TicketDiff ticketEvents() {
        return TicketEvents::events;
    }

    static List<NewFleetEvent> events(String host, Instant now, List<BbTicket> tickets, Option<BbSnapshot> baseline) {
        return baseline.map(snapshot -> changes(new Round(host,
                                                          now,
                                                          snapshot.takenAt()),
                                                snapshot.tickets(),
                                                tickets))
                       .or(() -> firstBaseline(host));
    }

    /// One round's constants: the host, the time of the events and the baseline's `taken_at`.
    private record Round(String host, Instant now, Instant baselineAt) {}

    private static List<NewFleetEvent> firstBaseline(String host) {
        log.info("baseline {}", host);

        return List.of();
    }

    private static List<NewFleetEvent> changes(Round round, List<BbTicket> before, List<BbTicket> after) {
        var previous = byId(before);
        var next = byId(after);
        var ids = new TreeSet<>(previous.keySet());

        ids.addAll(next.keySet());

        return ids.stream()
                  .flatMap(id -> transition(round,
                                            option(previous.get(id)),
                                            option(next.get(id))).stream())
                  .toList();
    }

    /// The first line of a ticket id wins, should a listing hold one id twice.
    private static Map<String, BbTicket> byId(List<BbTicket> tickets) {
        var map = new LinkedHashMap<String, BbTicket>();

        tickets.forEach(ticket -> map.putIfAbsent(ticket.ticket(), ticket));

        return map;
    }

    private static List<NewFleetEvent> transition(Round round, Option<BbTicket> previous, Option<BbTicket> next) {
        var change = previous.map(BbTicket::dir).or(ABSENT) + ">" + next.map(BbTicket::dir).or(ABSENT);

        return switch (change) {
            case "absent>waiting" -> queued(round, next);
            case "absent>running" -> concat(queued(round, next), started(round, next));
            case "absent>done" -> next.filter(ticket -> newerThanBaseline(round, ticket)).isPresent()
                                  ? concat(queued(round, next), startedIfSet(round, next), finished(round, next, none()))
                                  : List.of();
            case "waiting>running" -> started(round, next);
            case "waiting>done" -> concat(startedIfSet(round, next), finished(round, next, none()));
            case "running>done" -> finished(round, next, none());
            case "waiting>absent", "running>absent" -> finished(round, previous, some(GONE));
            default -> List.of();
        };
    }

    @SafeVarargs
    private static List<NewFleetEvent> concat(List<NewFleetEvent>... parts) {
        return Stream.of(parts)
                     .flatMap(List::stream)
                     .toList();
    }

    /// Strictly newer: `created` equal to `taken_at` is not. Both are exact epoch nanoseconds: `taken_at` is an
    /// `Instant` read from a timestamptz, so its microsecond precision is carried as it is, never rounded.
    private static boolean newerThanBaseline(Round round, BbTicket ticket) {
        var baselineNanos = round.baselineAt().getEpochSecond() * NANOS + round.baselineAt().getNano();

        return number(ticket, "created").map(created -> created > baselineNanos)
                     .or(false);
    }

    private static List<NewFleetEvent> queued(Round round, Option<BbTicket> ticket) {
        return event(round, ticket, "ticket-queued", none());
    }

    private static List<NewFleetEvent> started(Round round, Option<BbTicket> ticket) {
        return event(round, ticket, "ticket-started", none());
    }

    private static List<NewFleetEvent> startedIfSet(Round round, Option<BbTicket> ticket) {
        return ticket.filter(t -> t.field("started")
                                   .isPresent())
                     .isPresent()
               ? started(round, ticket)
               : List.of();
    }

    /// `state` is the done file's own, or `gone` when the ticket left the listing; `rc` only when the file has one.
    private static List<NewFleetEvent> finished(Round round, Option<BbTicket> ticket, Option<String> state) {
        return event(round,
                     ticket,
                     "ticket-finished",
                     state.orElse(() -> ticket.flatMap(t -> t.field("state"))));
    }

    private static List<NewFleetEvent> event(Round round, Option<BbTicket> ticket, String kind, Option<String> state) {
        return ticket.map(t -> List.of(NewFleetEvent.newFleetEvent(round.now(),
                                                                   SOURCE,
                                                                   kind,
                                                                   round.host() + "/" + t.ticket(),
                                                                   payload(round.host(),
                                                                           t,
                                                                           kind,
                                                                           state).toString())))
                     .or(List.of());
    }

    /// The keys in the contract's order; a key whose field is absent is omitted.
    private static ObjectNode payload(String host, BbTicket ticket, String kind, Option<String> state) {
        var node = JsonNodeFactory.instance.objectNode();

        node.put("host", host);
        node.put("ticket", ticket.ticket());
        text(node, "owner", ticket.field("owner"));
        text(node, "agent", ticket.field("agent"));
        text(node, "lane", ticket.field("lane"));
        number(ticket, "pr", PR).onPresent(pr -> node.put("pr", pr));
        text(node, "sessionLabel", ticket.field("session_label"));
        text(node, "co", ticket.field("co"));
        text(node, "output", ticket.field("output"));
        if (kind.equals("ticket-finished")) {
            text(node, "state", state);
            text(node,
                 "rc",
                 state.filter(GONE::equals).isPresent()
                 ? Option.<String> none()
                 : ticket.field("rc"));
        }

        return node;
    }

    private static void text(ObjectNode node, String key, Option<String> value) {
        value.onPresent(text -> node.put(key, text));
    }

    private static Option<Long> number(BbTicket ticket, String key) {
        return number(ticket, key, NUMBER);
    }

    private static Option<Long> number(BbTicket ticket, String key, Pattern pattern) {
        return ticket.field(key)
                     .filter(value -> pattern.matcher(value)
                                             .matches())
                     .map(Long::parseLong);
    }
}
