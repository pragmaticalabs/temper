package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.api.FleetAlarmsView;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbCollect;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbTicket;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// The `fleet/alarms` reply, pure. A `bb_alarm` mark is listed only while its condition
/// still holds on the host's latest snapshot: `queue-wait` while the ticket waits, `portgroup-held` while it runs with
/// `group=1`, `waiter-gone` while it is running or waiting with its waiter gone, `clock-skew` while the mark exists,
/// `host-retiring` while its subject is the configured `retireAt` and `now < retireAt`; a host past its `retireAt` lists
/// none of its collected marks (#103: it is no longer collected). A ticket is listed once, at its
/// highest fired threshold; `host-retiring` once, at its tightest. Order: `clock-skew`, then `queue-wait`,
/// `portgroup-held`, `waiter-gone` by ticket id, then `host-retiring`.
public final class FleetAlarms {
    private static final long NANOS = 1_000_000_000L;

    private static final List<String> TICKET_KINDS = List.of(BbAlarm.QUEUE_WAIT,
                                                             BbAlarm.PORTGROUP_HELD,
                                                             BbAlarm.WAITER_GONE);

    private FleetAlarms() {}

    public static FleetAlarmsView view(Instant now,
                                       List<TicketRoutes.Expected> hosts,
                                       List<BbSnapshot> snapshots,
                                       List<BbCollect> collects,
                                       List<BbAlarm> rows) {
        return FleetAlarmsView.fleetAlarmsView(seconds(now),
                                               hosts.stream()
                                                    .map(host -> host(now, host, snapshots, collects, rows))
                                                    .toList());
    }

    private static FleetAlarmsView.HostAlarms host(Instant now,
                                                   TicketRoutes.Expected host,
                                                   List<BbSnapshot> snapshots,
                                                   List<BbCollect> collects,
                                                   List<BbAlarm> rows) {
        var mine = rows.stream().filter(row -> row.host()
                                                  .equals(host.host())).toList();
        var snapshot = host.via().equals("none")
                       ? Option.<BbSnapshot> none()
                       : find(snapshots, BbSnapshot::host, host.host());
        var status = host.via().equals("none")
                     ? "NOT-COLLECTED"
                     : host.retired(now)
                       ? "RETIRED"
                       : snapshot.isPresent() && find(collects,
                                                      BbCollect::host,
                                                      host.host()).filter(collect -> TicketRoutes.healthy(collect, now))
                                                     .isPresent()
                         ? "ok"
                         : "UNREACHABLE";
        var alarms = Stream.concat(snapshot.filter(_ -> !retired(now, host))
                                           .map(known -> collected(known, mine))
                                           .or(List.of())
                                           .stream(),
                                   retiring(now, host, mine).stream())
                           .toList();

        return FleetAlarmsView.HostAlarms.hostAlarms(host.host(),
                                                     status,
                                                     snapshot.map(BbSnapshot::takenAt).map(FleetAlarms::seconds),
                                                     snapshot.flatMap(BbSnapshot::hostAt),
                                                     snapshot.flatMap(BbSnapshot::skewS),
                                                     host.retireAt(),
                                                     alarms);
    }

    /// The marks the latest snapshot still bears out.
    private static List<FleetAlarmsView.Alarm> collected(BbSnapshot snapshot, List<BbAlarm> rows) {
        var tickets = active(snapshot.tickets());
        var skew = rows.stream()
                       .filter(row -> row.kind()
                                         .equals(BbAlarm.CLOCK_SKEW))
                       .findFirst()
                       .map(row -> FleetAlarmsView.Alarm.alarm(row.kind(),
                                                               none(),
                                                               none(),
                                                               none(),
                                                               none(),
                                                               snapshot.skewS(),
                                                               seconds(row.firedAt())))
                       .stream();
        var perTicket = TICKET_KINDS.stream().flatMap(kind -> highest(rows, kind).values()
                                                                     .stream()
                                                                     .flatMap(row -> option(tickets.get(row.subject())).filter(ticket -> holds(kind,
                                                                                                                                               ticket))
                                                                                           .map(ticket -> ticketAlarm(snapshot,
                                                                                                                      row,
                                                                                                                      ticket))
                                                                                           .stream()));

        return Stream.concat(skew, perTicket).toList();
    }

