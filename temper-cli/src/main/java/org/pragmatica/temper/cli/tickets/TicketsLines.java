package org.pragmatica.temper.cli.tickets;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// The exact stdout of `temper tickets`: single spaces, reply order, `|` between the columns.
/// Owner liveness (computed by the controller) and the process fact are separate columns; the CLI prints the
/// liveness text as it is given and never computes it. A ticket with an agent never prints a bare liveness: the
/// session's liveness is not the agent's (R2-13), so ` agent=<name>` follows it.
sealed interface TicketsLines {
    String NONE = "-";
    String NEVER = "never";
    int ERROR_LIMIT = 120;
    int FIRST_PRINTABLE = 0x20;
    int LAST_PRINTABLE = 0x7e;

    static String render(TicketsWire.Reply reply) {
        return Stream.concat(Stream.of(mac(reply.mac())),
                             reply.hosts().stream().flatMap(TicketsLines::host))
                     .collect(Collectors.joining("\n"));
    }

    static String mac(TicketsWire.Mac mac) {
        var posted = mac.postedAt().or(NEVER);

        return mac.stale()
               ? "mac STALE-SOURCE last-post=" + posted
               : "mac ok last-post=" + posted + " sessions=" + mac.sessions();
    }

    private static Stream<String> host(TicketsWire.Host host) {
        return switch (host.status()) {
            case TicketsWire.OK -> Stream.concat(Stream.of(collected(host)), Stream.concat(tickets(host), done(host)));
            case TicketsWire.UNREACHABLE -> Stream.of(unreachable(host));
            case TicketsWire.RETIRED -> Stream.of("host " + host.host() + " RETIRED (past retireAt, not read)");
            default -> Stream.of("host " + host.host() + " NOT-COLLECTED (no link)");
        };
    }

    private static String collected(TicketsWire.Host host) {
        return "host " + host.host()
             + " ok taken=" + host.takenAt()
                                  .or(NONE)
             + " running=" + host.count(TicketsWire.RUNNING)
             + " waiting=" + host.count(TicketsWire.WAITING)
             + " done=" + host.done()
                              .size() + host.queue()
                                            .filter("absent"::equals)
                                            .map(_ -> " queue=absent")
                                            .or("");
    }

    private static String unreachable(TicketsWire.Host host) {
        return "host " + host.host()
             + " UNREACHABLE since " + host.since()
                                           .or(NEVER) + host.error()
                                                            .map(TicketsLines::cleaned)
                                                            .filter(text -> !text.isEmpty())
                                                            .map(text -> " (" + text + ")")
                                                            .or("");
    }

    /// Cut to 120 characters, each character outside 0x20-0x7e shown as `?`.
    static String cleaned(String error) {
        return error.codePoints()
                    .limit(ERROR_LIMIT)
                    .map(point -> point >= FIRST_PRINTABLE && point <= LAST_PRINTABLE
                                  ? point
                                  : '?')
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
    }

    /// Running, then waiting.
    private static Stream<String> tickets(TicketsWire.Host host) {
        return Stream.of(TicketsWire.RUNNING, TicketsWire.WAITING)
                     .flatMap(dir -> host.tickets()
                                         .stream()
                                         .filter(ticket -> dir.equals(ticket.dir())))
                     .map(TicketsLines::ticket);
    }

    private static String ticket(TicketsWire.Ticket ticket) {
        return "  " + ticket.dir()
             + " " + ticket.id()
             + " | " + ticket.liveness() + ticket.agent()
                                                 .map(agent -> " agent=" + agent)
                                                 .or("")
             + " | " + ticket.process()
             + " | owner=" + ticket.owner()
                                   .or(NONE)
             + " co=" + basename(ticket.co())
             + " lane=" + ticket.lane()
                                .or(NONE)
             + " pr=" + ticket.pr()
                              .map(String::valueOf)
                              .or(NONE)
             + " output=" + ticket.output()
                                  .or(NONE);
    }

    private static Stream<String> done(TicketsWire.Host host) {
        return host.done()
                   .stream()
                   .map(TicketsLines::done);
    }

    private static String done(TicketsWire.Done done) {
        return "  done " + done.id()
             + " | " + done.state()
                           .or(NONE)
             + " rc=" + done.rc()
                            .or(NONE)
             + " ended=" + done.endedAt()
                               .or(NONE)
             + " | owner=" + done.owner()
                                 .or(NONE)
             + " agent=" + done.agent()
                               .or(NONE)
             + " co=" + basename(done.co())
             + " output=" + done.output()
                                .or(NONE);
    }

    /// The last path component of `co`; `-` for none.
    private static String basename(Option<String> co) {
        return co.map(TicketsLines::lastComponent)
                 .filter(name -> !name.isEmpty())
                 .or(NONE);
    }

    private static String lastComponent(String path) {
        var trimmed = path.endsWith("/")
                      ? path.substring(0, path.length() - 1)
                      : path;

        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    record unused() implements TicketsLines {}
}
