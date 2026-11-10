package org.pragmatica.temper.controller.fleet;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.host.HostLink;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.host.LinkSignal;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.protocol.api.HeartbeatLine;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.MacStore;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.SourceSeen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.node.JsonNodeFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The fleet stream's producers and their schedule (design §2.2):
///
/// - at start ([#settle]): temper's epoch ([TemperEpoch]), and a `fleet_source` row for every expected producer
///   (`kiln` when configured, `selfcheck`, `selfcheck:highwater`, `mac` (T3's Mac post, period 60 s), `hostlink:<h>` per host), so one never seen reads
///   stale instead of absent;
/// - every 5 s, the kiln pull ([KilnPlan]), when `[fleet] kilnUrl` is set;
/// - every 60 s, [#minute]: the self-check (L0) and its transitions, `hostlink:<h>` success for every link UP, the
///   high-water check ([#highWater]: a restore under the running controller rotates the epoch; a good write is
///   `selfcheck:highwater`'s success), the staleness transitions of every source, the heartbeat tick, and 30-day
///   retention;
/// - on every link transition ([#signal]): `host-answering` (and `hostlink:<h>` success) or `host-unreachable`.
///
/// A failed run of a scheduled step is logged and the next run tries again (FER): the staleness of the step's own
/// source (`kiln`, `selfcheck`) is what announces a step that keeps failing.
public final class Fleet {
    private static final Logger log = LoggerFactory.getLogger(Fleet.class);
    public static final String SELFCHECK = "selfcheck";
    /// The high-water file's own source: its last good write.
    public static final String HIGHWATER = "selfcheck:highwater";
    public static final int MINUTE_S = 60;
    public static final Duration RETAIN = Duration.ofDays(30);
    private static final TimeSpan PULL_EVERY = TimeSpan.timeSpan(KilnPlan.PULL_PERIOD_S).seconds();
    private static final TimeSpan MINUTE = TimeSpan.timeSpan(MINUTE_S).seconds();

    private final List<HostName> hosts;
    private final FleetStore store;
    private final FleetWriter writer;
    private final Heartbeats heartbeats;
    private final Wake wake;
    private final ProcessRunner runner;
    private final Option<KilnClient> kiln;
    private final Supplier<Instant> clock;

    private final AtomicReference<List<ScheduledFuture<?>>> scheduled = new AtomicReference<>(List.of());

    private final AtomicBoolean pulling = new AtomicBoolean();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final AtomicBoolean pullFailing = new AtomicBoolean();
    /// Whether [#start] has completed: the fleet routes serve only then.
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<Option<SelfCheck>> selfCheck = new AtomicReference<>(none());
    /// Per host, the last transition's write (see [#serialized]).
    private final ConcurrentHashMap<HostName, Promise<Unit>> linkWrites = new ConcurrentHashMap<>();

    private Fleet(ControllerConfig.Fleet config,
                  List<HostName> hosts,
                  FleetStore store,
                  ProcessRunner runner,
                  Option<KilnClient> kiln,
                  Supplier<Instant> clock) {
        this.hosts = List.copyOf(hosts);
        this.store = store;
        this.wake = Wake.wake();
        this.writer = FleetWriter.fleetWriter(store,
                                              HighWater.highWater(config.stateDir()),
                                              wake,
                                              clock);
        this.heartbeats = Heartbeats.heartbeats(store, clock, writer::rotationPending);
        this.runner = runner;
        this.kiln = kiln;
        this.clock = clock;
    }

    public static Fleet fleet(ControllerConfig.Fleet config,
                              List<HostName> hosts,
                              FleetStore store,
                              ProcessRunner runner,
                              JsonMapper mapper,
                              Supplier<Instant> clock) {
        return new Fleet(config,
                         hosts,
                         store,
                         runner,
                         config.kilnUrl().map(url -> KilnClient.http(url, mapper, Duration.ofSeconds(5))),
                         clock);
    }

    /// For tests: the kiln client given.
    public static Fleet fleet(ControllerConfig.Fleet config,
                              List<HostName> hosts,
                              FleetStore store,
                              ProcessRunner runner,
                              Option<KilnClient> kiln,
                              Supplier<Instant> clock) {
        return new Fleet(config, hosts, store, runner, kiln, clock);
    }

    public FleetWriter writer() {
        return writer;
    }

