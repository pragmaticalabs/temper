package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbOffQueue;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbStore.Listing;
import org.pragmatica.temper.store.api.BbStore.RoundWrite;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.NewFleetEvent;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.pragmatica.lang.Option.option;


/// The alarms one successful collection makes: `clock-skew`,
/// `queue-wait`, `portgroup-held`, `portgroup-long`, `waiter-gone`, `off-queue-reactor` and `lingering-reactor`, from the new listing, the baseline and the host's `bb_alarm` rows.
/// Pure: it runs inside the collection's transaction on the rows that transaction read, so an event is made only for a
/// key that is absent, and its mark is written with it.
///
/// Ages are on the host's clock, from one listing (`age = at − t_ticket`, a negative age counts as 0), so the skew
/// between host and controller never enters an age. Each ticket fires once per threshold; a ticket first seen above
/// several due thresholds fires the highest of them only, and a mark is written for each.
///
/// `portgroup-held` is a stall alarm, not an age: it fires once per stall, when a running `group=1` ticket's progress
/// time (the host listing's `progressAt`, else `started`: an old host script reports no progress, so its ticket is
/// judged by the stall clock from its start) is at least the stall limit behind the listing's `at`; its mark is
/// cleared as soon as the ticket progresses, so the next stall fires again. `portgroup-long` is the age backstop
/// (`at − started` at the age limit, once), whatever the progress.
///
/// `off-queue-reactor` fires once per launcher the host's listing names as not started by the queue (`offq`), keyed by
/// `pid:startS` so a reused pid is a new launcher; `lingering-reactor` once when that launcher's age (`at − startS`) reaches
/// [#LINGERING_REACTOR_MIN]. Both marks are cleared when the launcher is no longer listed. A listing whose `offq` was not
/// read (an older `bb-tickets`, or `bb-queue offq` failed or timed out that round) never reaches here as "none": the store
/// carries the baseline's list forward, so only a successful read that lacks the launcher clears its marks.
public final class TicketAlarms {
    /// `queue-wait` fires again here, when above the configured limit.
    static final int QUEUE_WAIT_SECOND_MIN = 180;
    /// `clock-skew` fires above this many seconds...
    static final int SKEW_BOUND_S = 30;
    /// ...and re-arms at or below this many (G-S1-4).
    static final int SKEW_REARM_S = 20;
    /// `lingering-reactor` fires when an off-queue maven launcher has run this many minutes. The one site of the figure. 30 is a guess,
    /// anchored on the queue's own tickets over 7 days (run_s p99 about 46 min, 1.6% above 30 min): the incident that asked for the
    /// alarm blocked a host for 45 min; an off-queue launcher is already off-contract, so a legitimately long ticket cannot trip it.
    static final int LINGERING_REACTOR_MIN = 30;
    /// `waiter-gone` needs two listings at least this far apart on the host clock (G-S1-3).
    static final long GRACE_S = 30;
    /// The threshold of an `off-queue-reactor` mark: it has no limit.
    private static final String OFF_QUEUE_THRESHOLD = "0";
    private static final String SOURCE = "controller";
    private static final String GONE = "gone";
    /// The host listing's per-ticket progress time (epoch seconds), added to running tickets by `bb-tickets`.
    private static final String PROGRESS_AT = "progressAt";
    private static final String RUNNING = "running";
    private static final String WAITING = "waiting";
    private static final long NANOS = 1_000_000_000L;
    /// All digits, within a long.
    private static final Pattern NUMBER = Pattern.compile("^([0-9]{1,18}|[0-8][0-9]{18})$");
    /// A ticket's `pr` is a positive number of at most ten digits, without leading zeros.
    private static final Pattern PR = Pattern.compile("^[1-9][0-9]{0,9}$");

    private TicketAlarms() {}

    /// The configured limits, in minutes (`[fleet] queueWaitAlarmMin`, `portGroupStallAlarmMin`,
    /// `portGroupHeldAgeAlarmMin`).
    public record Limits(int queueWaitMin, int portGroupStallMin, int portGroupAgeMin) {
        public static final Limits DEFAULT = new Limits(60, 30, 360);

        public static Limits limits(int queueWaitMin, int portGroupStallMin, int portGroupAgeMin) {
            return new Limits(queueWaitMin, portGroupStallMin, portGroupAgeMin);
        }

