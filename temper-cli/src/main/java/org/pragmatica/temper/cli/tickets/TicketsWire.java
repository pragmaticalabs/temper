package org.pragmatica.temper.cli.tickets;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// `GET fleet/tickets` as the CLI reads it, field for field. Nullable fields are [Option]s; the
/// two ticket lists the controller may omit read as empty. The mapper rejects a field this record does not name, so a
/// controller of another shape is an unreadable answer (exit 69), not a half-printed one.
public sealed interface TicketsWire {
    int VERSION = 1;
    String OK = "ok";
    String UNREACHABLE = "UNREACHABLE";
    String NOT_COLLECTED = "NOT-COLLECTED";
    String RETIRED = "RETIRED";
    String RUNNING = "running";
    String WAITING = "waiting";
    Set<String> STATUSES = Set.of(OK, UNREACHABLE, NOT_COLLECTED, RETIRED);
    Set<String> DIRS = Set.of(RUNNING, WAITING);
    Set<String> PROCESSES = Set.of("waiter-alive", "process-orphan");

    record Mac(Option<String> lastOkAt, boolean stale, Option<String> postedAt, int sessions) {}

    record Ticket(String id,
                  String dir,
                  Option<String> owner,
                  String liveness,
                  Option<String> agent,
                  String process,
                  Option<String> lane,
                  Option<Long> pr,
                  Option<String> co,
                  Option<String> output,
                  Option<String> sessionLabel,
                  Option<String> createdAt,
                  Option<String> args) {}

    record Done(String id,
                Option<String> state,
                Option<String> rc,
                Option<String> endedAt,
                Option<String> owner,
                Option<String> agent,
                Option<String> co,
                Option<String> output) {}

    record Host(String host,
                String via,
                String status,
                Option<String> since,
                Option<String> error,
                Option<String> takenAt,
                Option<String> queue,
                List<Ticket> tickets,
                List<Done> done) {
        public Host {
            tickets = option(tickets).map(List::copyOf).or(List.of());
            done = option(done).map(List::copyOf).or(List.of());
        }

        boolean isComplete() {
            return option(host).isPresent()
                   && option(status).isPresent()
                   && tickets.stream()
                             .allMatch(ticket -> option(ticket.id()).isPresent() && option(ticket.liveness()).isPresent())
                   && done.stream()
                          .allMatch(row -> option(row.id()).isPresent());
        }

        boolean isUnreachable() {
            return UNREACHABLE.equals(status);
        }

        long count(String dir) {
            return tickets.stream()
                          .filter(ticket -> dir.equals(ticket.dir()))
                          .count();
        }
    }

    record Reply(int v, String asOf, Mac mac, List<Host> hosts) {
        public Reply {
            hosts = option(hosts).map(List::copyOf).or(List.of());
        }

        /// The members every row prints: the Mac source, and per host its name and status, per ticket its id and liveness.
        boolean isComplete() {
            return option(mac).isPresent() && hosts.stream()
                                                   .allMatch(Host::isComplete);
        }

        boolean isStale() {
            return mac.stale() || hosts.stream()
                                       .anyMatch(Host::isUnreachable);
        }
    }

    record unused() implements TicketsWire {}
}