    /// `readTokenId`: the configured read token's id, which a subscriber may be bound to (`bindTo: read`). Until
    /// [#start] has completed, every route answers 503 `NOT_READY`.
    public List<Route> routes(Responses responses, JsonMapper mapper, Option<String> readTokenId) {
        return routes(responses, mapper, readTokenId, started::get);
    }

    /// Whether [#start] has completed; the T2 routes are gated on it as the fleet routes are.
    public BooleanSupplier ready() {
        return started::get;
    }

    /// For tests: the routes served when `ready` says so.
    public List<Route> routes(Responses responses,
                              JsonMapper mapper,
                              Option<String> readTokenId,
                              BooleanSupplier ready) {
        return FleetRoutes.fleetRoutes(responses, mapper, store, heartbeats, wake, clock, readTokenId, ready).routes();
    }

    /// The links' transitions as `host-answering` / `host-unreachable`.
    public LinkSignal signal() {
        return LinkSignal.linkSignal(this::answering, this::unreachable);
    }

    /// The epoch, the expected source rows, and the first high-water check, before the API serves.
    public Promise<String> settle() {
        return TemperEpoch.settle(writer,
                                  clock.get())
                          .flatMap(epoch -> store.expect(expected())
                                                 .flatMap(_ -> highWater())
                                                 .map(_ -> epoch));
    }

    /// The running high-water check ([TemperEpoch#verify]); a written file is `selfcheck:highwater`'s success.
    public Promise<Unit> highWater() {
        var now = clock.get();

        return writer.verified()
                     .flatMap(written -> store.seen(highWaterSeen(written, now)));
    }

    private static List<SourceSeen> highWaterSeen(boolean written, Instant now) {
        return written
               ? List.of(SourceSeen.sourceSeen(HIGHWATER, some(now), MINUTE_S))
               : List.of();
    }

    /// The first self-check, once, before the schedule starts (no optimistic initial ok). `port` is the API's
    /// own (the loopback probe), so the server must already listen; the fleet routes answer 503 `NOT_READY` until this
    /// completes (FIX-6), so no reader ever sees the heartbeat's [SelfCheck#NOT_RUN] (ok=false, nothing failed). The
    /// probes never fail ([SelfCheck#run]), so the heartbeat holds a real verdict once this completes.
    ///
    /// FER on a failed write of its result: logged; the check's verdict is in the heartbeat already, and the minute
    /// writes it again.
    public Promise<Unit> start(int port) {
        var check = SelfCheck.selfCheck(runner, port);

        selfCheck.set(some(check));

        return check.run()
                    .flatMap(result -> checked(result,
                                               clock.get()))
                    .recover(cause -> failedStep("first self-check", cause))
                    .withResult(_ -> started.set(true));
    }

    /// Starts the schedule (after [#start]).
    public Unit schedule(HostLinks links) {
        var minute = SharedScheduler.scheduleAtFixedRate(() -> singleFlight(ticking, () -> minute(links)), MINUTE);
        var pull = kiln.map(client -> SharedScheduler.scheduleAtFixedRate(() -> singleFlight(pulling, () -> pull(client)),
                                                                          PULL_EVERY));

        scheduled.set(Stream.concat(Stream.of(minute), pull.stream()).toList());

        return unit();
    }

    public Unit stop() {
        scheduled.getAndSet(List.of()).forEach(future -> future.cancel(false));

        return unit();
    }

    private List<SourceSeen> expected() {
        return Stream.of(kiln.map(_ -> SourceSeen.sourceSeen(KilnPlan.KILN,
                                                             none(),
                                                             KilnPlan.PULL_PERIOD_S)).stream(),
                         Stream.of(SourceSeen.sourceSeen(SELFCHECK,
                                                         none(),
                                                         MINUTE_S),
                                   SourceSeen.sourceSeen(HIGHWATER,
                                                         none(),
                                                         MINUTE_S)),
                         Stream.of(SourceSeen.sourceSeen(MacStore.SOURCE,
                                                         none(),
                                                         MacStore.PERIOD_S)),
                         hosts.stream().map(host -> SourceSeen.sourceSeen(hostSource(host),
                                                                          none(),
                                                                          MINUTE_S)))
                     .flatMap(source -> source)
                     .toList();
    }

    static String hostSource(HostName host) {
        return "hostlink:" + host.value();
    }

