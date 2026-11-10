package org.pragmatica.temper.controller.t3;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.fleet.FleetRoutes;
import org.pragmatica.temper.controller.fleet.Staleness;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.store.api.BbCollect;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbStore;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.MacPost;
import org.pragmatica.temper.store.api.MacSession;
import org.pragmatica.temper.store.api.MacStore;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.temper.controller.http.Route.readRoute;


/// `GET /api/v1/fleet/tickets?host=<host>&done=<0..200>` (READ, READS), behind the fleet stream's
/// `NOT_READY` gate. Every expected host appears exactly once, in configuration order, as ONE row: `ok` with its
/// tickets (running, then waiting, in `bb-tickets` order, each with its [Liveness]) and its newest `done` done tickets;
/// `UNREACHABLE` (the last attempt failed, or the last success is older than 90 s) with no tickets, never stale ones; or
/// `NOT-COLLECTED` for a `bbHosts` entry without a link. `host` that is not expected is 404 `NO_HOST`, a malformed `done`
/// is 400.
public record TicketRoutes(Responses responses,
                           FleetStore fleet,
                           MacStore mac,
                           BbStore bb,
                           List<Expected> hosts,
                           Supplier<Instant> clock,
                           BooleanSupplier ready) {
    static final int DEFAULT_DONE = 10;
    static final int MAX_DONE = 200;
    /// The last success counts for 3 collection periods.
    static final Duration OK_WINDOW = Duration.ofSeconds(3L * BbStore.PERIOD_S);
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,3}$");
    private static final Pattern NUMBER = Pattern.compile("^([0-9]{1,18}|[0-8][0-9]{18})$");
    /// A ticket's `pr` is a positive number of at most ten digits, without leading zeros.
    private static final Pattern PR = Pattern.compile("^[1-9][0-9]{0,9}$");
    private static final long NANOS = 1_000_000_000L;

    /// An expected host and how it is read: `hostlink`, `bbscan` or `none` (not collected); `retireAt` is the
    /// `[[hosts]]` entry's (none for a `bbHosts` entry).
    public record Expected(String host, String via, Option<Instant> retireAt) {
        /// Past its `retireAt` at `now` (the instant itself counts, as everywhere): the collector no longer reads it.
        public boolean retired(Instant now) {
            return retireAt.filter(at -> !now.isBefore(at))
                           .isPresent();
        }

        public static Expected expected(String host, String via) {
            return new Expected(host, via, none());
        }

        public static Expected expected(String host, String via, Option<Instant> retireAt) {
            return new Expected(host, via, retireAt);
        }
    }

    record Query(Option<String> host, int done) {}

    /// Everything one reply is made of, read once.
    private record Inputs(Instant now,
                          Option<FleetSourceRow> macSource,
                          Option<MacPost> latest,
                          List<MacSession> sessions,
                          List<BbSnapshot> snapshots,
                          List<BbCollect> collects) {}

    record MacView(Option<String> lastOkAt, boolean stale, Option<String> postedAt, int sessions) {}

    record TicketView(String id,
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

    record DoneView(String id,
                    Option<String> state,
                    Option<String> rc,
                    Option<String> endedAt,
                    Option<String> owner,
                    Option<String> agent,
                    Option<String> co,
                    Option<String> output) {}

    record HostView(String host,
                    String via,
                    String status,
                    Option<String> since,
                    Option<String> error,
                    Option<String> takenAt,
                    Option<String> queue,
                    List<TicketView> tickets,
                    List<DoneView> done) {}

    record Reply(int v, String asOf, MacView mac, List<HostView> hosts) {}

    public static TicketRoutes ticketRoutes(Responses responses,
                                            FleetStore fleet,
                                            MacStore mac,
                                            BbStore bb,
                                            List<Expected> hosts,
                                            Supplier<Instant> clock,
                                            BooleanSupplier ready) {
        return new TicketRoutes(responses, fleet, mac, bb, List.copyOf(hosts), clock, ready);
    }

    public List<Route> routes() {
        return List.of(readRoute("fleet/tickets", FleetRoutes.gated(responses, ready, this::tickets)),
                       readRoute("fleet/alarms", FleetRoutes.gated(responses, ready, this::alarms)));
    }

    /// `GET /api/v1/fleet/alarms` (READ): every expected host in the `fleet/tickets` order
    /// and status rule, with its CURRENT alarms ([FleetAlarms]).
    private Promise<ApiResponse> alarms(ApiRequest request) {
        return Promise.all(bb.snapshots(),
                           bb.collects(),
                           bb.alarms())
                      .map((snapshots, collects, rows) -> responses.ok(FleetAlarms.view(clock.get(),
                                                                                        hosts,
                                                                                        snapshots,
                                                                                        collects,
                                                                                        rows)))
                      .recover(responses::failure);
    }

    private Promise<ApiResponse> tickets(ApiRequest request) {
        return query(request).async()
                    .flatMap(query -> inputs().map(inputs -> responses.ok(reply(query, inputs))))
                    .recover(responses::failure);
    }

    private Promise<Inputs> inputs() {
        return Promise.all(fleet.sources(),
                           mac.latest(),
                           mac.sessions(),
                           bb.snapshots(),
                           bb.collects())
                      .map((sources, latest, sessions, snapshots, collects) -> new Inputs(clock.get(),
                                                                                          find(sources,
                                                                                               FleetSourceRow::name,
                                                                                               MacStore.SOURCE),
                                                                                          latest,
                                                                                          sessions,
                                                                                          snapshots,
                                                                                          collects));
    }

    // ---- query ----------------------------------------------------------------------------------------------
    private Result<Query> query(ApiRequest request) {
        return done(request).flatMap(done -> host(request).map(host -> new Query(host, done)));
    }

    private static Result<Integer> done(ApiRequest request) {
        return request.queryParam("done")
                      .map(text -> DIGITS.matcher(text).matches() && Integer.parseInt(text) <= MAX_DONE
                                   ? success(Integer.parseInt(text))
                                   : Result.<Integer> failure(ApiError.BadRequest.badRequest(cause("invalid done: '" + text
                                                                                                  + "' is not 0.." + MAX_DONE))))
                      .or(success(DEFAULT_DONE));
    }

    private Result<Option<String>> host(ApiRequest request) {
        return request.queryParam("host")
                      .map(name -> hosts.stream()
                                        .anyMatch(expected -> expected.host()
                                                                      .equals(name))
                                   ? success(some(name))
                                   : ApiError.General.NO_HOST.<Option<String>> result())
                      .or(success(none()));
    }

    // ---- reply ----------------------------------------------------------------------------------------------
    private Reply reply(Query query, Inputs in) {
        var lastOk = in.macSource().flatMap(FleetSourceRow::lastOkAt);
        var stale = Staleness.stale(lastOk, MacStore.PERIOD_S, in.now());
        var latest = in.latest().map(MacPost::processes).or(List.of());
        var state = new Liveness.MacState(!stale, lastOk, latest, in.sessions());

        return new Reply(1,
                         iso(in.now()),
                         new MacView(lastOk.map(TicketRoutes::iso),
                                     stale,
                                     in.latest().map(post -> iso(post.postedAt())),
                                     latest.size()),
                         hosts.stream()
                              .filter(host -> query.host()
                                                   .map(host.host()::equals)
                                                   .or(true))
                              .map(host -> view(host, query, in, state))
                              .toList());
    }

    private HostView view(Expected host, Query query, Inputs in, Liveness.MacState state) {
        return host.via()
                   .equals("none")
               ? new HostView(host.host(),
                              host.via(),
                              "NOT-COLLECTED",
                              none(),
                              none(),
                              none(),
                              none(),
                              List.of(),
                              List.of())
               : host.retired(in.now())
                 ? retiredView(host)
                 : collected(host, query, in, state);
    }

    /// A retired host is not read any more, so its last row ages out; it answers `RETIRED`, never `UNREACHABLE` (#107),
    /// and with no tickets (a stale list would pass for current).
    private static HostView retiredView(Expected host) {
        return new HostView(host.host(), host.via(), "RETIRED", none(), none(), none(), none(), List.of(), List.of());
    }

    private HostView collected(Expected host, Query query, Inputs in, Liveness.MacState state) {
        var collect = find(in.collects(), BbCollect::host, host.host());

        return find(in.snapshots(),
                    BbSnapshot::host,
                    host.host()).filter(_ -> collect.filter(row -> healthy(row,
                                                                           in.now()))
                                                    .isPresent())
                   .map(snapshot -> ok(host, snapshot, query, state))
                   .or(() -> unreachable(host, collect));
    }

    /// The last attempt succeeded and the success is at most 3 periods old.
    static boolean healthy(BbCollect collect, Instant now) {
        return collect.failingSince()
                      .isEmpty() && collect.lastOkAt()
                                           .filter(at -> Duration.between(at, now).compareTo(OK_WINDOW) <= 0)
                                           .isPresent();
    }

    private static HostView ok(Expected host, BbSnapshot snapshot, Query query, Liveness.MacState state) {
        return new HostView(host.host(),
                            host.via(),
                            "ok",
                            none(),
                            none(),
                            some(iso(snapshot.takenAt())),
                            snapshot.queue(),
                            Stream.concat(dir(snapshot, "running"),
                                          dir(snapshot, "waiting"))
                                  .map(ticket -> ticketView(ticket, state))
                                  .toList(),
                            dir(snapshot, "done").limit(query.done()).map(TicketRoutes::doneView).toList());
    }

    private static HostView unreachable(Expected host, Option<BbCollect> collect) {
        return new HostView(host.host(),
                            host.via(),
                            "UNREACHABLE",
                            collect.flatMap(BbCollect::failingSince)
                                   .orElse(() -> collect.flatMap(BbCollect::lastOkAt))
                                   .map(TicketRoutes::iso),
                            collect.flatMap(BbCollect::lastError),
                            none(),
                            none(),
                            List.of(),
                            List.of());
    }

    private static Stream<BbTicket> dir(BbSnapshot snapshot, String dir) {
        return snapshot.tickets()
                       .stream()
                       .filter(ticket -> ticket.dir()
                                               .equals(dir));
    }

    private static TicketView ticketView(BbTicket ticket, Liveness.MacState state) {
        return new TicketView(ticket.ticket(),
                              ticket.dir(),
                              ticket.field("owner"),
                              Liveness.liveness(ticket, state),
                              ticket.field("agent"),
                              ticket.waiterState().filter("alive"::equals).isPresent()
                              ? "waiter-alive"
                              : "process-orphan",
                              ticket.field("lane"),
                              number(ticket, "pr", PR),
                              ticket.field("co"),
                              ticket.field("output"),
                              ticket.field("session_label"),
                              number(ticket, "created").map(nanos -> iso(Instant.ofEpochSecond(nanos / NANOS))),
                              ticket.field("args"));
    }

    private static DoneView doneView(BbTicket ticket) {
        return new DoneView(ticket.ticket(),
                            ticket.field("state"),
                            ticket.field("rc"),
                            number(ticket, "ended").map(seconds -> iso(Instant.ofEpochSecond(seconds))),
                            ticket.field("owner"),
                            ticket.field("agent"),
                            ticket.field("co"),
                            ticket.field("output"));
    }

    private static Option<Long> number(BbTicket ticket, String key) {
        return number(ticket, key, NUMBER);
    }

    private static Option<Long> number(BbTicket ticket, String key, Pattern pattern) {
        return ticket.field(key)
                     .filter(text -> pattern.matcher(text)
                                            .matches())
                     .map(Long::parseLong);
    }

    private static <T> Option<T> find(List<T> rows, Fn1<String, T> name, String wanted) {
        return rows.stream()
                   .filter(row -> name.apply(row)
                                      .equals(wanted))
                   .findFirst()
                   .map(row -> some(row))
                   .orElseGet(Option::none);
    }

    private static String iso(Instant at) {
        return at.truncatedTo(ChronoUnit.SECONDS)
                 .toString();
    }
}