    /// The mark of the highest threshold of each ticket of `kind`, by ticket id.
    private static Map<String, BbAlarm> highest(List<BbAlarm> rows, String kind) {
        return rows.stream()
                   .filter(row -> row.kind()
                                     .equals(kind))
                   .collect(Collectors.toMap(BbAlarm::subject,
                                             row -> row,
                                             (left, right) -> threshold(right) > threshold(left)
                                                              ? right
                                                              : left,
                                             TreeMap::new));
    }

    private static boolean holds(String kind, BbTicket ticket) {
        return switch (kind) {
            case BbAlarm.QUEUE_WAIT -> ticket.dir().equals("waiting");
            case BbAlarm.PORTGROUP_HELD -> ticket.dir().equals("running") && ticket.field("group").filter("1"::equals).isPresent();
            default -> ticket.waiterState().filter("gone"::equals).isPresent();
        };
    }

    private static FleetAlarmsView.Alarm ticketAlarm(BbSnapshot snapshot, BbAlarm row, BbTicket ticket) {
        var since = switch (row.kind()) {
            case BbAlarm.QUEUE_WAIT -> TicketAlarms.number(ticket, "created").map(created -> created / NANOS);
            case BbAlarm.PORTGROUP_HELD -> TicketAlarms.number(ticket, "started");
            default -> Option.<Long> none();
        };
        var age = since.flatMap(start -> snapshot.hostAt()
                                                 .map(at -> (int)(Math.max(0,
                                                                           at.getEpochSecond() - start) / 60)));
        var limit = row.kind().equals(BbAlarm.WAITER_GONE)
                    ? Option.<Integer> none()
                    : some(threshold(row));

        return FleetAlarmsView.Alarm.alarm(row.kind(),
                                           some(row.subject()),
                                           age,
                                           limit,
                                           none(),
                                           none(),
                                           seconds(row.firedAt()));
    }

    /// A retired host is no longer collected (#103), so its last snapshot never changes again: the marks it bears out
    /// are not current, and none is listed.
    private static boolean retired(Instant now, TicketRoutes.Expected host) {
        return host.retireAt()
                   .filter(at -> !now.isBefore(at))
                   .isPresent();
    }

    /// The tightest fired threshold of the configured `retireAt`, while it is ahead.
    private static List<FleetAlarmsView.Alarm> retiring(Instant now, TicketRoutes.Expected host, List<BbAlarm> rows) {
        return host.retireAt()
                   .filter(now::isBefore)
                   .map(retireAt -> rows.stream()
                                        .filter(row -> row.kind()
                                                          .equals(BbAlarm.HOST_RETIRING) && row.subject()
                                                                                               .equals(retireAt.toString()))
                                        .min(Comparator.comparingInt(FleetAlarms::threshold))
                                        .map(row -> FleetAlarmsView.Alarm.alarm(row.kind(),
                                                                                none(),
                                                                                none(),
                                                                                none(),
                                                                                some(threshold(row)),
                                                                                none(),
                                                                                seconds(row.firedAt())))
                                        .stream()
                                        .toList())
                   .or(List.of());
    }

    private static int threshold(BbAlarm row) {
        return row.threshold()
                  .matches("^[0-9]{1,9}$")
               ? Integer.parseInt(row.threshold())
               : 0;
    }

    private static Map<String, BbTicket> active(List<BbTicket> tickets) {
        var map = new TreeMap<String, BbTicket>();

        tickets.stream()
               .filter(ticket -> ticket.dir()
                                       .equals("running") || ticket.dir()
                                                                   .equals("waiting"))
               .forEach(ticket -> map.putIfAbsent(ticket.ticket(),
                                                  ticket));

        return map;
    }

    private static <T> Option<T> find(List<T> rows, Function<T, String> name, String wanted) {
        return rows.stream()
                   .filter(row -> name.apply(row)
                                      .equals(wanted))
                   .findFirst()
                   .map(row -> some(row))
                   .orElseGet(Option::none);
    }

    private static Instant seconds(Instant at) {
        return at.truncatedTo(ChronoUnit.SECONDS);
    }
}