    /// At most one run of a step at a time: a run still going when the next is due skips that one.
    private static Unit singleFlight(AtomicBoolean running, Supplier<Promise<Unit>> step) {
        return running.compareAndSet(false, true)
               ? released(running, step)
               : unit();
    }

    private static Unit released(AtomicBoolean running, Supplier<Promise<Unit>> step) {
        step.get().onResult(_ -> running.set(false));

        return unit();
    }

    // ---- kiln pull -------------------------------------------------------------------------------------------
    /// One pull: the stored kiln state, kiln's reply to it, the plan, written in one transaction.
    public Promise<Unit> pull(KilnClient client) {
        return store.source(KilnPlan.KILN)
                    .map(row -> row.or(() -> FleetSourceRow.unseen(KilnPlan.KILN, KilnPlan.PULL_PERIOD_S)))
                    .flatMap(state -> client.changes(state.epoch(),
                                                     state.cursor())
                                            .map(reply -> KilnPlan.plan(state,
                                                                        reply,
                                                                        clock.get())))
                    .flatMap(writer::pulled)
                    .withResult(result -> pullOutcome(result.isSuccess(),
                                                      result.fold(Cause::message, _ -> "")))
                    .mapToUnit();
    }

    /// One WARN when the pull starts failing and one INFO when it recovers, not one per 5 s.
    private Unit pullOutcome(boolean ok, String failure) {
        var wasFailing = pullFailing.getAndSet(!ok);

        return ok == wasFailing
               ? logged(ok, failure)
               : unit();
    }

    private static Unit logged(boolean ok, String failure) {
        return ok
               ? info("fleet: kiln pull recovered")
               : warn("fleet: kiln pull failing: " + failure);
    }

    // ---- minute ----------------------------------------------------------------------------------------------
    /// Each step is attempted whatever the previous one did (a failed staleness write never skips the heartbeat tick,
    /// which would show the controller dead for that minute).
    public Promise<Unit> minute(HostLinks links) {
        return minute(() -> upHosts(links));
    }

    /// For tests: the minute with the hosts whose link is UP given (a live UP link needs a worker session).
    public Promise<Unit> minute(Supplier<List<HostName>> up) {
        var now = clock.get();

        return attempt("self-check",
                       () -> selfChecked(now)).flatMap(_ -> attempt("links",
                                                                    () -> store.seen(linksUp(up.get(),
                                                                                             now))))
                      .flatMap(_ -> attempt("high-water", this::highWater))
                      .flatMap(_ -> attempt("staleness",
                                            () -> staleness(now)))
                      .flatMap(_ -> attempt("tick",
                                            () -> retained(now)));
    }

    /// FER: a failed step is logged and the minute goes on; the next minute tries it again, and the staleness of the
    /// step's own source announces one that keeps failing.
    private static Promise<Unit> attempt(String step, Supplier<Promise<Unit>> run) {
        return run.get()
                  .recover(cause -> failedStep(step, cause));
    }

    private static Unit failedStep(String step, Cause cause) {
        log.warn("fleet: {} step failed: {}", step, cause.message());

        return unit();
    }

    private Promise<Unit> selfChecked(Instant now) {
        return selfCheck.get()
                        .map(check -> check.run()
                                           .flatMap(result -> checked(result, now)))
                        .or(Promise::unitPromise);
    }

    /// The result replaces the heartbeat's; a change of `ok` is announced; a passed check is `selfcheck`'s success.
    public Promise<Unit> checked(HeartbeatLine.SelfCheck check, Instant now) {
        var previous = heartbeats.selfCheck().getAndSet(check);

        return announcedCheck(previous, check, now).flatMap(_ -> store.seen(check.ok()
                                                                            ? List.of(SourceSeen.sourceSeen(SELFCHECK,
                                                                                                            some(now),
                                                                                                            MINUTE_S))
                                                                            : List.of()));
    }

    /// The first result after [SelfCheck#NOT_RUN] is always announced; later ones only when `ok` changes.
    private Promise<Unit> announcedCheck(HeartbeatLine.SelfCheck previous, HeartbeatLine.SelfCheck check, Instant now) {
        return previous != SelfCheck.NOT_RUN && previous.ok() == check.ok()
               ? Promise.unitPromise()
               : writer.append(List.of(NewFleetEvent.newFleetEvent(now,
                                                                   "controller",
                                                                   check.ok()
                                                                   ? "self-check-ok"
                                                                   : "self-check-failed",
                                                                   SELFCHECK,
                                                                   checkPayload(check))))
                       .mapToUnit();
    }

