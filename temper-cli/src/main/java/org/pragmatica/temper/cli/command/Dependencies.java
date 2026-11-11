package org.pragmatica.temper.cli.command;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.cli.transport.FleetLine;

import static org.pragmatica.lang.Option.option;


/// The heartbeat sources an `events --until` wait depends on: when one is stale, a match
/// could not arrive through it, and the wait ends with exit 4 instead of waiting for nothing.
///
/// - A host kind (`host-unreachable`, `host-answering`) depends on `hostlink:<h>`, `h` the `--match` value of
///   `host` or `subject`; without one, on every `hostlink:*` source the heartbeat lists.
/// - A kind the controller itself emits (staleness, gaps, the self-check, the epoch) depends on no source: the
///   controller emits it from its own state.
/// - Every wait, whatever its kinds, depends on `selfcheck:highwater` first: it vouches
///   for the stream itself (a pending restore rotation, or a high-water file that cannot be written, means seqs may have
///   been handed out twice), so no match read from it can be trusted while it is stale. It is named first: it is the
///   stream-wide reason. `--follow` does not exit on it; its reader gets the `source-stale` line.
/// - A T2 detector kind (`ungated-merge`, `merged`, `queue-advanced`, `go-invalidated`) depends on `kiln`, `kiln:github`,
///   `kiln:highwater` and `t2:detector`; `verdict-registered` is the
///   controller's own.
/// - A ticket kind (`ticket-queued`, `ticket-started`, `ticket-finished`) depends on `tickets:<h>`,
///   `h` the `--match host=<h>` value; without one, on every `tickets:*` source the heartbeat lists. The subject of a
///   ticket event is `<host>/<id>`, so only the `host` key names the host. The collection alarms (`queue-wait`,
///   `portgroup-held`, `portgroup-long`, `waiter-gone`, `clock-skew`) are evaluated in the same collection
///   round and depend on `tickets:<h>` alike; `host-retiring` is the controller's own, from its config and clock.
/// - Every other kind comes from kiln, which reads GitHub: it depends on `kiln` (the pull), `kiln:github`, and
///   `kiln:highwater` (kiln's own feed integrity, the same property one level down; lead ruling 11-03). A kind
///   this CLI does not know (one WP2 adds) counts as a kiln kind: fail closed.
///
/// A named source the heartbeat does not list counts as stale (fail closed: nothing vouches for it).
record Dependencies(List<String> named, boolean everyHostLink, boolean everyTickets) {
    Dependencies(List<String> named, boolean everyHostLink) {
        this(named, everyHostLink, false);
    }

    static final Set<String> HOST_KINDS = Set.of("host-unreachable", "host-answering");

    static final Set<String> CONTROLLER_KINDS = Set.of("source-stale",
                                                       "source-recovered",
                                                       "source-reset",
                                                       "gap",
                                                       "self-check-failed",
                                                       "self-check-ok",
                                                       "epoch-changed",
                                                       "verdict-registered",
                                                       "host-retiring");

    /// The alarms of a ticket collection round: they arrive through `tickets:<h>`.
    static final Set<String> COLLECTION_ALARM_KINDS = Set.of("queue-wait",
                                                             "portgroup-held",
                                                             "portgroup-long",
                                                             "waiter-gone",
                                                             "clock-skew");

    static final List<String> KILN_SOURCES = List.of("kiln", "kiln:github", "kiln:highwater");
    /// The stream's own integrity source: every wait depends on it.
    static final String STREAM = "selfcheck:highwater";

    /// The T2 kinds the detector emits: they arrive through kiln's pull and the detector.
    static final Set<String> DETECTOR_KINDS = Set.of("ungated-merge", "merged", "queue-advanced", "go-invalidated");

    static final List<String> DETECTOR_SOURCES = List.of("kiln", "kiln:github", "kiln:highwater", "t2:detector");

    static final String HOST_LINK = "hostlink:";
    static final String TICKET_KIND = "ticket-";
    static final String TICKETS = "tickets:";
    static final String HOST = "host";
    static final Set<String> HOST_KEYS = Set.of("host", EventMatch.SUBJECT);

    static Dependencies dependencies(List<String> kinds, List<EventMatch> match) {
        var host = hostOf(match);
        var ticketHost = ticketHostOf(match);

        return new Dependencies(Stream.concat(Stream.of(STREAM),
                                              kinds.stream()
                                                   .flatMap(kind -> sourcesOf(kind, host, ticketHost))
                                                   .collect(Collectors.toCollection(TreeSet::new))
                                                   .stream())
                                      .toList(),
                                host.isEmpty() && kinds.stream().anyMatch(HOST_KINDS::contains),
                                ticketHost.isEmpty() && kinds.stream().anyMatch(Dependencies::isTicketKind));
    }

    private static boolean isTicketKind(String kind) {
        return kind.startsWith(TICKET_KIND) || COLLECTION_ALARM_KINDS.contains(kind);
    }

    private static Option<String> ticketHostOf(List<EventMatch> match) {
        return Option.from(match.stream().filter(pair -> HOST.equals(pair.key())).map(EventMatch::value).findFirst());
    }

    private static Option<String> hostOf(List<EventMatch> match) {
        return Option.from(match.stream()
                                .filter(pair -> HOST_KEYS.contains(pair.key()))
                                .map(EventMatch::value)
                                .findFirst());
    }

    private static Stream<String> sourcesOf(String kind, Option<String> host, Option<String> ticketHost) {
        return HOST_KINDS.contains(kind)
               ? host.map(name -> HOST_LINK + name)
                     .stream()
               : isTicketKind(kind)
                 ? ticketHost.map(name -> TICKETS + name)
                             .stream()
                 : CONTROLLER_KINDS.contains(kind)
                   ? Stream.empty()
                   : DETECTOR_KINDS.contains(kind)
                     ? DETECTOR_SOURCES.stream()
                     : KILN_SOURCES.stream();
    }

    /// The first source this wait depends on that `beat` shows stale (or does not list).
    Option<String> staleIn(FleetLine.Heartbeat beat) {
        return Option.from(Stream.of(named.stream().filter(name -> isStale(beat, name)),
                                     staleSources(beat, everyHostLink, HOST_LINK),
                                     staleSources(beat, everyTickets, TICKETS))
                                 .flatMap(sources -> sources)
                                 .findFirst());
    }

    private static boolean isStale(FleetLine.Heartbeat beat, String name) {
        return option(beat.stale().get(name)).or(true);
    }

    private static Stream<String> staleSources(FleetLine.Heartbeat beat, boolean every, String prefix) {
        return every
               ? beat.stale()
                     .entrySet()
                     .stream()
                     .filter(source -> source.getKey()
                                             .startsWith(prefix) && source.getValue())
                     .map(Map.Entry::getKey)
                     .sorted()
               : Stream.empty();
    }
}