        /// The first limit, then the fixed second one when it is above the first.
        List<Integer> queueWait() {
            return thresholds(queueWaitMin, QUEUE_WAIT_SECOND_MIN);
        }

        private static List<Integer> thresholds(int first, int second) {
            return second > first
                   ? List.of(first, second)
                   : List.of(first);
        }
    }

    /// What one successful round writes besides the snapshot, made of the listing, the baseline and the host's rows.
    @FunctionalInterface
    public interface Round {
        RoundWrite write(String host, Instant now, Listing listing, Option<BbSnapshot> baseline, List<BbAlarm> rows);
    }

    /// The ticket diff alone: no alarm is evaluated and no mark changes.
    public static Round diffOnly(TicketDiff diff) {
        return (host, now, listing, baseline, _) -> RoundWrite.eventsOnly(diff.events(host,
                                                                                      now,
                                                                                      listing.tickets(),
                                                                                      baseline));
    }

    /// The ticket events first (unchanged), then the alarm events.
    public static Round round(TicketDiff diff, Limits limits) {
        return (host, now, listing, baseline, rows) -> {
            var alarms = alarms(host, now, listing, baseline, rows, limits);

            return RoundWrite.roundWrite(Stream.concat(diff.events(host,
                                                                   now,
                                                                   listing.tickets(),
                                                                   baseline).stream(),
                                                       alarms.events().stream())
                                               .toList(),
                                         alarms.fire(),
                                         alarms.clear());
        };
    }