    private static String checkPayload(HeartbeatLine.SelfCheck check) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("reason",
                    String.join(",", check.failed()));
        check.advisory().load1().onPresent(load -> payload.put("load1", load));
        payload.put("cores",
                    check.advisory().cores());

        return payload.toString();
    }

    private static List<HostName> upHosts(HostLinks links) {
        return links.all()
                    .stream()
                    .filter(HostLink::isUp)
                    .map(HostLink::name)
                    .toList();
    }

    /// `hostlink:<h>` success for every host whose link is UP: a link that stays UP never ages into staleness.
    private static List<SourceSeen> linksUp(List<HostName> up, Instant now) {
        return up.stream()
                 .map(host -> SourceSeen.sourceSeen(hostSource(host),
                                                    some(now),
                                                    MINUTE_S))
                 .toList();
    }

    /// Each source whose computed staleness differs from the one last announced: one event, once (the store's
    /// conditional update decides, in the event's transaction).
    public Promise<Unit> staleness(Instant now) {
        return store.sources()
                    .flatMap(rows -> rows.stream()
                                         .filter(row -> stale(row, now) != row.stale())
                                         .reduce(Promise.unitPromise(),
                                                 (done, row) -> done.flatMap(_ -> transition(row, now)),
                                                 (left, _) -> left));
    }

    private boolean stale(FleetSourceRow row, Instant now) {
        return Staleness.stale(row, now, writer.rotationPending());
    }

    private Promise<Unit> transition(FleetSourceRow row, Instant now) {
        var stale = stale(row, now);
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("lastOkAt",
                    row.lastOkAt().map(Instant::toString).or(""));
        payload.put("periodS", row.periodS());

        return writer.staleness(row.name(),
                                stale,
                                row.lastOkAt(),
                                NewFleetEvent.newFleetEvent(now,
                                                            "controller",
                                                            stale
                                                            ? "source-stale"
                                                            : "source-recovered",
                                                            row.name(),
                                                            payload.toString()))
                     .mapToUnit();
    }

    private Promise<Unit> retained(Instant now) {
        return store.tick(now.truncatedTo(ChronoUnit.MINUTES),
                          now.minus(RETAIN))
                    .flatMap(_ -> store.prune(now.minus(RETAIN)));
    }

    // ---- host links ------------------------------------------------------------------------------------------
    /// Per host, each transition's write starts only after the previous one finished: the events commit in
    /// transition order, so the host's last event is its current state. The link's thread does not wait.
    ///
    /// FER on a failed write: logged, and the next transition still runs (the minute's `hostlink:<h>` refresh and
    /// staleness still report the link).
    private Unit serialized(HostName host, String kind, Supplier<Promise<Unit>> write) {
        linkWrites.compute(host, (_, previous) -> chained(option(previous), kind, host, write));

        return unit();
    }

    private static Promise<Unit> chained(Option<Promise<Unit>> previous,
                                         String kind,
                                         HostName host,
                                         Supplier<Promise<Unit>> write) {
        return previous.or(Promise::unitPromise)
                       .fold(_ -> write.get()
                                       .recover(cause -> failedStep(kind + " " + host.value(),
                                                                    cause)));
    }

    private Unit answering(HostName host) {
        return serialized(host, "host-answering", () -> answered(host, clock.get()));
    }

    private Promise<Unit> answered(HostName host, Instant now) {
        return store.seen(List.of(SourceSeen.sourceSeen(hostSource(host),
                                                        some(now),
                                                        MINUTE_S)))
                    .flatMap(_ -> writer.append(List.of(hostEvent(now, "host-answering", host))))
                    .mapToUnit();
    }

    private Unit unreachable(HostName host) {
        return serialized(host,
                          "host-unreachable",
                          () -> writer.append(List.of(hostEvent(clock.get(),
                                                                "host-unreachable",
                                                                host)))
                                      .mapToUnit());
    }

    private static NewFleetEvent hostEvent(Instant now, String kind, HostName host) {
        return NewFleetEvent.newFleetEvent(now, "hostlink", kind, host.value(), "{\"host\":\"" + host.value() + "\"}");
    }

    private static Unit info(String text) {
        log.info(text);

        return unit();
    }

    private static Unit warn(String text) {
        log.warn(text);

        return unit();
    }
}