    /// The skew of a listing whose trailer says `at`, read between the controller instants `t0` (before the read) and
    /// `t1` (after the reply): 0 inside `[⌊t0⌋, ⌈t1⌉]`, else the signed distance to the nearer end (positive = host
    /// ahead). Measuring against the interval keeps transport time out of the skew.
    public static int skew(Instant t0, Instant t1, Instant at) {
        var lower = t0.getEpochSecond();
        var upper = t1.getEpochSecond() + (t1.getNano() > 0
                                           ? 1
                                           : 0);
        var seconds = at.getEpochSecond();
        var skew = seconds > upper
                   ? seconds - upper
                   : seconds < lower
                     ? seconds - lower
                     : 0;

        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, skew));
    }

    /// One round's alarms, in the contract's order: `clock-skew`, then `queue-wait`, `portgroup-held`,
    /// `portgroup-long`, `waiter-gone`, each by ticket id.
    public static RoundWrite alarms(String host,
                                    Instant now,
                                    Listing listing,
                                    Option<BbSnapshot> baseline,
                                    List<BbAlarm> rows,
                                    Limits limits) {
        var in = new In(host,
                        now,
                        listing.hostAt().getEpochSecond(),
                        active(listing.tickets()),
                        rows);
        var out = new Out();

        skew(in, listing.skewS(), out);
        in.tickets()
          .values()
          .stream()
          .filter(ticket -> ticket.dir()
                                  .equals(WAITING))
          .forEach(ticket -> queueWait(in,
                                       ticket,
                                       limits.queueWait(),
                                       out));
        in.tickets()
          .values()
          .stream()
          .filter(TicketAlarms::groupHolder)
          .forEach(ticket -> {
                       portGroupHeld(in,
                                     ticket,
                                     limits.portGroupStallMin(),
                                     out);
                       portGroupLong(in,
                                     ticket,
                                     limits.portGroupAgeMin(),
                                     out);
                   });
        baseline.onPresent(snapshot -> in.tickets()
                                         .values()
                                         .forEach(ticket -> waiterGone(in, snapshot, ticket, out)));
        offQueue(in, listing.offq(), out);
        rows.stream()
            .filter(row -> perTicket(row.kind()) && !in.tickets()
                                                       .containsKey(row.subject()))
            .forEach(out.clear::add);

        return RoundWrite.roundWrite(out.events, out.fire, out.clear);
    }

    /// One round's constants: the host, the controller time, the listing's `at` (epoch seconds, host clock), the
    /// running and waiting tickets by id (the first line of an id wins), the host's marks.
    private record In(String host, Instant now, long at, Map<String, BbTicket> tickets, List<BbAlarm> rows) {
        boolean marked(String kind, String subject, String threshold) {
            return rows.stream()
                       .anyMatch(row -> row.kind()
                                           .equals(kind)
                                        && row.subject()
                                              .equals(subject)
                                        && row.threshold()
                                              .equals(threshold));
        }

        BbAlarm mark(String kind, String subject, String threshold) {
            return BbAlarm.bbAlarm(host, kind, subject, threshold, now);
        }

        NewFleetEvent event(String kind, String subject, ObjectNode payload) {
            return NewFleetEvent.newFleetEvent(now, SOURCE, kind, subject, payload.toString());
        }
    }

    private static final class Out {
        private final List<NewFleetEvent> events = new ArrayList<>();
        private final List<BbAlarm> fire = new ArrayList<>();
        private final List<BbAlarm> clear = new ArrayList<>();
    }

    private static Map<String, BbTicket> active(List<BbTicket> tickets) {
        var map = new TreeMap<String, BbTicket>();

        tickets.stream()
               .filter(ticket -> ticket.dir()
                                       .equals(RUNNING) || ticket.dir()
                                                                 .equals(WAITING))
               .forEach(ticket -> map.putIfAbsent(ticket.ticket(),
                                                  ticket));

        return map;
    }

    private static boolean perTicket(String kind) {
        return kind.equals(BbAlarm.QUEUE_WAIT) || kind.equals(BbAlarm.PORTGROUP_HELD) || kind.equals(BbAlarm.PORTGROUP_LONG) || kind.equals(BbAlarm.WAITER_GONE);
    }

    private static boolean reactorKind(String kind) {
        return kind.equals(BbAlarm.OFF_QUEUE_REACTOR) || kind.equals(BbAlarm.LINGERING_REACTOR);
    }

    private static boolean groupHolder(BbTicket ticket) {
        return ticket.dir()
                     .equals(RUNNING) && inGroup(ticket);
    }

    private static boolean inGroup(BbTicket ticket) {
        return ticket.field("group")
                     .filter("1"::equals)
                     .isPresent();
    }

    // ---- clock-skew -------------------------------------------------------------------------------------------
    private static void skew(In in, int skewS, Out out) {
        var marked = in.marked(BbAlarm.CLOCK_SKEW, BbAlarm.NO_TICKET, Integer.toString(SKEW_BOUND_S));

        if (Math.abs((long) skewS) > SKEW_BOUND_S && !marked) {
            var payload = JsonNodeFactory.instance.objectNode();

            payload.put("host", in.host());
            payload.put("skewS", skewS);
            out.events.add(in.event(BbAlarm.CLOCK_SKEW, in.host(), payload));
            out.fire.add(in.mark(BbAlarm.CLOCK_SKEW, BbAlarm.NO_TICKET, Integer.toString(SKEW_BOUND_S)));
        } else if (Math.abs((long) skewS) <= SKEW_REARM_S && marked) {
            out.clear.add(in.mark(BbAlarm.CLOCK_SKEW, BbAlarm.NO_TICKET, Integer.toString(SKEW_BOUND_S)));
        }
    }

    // ---- queue-wait, portgroup-held ---------------------------------------------------------------------------
    /// Aged from `created` (epoch nanoseconds), never from `blocked_since` (the starvation timer, set for a cap skip only).
    private static void queueWait(In in, BbTicket ticket, List<Integer> thresholds, Out out) {
        number(ticket, "created").map(created -> Math.max(0,
                                                          in.at() - created / NANOS))
              .onPresent(age -> aged(in,
                                     ticket,
                                     BbAlarm.QUEUE_WAIT,
                                     age,
                                     thresholds,
                                     payload -> queueWaitKeys(in, ticket, payload),
                                     out));
    }

    /// Fires once when the stall (`at − progress`) reaches the limit; `progress` is the listing's `progressAt`, else
    /// `started`. While the ticket progresses, and for any mark of another limit (an old controller's age marks), the
    /// ticket's marks are cleared, so the next stall fires again.
    private static void portGroupHeld(In in, BbTicket ticket, int limitMin, Out out) {
        var progress = progress(ticket);
        var stall = progress.map(at -> Math.max(0, in.at() - at));
        var stalled = stall.filter(seconds -> seconds >= limitMin * 60L).isPresent();
        var threshold = Integer.toString(limitMin);

        in.rows()
          .stream()
          .filter(row -> row.kind()
                            .equals(BbAlarm.PORTGROUP_HELD) && row.subject()
                                                                  .equals(ticket.ticket()))
          .filter(row -> !stalled || !row.threshold()
                                         .equals(threshold))
          .forEach(out.clear::add);
        if (stalled && !in.marked(BbAlarm.PORTGROUP_HELD, ticket.ticket(), threshold)) {
            var payload = ticketKeys(in.host(), ticket);

            payload.put("stallMin", stall.unwrap() / 60);
            payload.put("limitMin", limitMin);
            progress.filter(_ -> ticket.field(PROGRESS_AT)
                                       .isPresent()).onPresent(at -> payload.put("progressAt", at));
            number(ticket, "started").onPresent(started -> payload.put("ageMin",
                                                                       Math.max(0, in.at() - started) / 60));
            payload.put("groupWaiting", groupWaiting(in));
            out.events.add(in.event(BbAlarm.PORTGROUP_HELD,
                                    in.host() + "/" + ticket.ticket(),
                                    payload));
            out.fire.add(in.mark(BbAlarm.PORTGROUP_HELD, ticket.ticket(), threshold));
        }
    }

    /// The age backstop: `at − started` at the limit fires `portgroup-long` once, whatever the progress.
    private static void portGroupLong(In in, BbTicket ticket, int limitMin, Out out) {
        number(ticket, "started").map(started -> Math.max(0,
                                                          in.at() - started))
              .onPresent(age -> aged(in,
                                     ticket,
                                     BbAlarm.PORTGROUP_LONG,
                                     age,
                                     List.of(limitMin),
                                     payload -> {
                                         payload.put("groupWaiting",
                                                     groupWaiting(in));
                                         number(ticket, PROGRESS_AT).onPresent(at -> payload.put("progressAt", at));
                                     },
                                     out));
    }

    /// The ticket's last progress, epoch seconds on the host clock: `progressAt` when the listing has it, else `started`.
    static Option<Long> progress(BbTicket ticket) {
        var reported = number(ticket, PROGRESS_AT);

        return reported.isPresent()
               ? reported
               : number(ticket, "started");
    }

    /// The due thresholds (`age ≥ limit × 60`, at exactly the limit it fires) without a mark: one event at the highest
    /// of them, and a mark for each.
    private static void aged(In in,
                             BbTicket ticket,
                             String kind,
                             long age,
                             List<Integer> thresholds,
                             Consumer<ObjectNode> extra,
                             Out out) {
        var missing = thresholds.stream()
                                .filter(limit -> age >= limit * 60L)
                                .filter(limit -> !in.marked(kind,
                                                            ticket.ticket(),
                                                            Integer.toString(limit)))
                                .toList();

        if (missing.isEmpty()) {
            return;
        }

        var limit = missing.getLast();
        var payload = ticketKeys(in.host(), ticket);

        payload.put("ageMin", age / 60);
        payload.put("limitMin", limit);
        extra.accept(payload);
        out.events.add(in.event(kind,
                                in.host() + "/" + ticket.ticket(),
                                payload));
        missing.forEach(due -> out.fire.add(in.mark(kind, ticket.ticket(), Integer.toString(due))));
    }

    private static void queueWaitKeys(In in, BbTicket ticket, ObjectNode payload) {
        text(payload, "group", ticket.field("group"));
        if (inGroup(ticket)) {
            text(payload,
                 "holder",
                 in.tickets()
                   .values()
                   .stream()
                   .filter(TicketAlarms::groupHolder)
                   .map(BbTicket::ticket)
                   .findFirst()
                   .map(Option::some)
                   .orElseGet(Option::none));
        }

        text(payload,
             "blockedSince",
             number(ticket, "blocked_since").map(seconds -> Instant.ofEpochSecond(seconds).toString()));
    }

    private static int groupWaiting(In in) {
        return (int) in.tickets()
                       .values()
                       .stream()
                       .filter(ticket -> ticket.dir()
                                               .equals(WAITING) && inGroup(ticket))
                       .count();
    }

    // ---- waiter-gone ------------------------------------------------------------------------------------------
    /// Two consecutive successful listings, both carrying `at`, at least [#GRACE_S] apart, both reading `gone`.
    private static void waiterGone(In in, BbSnapshot baseline, BbTicket ticket, Out out) {
        var before = active(baseline.tickets()).get(ticket.ticket());
        var spaced = baseline.hostAt().filter(at -> in.at() - at.getEpochSecond() >= GRACE_S);

        if (gone(ticket) && option(before).filter(TicketAlarms::gone).isPresent() && spaced.isPresent() && !in.marked(BbAlarm.WAITER_GONE,
                                                                                                                      ticket.ticket(),
                                                                                                                      BbAlarm.GRACE)) {
            var payload = ticketKeys(in.host(), ticket);

            payload.put("dir", ticket.dir());
            payload.put("goneSinceAt",
                        spaced.unwrap().toString());
            out.events.add(in.event(BbAlarm.WAITER_GONE,
                                    in.host() + "/" + ticket.ticket(),
                                    payload));
            out.fire.add(in.mark(BbAlarm.WAITER_GONE, ticket.ticket(), BbAlarm.GRACE));
        }
    }

    private static boolean gone(BbTicket ticket) {
        return ticket.waiterState()
                     .filter(GONE::equals)
                     .isPresent();
    }

    // ---- off-queue-reactor, lingering-reactor -----------------------------------------------------------------
    private static void offQueue(In in, List<BbOffQueue> reactors, Out out) {
        reactors.forEach(reactor -> {
            offQueueReactor(in, reactor, out);
            lingeringReactor(in, reactor, out);
        });
        in.rows()
          .stream()
          .filter(row -> reactorKind(row.kind()) && reactors.stream()
                                                            .noneMatch(reactor -> reactor.key()
                                                                                         .equals(row.subject())))
          .forEach(out.clear::add);
    }

    private static void offQueueReactor(In in, BbOffQueue reactor, Out out) {
        if (!in.marked(BbAlarm.OFF_QUEUE_REACTOR, reactor.key(), OFF_QUEUE_THRESHOLD)) {
            out.events.add(in.event(BbAlarm.OFF_QUEUE_REACTOR,
                                    in.host() + "/" + reactor.key(),
                                    reactorKeys(in, reactor)));
            out.fire.add(in.mark(BbAlarm.OFF_QUEUE_REACTOR, reactor.key(), OFF_QUEUE_THRESHOLD));
        }
    }

    private static void lingeringReactor(In in, BbOffQueue reactor, Out out) {
        var threshold = Integer.toString(LINGERING_REACTOR_MIN);

        if (reactorAgeS(in, reactor) >= LINGERING_REACTOR_MIN * 60L && !in.marked(BbAlarm.LINGERING_REACTOR,
                                                                                  reactor.key(),
                                                                                  threshold)) {
            var payload = reactorKeys(in, reactor);

            payload.put("limitMin", LINGERING_REACTOR_MIN);
            out.events.add(in.event(BbAlarm.LINGERING_REACTOR,
                                    in.host() + "/" + reactor.key(),
                                    payload));
            out.fire.add(in.mark(BbAlarm.LINGERING_REACTOR, reactor.key(), threshold));
        }
    }

    /// Age on the host's clock, from one listing; a start after `at` counts as 0.
    private static long reactorAgeS(In in, BbOffQueue reactor) {
        return Math.max(0,
                        in.at() - reactor.startS());
    }

    private static ObjectNode reactorKeys(In in, BbOffQueue reactor) {
        var node = JsonNodeFactory.instance.objectNode();

        node.put("host", in.host());
        node.put("pid", reactor.pid());
        node.put("owner", reactor.owner());
        node.put("cwd", reactor.cwd());
        node.put("ageMin", reactorAgeS(in, reactor) / 60);

        return node;
    }

    // ---- payload ----------------------------------------------------------------------------------------------
    /// The ticket keys in the contract's order; a key whose field is absent is omitted.
    private static ObjectNode ticketKeys(String host, BbTicket ticket) {
        var node = JsonNodeFactory.instance.objectNode();

        node.put("host", host);
        node.put("ticket", ticket.ticket());
        text(node, "owner", ticket.field("owner"));
        text(node, "agent", ticket.field("agent"));
        text(node, "lane", ticket.field("lane"));
        number(ticket, "pr", PR).onPresent(pr -> node.put("pr", pr));
        text(node, "sessionLabel", ticket.field("session_label"));
        text(node, "co", ticket.field("co"));

        return node;
    }

    private static void text(ObjectNode node, String key, Option<String> value) {
        value.onPresent(text -> node.put(key, text));
    }

    static Option<Long> number(BbTicket ticket, String key) {
        return number(ticket, key, NUMBER);
    }

    private static Option<Long> number(BbTicket ticket, String key, Pattern pattern) {
        return ticket.field(key)
                     .filter(value -> pattern.matcher(value)
                                             .matches())
                     .map(Long::parseLong);
    }
}
