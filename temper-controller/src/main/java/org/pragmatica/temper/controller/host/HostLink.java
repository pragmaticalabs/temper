package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.flow.CauseChain;
import org.pragmatica.temper.controller.flow.Serial;
import org.pragmatica.temper.controller.policy.ReconcileAction;
import org.pragmatica.temper.controller.result.JobDecision;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.LostSettled;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.StoredAttempt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.Option.option;


/// One host's worker link (m0-design §6.4), a state machine `DOWN → CONNECTING → HANDSHAKING → UP → DOWN`
/// ([LinkState]):
///
/// 1. **Connect:** spawn `ssh … <user>@<address> run <ver>` (deploying the jar first when the previous session
///    ended before `Hello`, or answered `Incompatible`).
/// 2. **Handshake:** the first frame must be `Hello` within `timing.hello`. The protocol version
///    ([Handshake#PROTOCOL_VERSION]) and worker version = controller
///    version → `Welcome`; otherwise `Incompatible`, mark the next connect to deploy, close.
/// 3. **Reconcile** ([ReconcileRun]) against the store; only then UP: grants are enabled and the scheduler is woken.
/// 4. **Down:** EOF, a frame error, the ssh exit, `timing.silence` without a frame while HANDSHAKING or UP, or a
///    reset. Pending requests fail; the host is marked DOWN; reconnect after the backoff
///    (`1 s · 2^(attempt-1)`, max 60 s, jitter). The attempt counter restarts after `timing.stableUp` of UP.
///
/// Lease expiry while DOWN changes nothing in the store (the units show `leaseExpired`, never re-granted).
///
/// **LOST** (m4-design §2.1, §3; rulings R-M4-1, R-M4-16) is a flag held next to the link state, because the link keeps
/// reconnecting while the host is LOST. Every liveness tick moves the down clock ([HostLiveness#clock]) and decides
/// ([HostLiveness#decide]): down for `env.hostLost()`, or a host-key failure on any of its ssh sessions (link or deploy,
/// [SshDiagnosis]), which is LOST at once. On declaring it, the session (if any) is closed, so the host comes back only
/// through recovery. While the flag is set:
/// - `hosts.state` is `LOST` with its mark, whatever the link state;
/// - **level, not edge:** every tick settles the host's active units (`UnitStore.settleLost`), so a settle that failed
///   half-way, or a grant that committed as the link dropped, is settled on a later tick; the settle is conditional, so
///   a repeat changes nothing;
/// - the host is never UP for the scheduler ([#isUp]).
/// A link that is UP again when the clock reaches `hostLost` defers the verdict (Q-M4-12): the host keeps its units
/// (the handshake's reconcile reattached them) but takes no new grant until it has been UP for `stableUp`, which clears
/// the clock; leaving UP first declares LOST at once.
/// **Recovery** (§2.5) is the next handshake that completes, in this order: settle to completion, then reconcile, then
/// UP, then the UP write, which clears the stored mark, and only after it commits the flag. Only the loss the handshake
/// saw is cleared: one declared during the recovery closed its session and stays, to be settled. A failed settle or UP
/// write closes the session like a failed reconcile. Settles and the UP write share the host-write slot, so a settle
/// never runs between them, and a settle whose slot turn comes after the flag cleared does nothing. Each of the slot's
/// store calls is bounded by `timing.request` before anything is chained to it: a hung store fails the step (the next
/// tick settles again, recovery closes and retries) instead of stalling the host's settles and recovery for good, and
/// a timed-out mark write never chains to a settle. The bound does not stop the statement, so the store orders the
/// writes itself: the UP write clears only the mark recovery saw and remembers it, a mark write at or before a cleared
/// mark is refused, and a refused mark clears the flag (m4-design §2.5).
/// A LOST mark found in the store at [#start] is kept (Q-M4-8): the host stays LOST across a controller restart, and
/// the down clock starts at the start.
///
/// **Retired** (#103): once the host is past its `retireAt` (m4-design §10) and holds no active unit, the link stops
/// for good, whether the instant passed before the start or while the link runs: every connect and every liveness tick
/// asks. It is not dialed again, and its session, if any, is shut down. A retired host still holding units keeps its
/// link until they end or the LOST path settles them (Q-M4-10).
public final class HostLink {
    private static final Logger log = LoggerFactory.getLogger(HostLink.class);

    private final ControllerConfig.Host host;
    private final LinkEnv env;
    private final WorkerEvents events;
    private final ReconcileRun reconcile;
    private final AtomicReference<LinkState> state = new AtomicReference<>(new LinkState.Down(1));
    private final AtomicReference<Option<HostFacts>> facts = new AtomicReference<>(none());
    private final AtomicReference<Option<ScheduledFuture<?>>> ticks = new AtomicReference<>(none());
    private final AtomicBoolean deployFirst = new AtomicBoolean(false);

    private final AtomicReference<Liveness> liveness = new AtomicReference<>(Liveness.liveness(none(), false, none()));

    private final AtomicBoolean settling = new AtomicBoolean(false);
    private final Serial hostWrites = new Serial();

    private HostLink(ControllerConfig.Host host, LinkEnv env) {
        this.host = host;
        this.env = env;
        this.events = WorkerEvents.workerEvents(host, env, this::adoptFacts);
        this.reconcile = ReconcileRun.reconcileRun(host.name(), env, events);
    }

    public static HostLink hostLink(ControllerConfig.Host host, LinkEnv env) {
        return new HostLink(host, env);
    }

    public HostName name() {
        return host.name();
    }

    public ControllerConfig.Host config() {
        return host;
    }

    public LinkState state() {
        return state.get();
    }

    /// The link as the scheduler sees it, for a skip reason: `LOST` while LOST, `DEFERRED` while UP with the verdict
    /// deferred (Q-M4-12), otherwise the link state's wire value.
    public String grantState() {
        var current = state.get();

        return lost().isPresent()
               ? "LOST"
               : current instanceof LinkState.Up && overdue()
                 ? "DEFERRED"
                 : current.wire();
    }

    /// UP with a session that is still open, not LOST, and no LOST verdict deferred. A closed session flips `isOpen`
    /// synchronously in `Connection.close`, while the move to DOWN runs from the `closed()` callback a moment later;
    /// reading both closes that window, so the scheduler never grants to, and no request is sent on, a session that is
    /// already gone. The LOST flag closes the same window between declaring LOST and the close of the session, and
    /// between the move to UP and the end of recovery. A deferred verdict (Q-M4-12) keeps the host out of grants until
    /// an UP of `stableUp` clears the clock.
    public boolean isUp() {
        return upSession().isPresent()
               && lost().isEmpty()
               && !overdue();
    }

    private boolean overdue() {
        return HostLiveness.overdue(liveness.get().downSince(),
                                    Instant.now(),
                                    env.hostLost());
    }

    /// The LOST mark while the host is LOST (m4-design §3).
    public Option<HostLoss> lost() {
        return liveness.get()
                       .lost();
    }

    /// Starts in flight on this link ([WorkerEvents#pendingStarts]), for tests.
    int pendingStarts() {
        return events.pendingStarts();
    }

    /// UNKNOWN marks not landed yet on this link ([WorkerEvents#pendingUnknowns]), for tests.
    int pendingUnknowns() {
        return events.pendingUnknowns();
    }

    /// The units on this link that run but whose start is not recorded ([WorkerEvents#unrecordedStarts]).
    public Set<UnitAttempt> unrecordedStarts() {
        return events.unrecordedStarts();
    }

    /// The latest worker facts (`Hello` or `Heartbeat`).
    public Option<HostFacts> facts() {
        return facts.get();
    }

    /// Reads the stored LOST mark, then starts connecting and the liveness check. The down clock starts now
    /// (m4-design §2.1, Q-M4-8). Nothing is connected, and nothing written to `hosts`, before the mark is read: any
    /// such write would erase it (`HostStore.state` clears the mark), and recovery would then skip its settle. A
    /// failed read is retried with the link's backoff until it succeeds or the link stops.
    public Unit start() {
        return readMark(Instant.now(), 1);
    }

    private Unit readMark(Instant started, int attempt) {
        // May run late: the start is detached; the link's state machine is the outcome.
        env.hosts()
           .loss(host.name())
           .onSuccess(stored -> begin(started, stored))
           .onFailure(cause -> lossUnread(started, attempt, cause));

        return unit();
    }

    private Unit lossUnread(Instant started, int attempt, Cause cause) {
        var delay = env.timing().backoff().nextTimeout(attempt);

        log.warn("[{}] the stored LOST mark cannot be read, retrying in {}: {}", host.name(), delay, cause.message());
        SharedScheduler.schedule(() -> rereadMark(started, attempt + 1), delay);

        return unit();
    }

    private Unit rereadMark(Instant started, int attempt) {
        return state.get() instanceof LinkState.Stopped
               ? unit()
               : readMark(started, attempt);
    }

    /// A link stopped while the mark was read never starts.
    private Unit begin(Instant started, Option<HostLoss> stored) {
        return state.get() instanceof LinkState.Stopped
               ? unit()
               : begun(started, stored);
    }

    private Unit begun(Instant started, Option<HostLoss> stored) {
        liveness.set(Liveness.liveness(some(started), false, stored));
        stored.onPresent(loss -> log.warn("[{}] LOST({}) since {}, stored", host.name(), loss.reason(), loss.at()));
        ticks.set(some(SharedScheduler.scheduleAtFixedRate(this::livenessTick,
                                                           env.timing().livenessCheck())));

        return connect(1);
    }

    /// Stops for good: tells the worker to shut down (its containers keep running) and closes the session; the
    /// link's background writes (start retries, UNKNOWN marks) stop too ([WorkerEvents#stop]).
    public Unit stop() {
        var last = state.getAndSet(new LinkState.Stopped());

        stopTicks();
        events.stop();
        last.connection().onPresent(this::shutdown);

        return unit();
    }

    /// The unit's current attempt as the store has it (m4-design §4.1): a request about one unit that this link sends
    /// outside reconcile (`CancelUnit`, `LogTail`) names it (protocol v3), so a worker holding another attempt answers
    /// `stale-attempt` and acts on nothing.
    public Promise<Integer> currentAttempt(UnitRef ref) {
        return env.units()
                  .attempts(List.of(ref))
                  .flatMap(stored -> currentAttempt(ref, stored));
    }

    private static Promise<Integer> currentAttempt(UnitRef ref, List<StoredAttempt> stored) {
        return Option.from(stored.stream().filter(attempt -> attempt.ref()
                                                                    .equals(ref)).findFirst())
                     .map(StoredAttempt::attempt)
                     .async(StoreError.UnitNotFound.FACTORY.apply(ref.job(),
                                                                  ref.unit()));
    }

    /// A request over the UP session with the link's request timeout; fails with [LinkError.Down] otherwise.
    /// Nothing is reset on failure (a log tail that times out says nothing about the units).
    public Promise<Message> request(Message message) {
        return upSession().map(session -> session.request(message,
                                                          env.timing().request()))
                        .or(this::notUp);
    }

    /// A request over the UP session with its own timeout (`GcJobs` deletes whole job trees, which can outlast the
    /// link's request timeout).
    public Promise<Message> request(Message message, TimeSpan timeout) {
        return upSession().map(session -> session.request(message, timeout))
                        .or(this::notUp);
    }

    /// A request whose failure means the worker's view and the store's may now differ (`CancelUnit`): unless it is
    /// answered with `expected`, the session that carried it is closed, so the reconnect's reconcile settles the
    /// difference (a worker that keeps failing the same action gets the unit settled as `ERROR(infra)` there). Only
    /// that session is closed, never a newer one.
    public Promise<Message> requestOrReset(Message message, Class<? extends Message> expected) {
        return upSession().map(session -> expectedOrReset(session, message, expected))
                        .or(this::notUp);
    }

    private Promise<Message> expectedOrReset(Connection session, Message message, Class<? extends Message> expected) {
        // The reset may run late: the one caller (`HostLinks`) logs and moves on, and a request that picks the session
        // before the reset lands fails `Down` when it does.
        return session.request(message,
                               env.timing().request())
                      .flatMap(reply -> Connection.expect(reply, expected).async())
                      .onFailure(cause -> reset(session, cause));
    }

    /// Starts a granted unit on the current UP session ([UnitLauncher]); a grant made while the link went down
    /// stays GRANTED and is settled by the next handshake's reconcile.
    public Promise<Unit> launch(Granted granted) {
        return upSession().map(session -> UnitLauncher.unitLauncher(host, session, env, events).launch(granted))
                        .or(this::notUp);
    }

    private <T> Promise<T> notUp() {
        return LinkError.Down.FACTORY.apply(host.name()).promise();
    }

    /// Closes exactly `session` (a no-op when it is already closed); the DOWN path reconnects and reconciles.
    static Unit reset(Connection session, Cause cause) {
        log.warn("[{}] resetting the session after: {}", session.host(), CauseChain.render(cause));

        return session.close();
    }

    private Option<Connection> upSession() {
        return switch (state.get()) {
            case LinkState.Up up -> some(up.session()).filter(Connection::isOpen);
            default -> none();
        };
    }

    /// The worker exits on `Shutdown` or on EOF alike, so a failed send changes nothing: the close follows.
    private Unit shutdown(Connection session) {
        session.send(new Message.Shutdown())
               .onFailure(cause -> log.info("[{}] Shutdown not sent: {}",
                                            host.name(),
                                            cause.message()));

        return session.close();
    }

    private Unit latestFacts(HostFacts latest) {
        facts.set(some(latest));

        return unit();
    }

    // ---- connect -----------------------------------------------------------------------------------------------
    /// A retired host (#103) is not dialed once it holds no active unit: the link stops instead.
    private Unit connect(int attempt) {
        return retired(env.clock().get())
               ? drainedThen(() -> dial(attempt))
               : dial(attempt);
    }

    private Unit dial(int attempt) {
        return transition(LinkState.Down.class::isInstance, _ -> new LinkState.Connecting(attempt, none()))
               ? deployThenSpawn(attempt)
               : unit();
    }

    // ---- retirement (#103) -------------------------------------------------------------------------------------
    /// The host is past its `retireAt` (m4-design §10: retired at the instant itself).
    private boolean retired(Instant now) {
        return host.retiredSince(now)
                   .isPresent();
    }

    /// Retired and drained (no active unit): the link stops for good ([#stop]), so the host is not dialed again and its
    /// liveness clock stops. Retired with units still active: `otherwise` runs, so the link keeps them (and the LOST
    /// path settles them if the host is gone, Q-M4-10); the next connect or tick asks again. A failed read keeps the
    /// link as well: stopping it on a guess could strand units.
    private Unit drainedThen(Runnable otherwise) {
        // May run late: the check is detached; the stopped link, or `otherwise`, is the outcome.
        env.units()
           .active(host.name())
           .onSuccess(active -> retireOr(active.isEmpty(),
                                         otherwise))
           .onFailure(cause -> unreadActive(cause, otherwise));

        return unit();
    }

    private Unit retireOr(boolean drained, Runnable otherwise) {
        if (drained) {
            return retire();
        }

        otherwise.run();

        return unit();
    }

    private Unit unreadActive(Cause cause, Runnable otherwise) {
        log.warn("[{}] retired, but its active units cannot be read; the link stays: {}", host.name(), cause.message());
        otherwise.run();

        return unit();
    }

    private Unit retire() {
        if (! (state.get() instanceof LinkState.Stopped)) {
            log.info("[{}] retired since {} with no active unit: link stopped, not dialed again",
                     host.name(),
                     host.retireAt().map(Instant::toString).or("?"));
        }

        return stop();
    }

    /// The deploy outcome is logged by [#deployIfNeeded]; the connect proceeds either way (a failed deploy shows up
    /// again as a session without `Hello`).
    private Unit deployThenSpawn(int attempt) {
        // May run late: the connect is detached; nothing waits for the spawn.
        deployIfNeeded().onResultRun(() -> spawn(attempt));

        return unit();
    }

    private Promise<Unit> deployIfNeeded() {
        return deployFirst.getAndSet(false)
               ? WorkerDeploy.deploy(env, host)
                             .withSuccess(_ -> log.info("[{}] deployed worker {}",
                                                        host.name(),
                                                        env.version()))
                             .withFailure(this::deployFailed)
               : Promise.unitPromise();
    }

    /// L4: a worker jar of another build is an operator's error that no retry fixes: logged at ERROR, naming both
    /// builds. A host-key failure is LOST at once, decided before the next connect spawns. Any other deploy failure is
    /// a warning (the link retries).
    private Unit deployFailed(Cause cause) {
        return switch (cause) {
            case LinkError.ForeignWorkerJar foreign -> foreignJar(foreign);
            case LinkError.HostKey hostKey -> hostKeyFailed(hostKey);
            default -> deployWarning(cause);
        };
    }

    private Unit foreignJar(LinkError.ForeignWorkerJar foreign) {
        log.error("[{}] {}", host.name(), foreign.message());

        return unit();
    }

    private Unit deployWarning(Cause cause) {
        log.warn("[{}] {}", host.name(), cause.message());

        return unit();
    }

    private Unit spawn(int attempt) {
        markHost();

        return Command.command(SshCommand.run(host,
                                              env.version()),
                               Map.of(),
                               env.timing().hello())
                      .map(Command::inheritingEnvironment)
                      .flatMap(env.runner()::spawn)
                      .fold(cause -> spawnFailed(attempt, cause),
                            process -> open(attempt, process));
    }

    private Unit spawnFailed(int attempt, Cause cause) {
        log.warn("[{}] cannot start ssh: {}", host.name(), cause.message());

        return down(current -> current instanceof LinkState.Connecting connecting && connecting.connection()
                                                                                               .isEmpty());
    }

    private Unit open(int attempt, Spawned process) {
        var session = Connection.connection(host.name(), process, env.mapper(), events::handle);

        return transition(current -> current instanceof LinkState.Connecting connecting && connecting.connection()
                                                                                                     .isEmpty(),
                          _ -> new LinkState.Connecting(attempt, some(session)))
               ? awaitHello(attempt, session)
               : session.close();
    }

    /// m4-design §2.2: ssh's stderr on this session reported a host-key failure.
    private Unit watchHostKey(Connection session) {
        // May run late: the failure is latched and decided at once; nothing waits for it.
        session.hostKeyFailure().onSuccess(_ -> hostKeyOn(session));

        return unit();
    }

    private Unit hostKeyOn(Connection session) {
        return superseded(session)
               ? unit()
               : hostKeyFailed(LinkError.HostKey.FACTORY.apply(host.name()));
    }

    /// The link has moved past `session`: it holds another one, or it is stopped. A session that closed as its ssh
    /// exited still counts until then (its close can land before its stderr is read).
    private boolean superseded(Connection session) {
        var current = state.get();

        return current instanceof LinkState.Stopped || current.connection()
                                                              .filter(owned -> owned != session)
                                                              .isPresent();
    }

    private Unit hostKeyFailed(LinkError.HostKey failure) {
        log.warn("[{}] {}", host.name(), failure.message());
        liveness.updateAndGet(Liveness::withHostKeyFailure);

        return judge();
    }

    private Unit awaitHello(int attempt, Connection session) {
        // May run late: the session's close and its `Hello` drive the link's state machine, which nothing awaits.
        session.closed().onResultRun(() -> closed(session));
        watchHostKey(session);
        session.hello()
               .timeout(env.timing().hello())
               .onSuccess(hello -> hello(attempt, session, hello))
               .onFailure(cause -> noHello(session, cause));

        return unit();
    }

    /// The session ended before `Hello` (the jar may be missing on the host): deploy before the next connect. A
    /// `Hello` timeout only closes.
    private Unit noHello(Connection session, Cause cause) {
        log.warn("[{}] no Hello: {}", host.name(), cause.message());
        deployFirst.compareAndSet(false, cause instanceof LinkError.Down);

        return session.close();
    }

    // ---- handshake ---------------------------------------------------------------------------------------------
    private Unit hello(int attempt, Connection session, Message.Hello hello) {
        return Handshake.compatible(hello, env.version())
               ? accepted(attempt, session, hello)
               : incompatible(session, hello);
    }

    private Unit incompatible(Connection session, Message.Hello hello) {
        log.warn("[{}] worker {} (protocol {}) is incompatible; deploying {}",
                 host.name(),
                 hello.workerVersion(),
                 hello.protocol(),
                 env.version());
        session.send(Handshake.answer(hello,
                                      env.version()))
               .onFailure(cause -> log.info("[{}] Incompatible not sent: {}",
                                            host.name(),
                                            cause.message()));
        deployFirst.set(true);

        return session.close();
    }

    /// A `Welcome` that cannot be sent ends the session: the worker never learns it was accepted.
    private Unit accepted(int attempt, Connection session, Message.Hello hello) {
        return session.send(Handshake.answer(hello,
                                             env.version()))
                      .fold(cause -> reset(session, cause),
                            _ -> welcomed(attempt, session, hello));
    }

    private Unit welcomed(int attempt, Connection session, Message.Hello hello) {
        recordHelloFacts(hello);

        return transition(current -> current instanceof LinkState.Connecting && owns(current, session),
                          _ -> new LinkState.Handshaking(attempt, session))
               ? recovered(session, hello)
               : session.close();
    }

    /// m4-design §2.5: a LOST host settles to completion before its reconcile; a healthy one reconciles at once. The
    /// mark seen here is the one recovery may clear.
    private Unit recovered(Connection session, Message.Hello hello) {
        var seen = lost();

        return seen.isPresent()
               ? settleThenReconcile(session, hello, seen)
               : reconciled(session, hello, seen);
    }

    private Unit settleThenReconcile(Connection session, Message.Hello hello, Option<HostLoss> seen) {
        // May run late: the handshake is detached; the reconcile or the close is the outcome.
        settle().onSuccess(_ -> reconciled(session, hello, seen)).onFailure(cause -> settleFailed(session, cause));

        return unit();
    }

    private Unit settleFailed(Connection session, Cause cause) {
        log.warn("[{}] settle of the LOST host failed, reconnecting: {}", host.name(), cause.message());

        return session.close();
    }

    private Unit recordHelloFacts(Message.Hello hello) {
        option(hello.facts()).flatMap(HostLink::factsOf).onPresent(this::adoptFacts);

        return unit();
    }

    private static Option<HostFacts> factsOf(HostFactsDto dto) {
        return dto.toDomain()
                  .option();
    }

    /// The latest facts in memory (what the scheduler and `GET /hosts` read), then `hosts.facts`.
    private Unit adoptFacts(HostFacts latest) {
        latestFacts(latest);

        return writeHost();
    }

    private Unit reconciled(Connection session, Message.Hello hello, Option<HostLoss> seen) {
        markHost();
        // May run late: the handshake is detached; `up` or the close is the outcome.
        reconcile.run(session, hello)
                 .onSuccess(actions -> up(session, actions, seen))
                 .onFailure(cause -> reconcileFailed(session, cause));

        return unit();
    }

    private Unit reconcileFailed(Connection session, Cause cause) {
        log.warn("[{}] reconcile failed, reconnecting: {}", host.name(), cause.message());

        return session.close();
    }

    /// UP only on a session still open and with the LOST mark still the one seen at the handshake: a loss declared
    /// during the settle or reconcile closed the session (or is closing it) and must be settled, never cleared.
    private Unit up(Connection session, List<ReconcileAction> actions, Option<HostLoss> seen) {
        // Each half alone is defence in depth: a declaration closes the session first, so either sees it.
        return session.isOpen() && lost().equals(seen)
               ? enterUp(session, actions, seen)
               : lostMeanwhile(session);
    }

    private Unit lostMeanwhile(Connection session) {
        log.warn("[{}] the host was declared LOST or the session closed during reconcile; not UP", host.name());

        return session.close();
    }

    private Unit enterUp(Connection session, List<ReconcileAction> actions, Option<HostLoss> seen) {
        return transition(current -> current instanceof LinkState.Handshaking && owns(current, session),
                          current -> new LinkState.Up(session, Instant.now(), attemptOf(current)))
               ? announceUp(session, actions, seen)
               : unit();
    }

    private Unit announceUp(Connection session, List<ReconcileAction> actions, Option<HostLoss> seen) {
        log.info("[{}] link UP ({} reconcile actions)", host.name(), actions.size());
        env.signal().up().apply(host.name());

        return seen.map(loss -> clearLoss(session, loss))
                   .or(this::healthyUp);
    }

    private Unit healthyUp() {
        markHost();

        return env.wake()
                  .signal();
    }

    /// §2.5: the UP write (it clears the stored mark) commits before the flag clears, both inside the host-write slot;
    /// until then the host takes no grant ([#isUp]). A failed write, or a session gone before it, closes the session:
    /// the next handshake recovers again.
    private Unit clearLoss(Connection session, HostLoss seen) {
        // May run late: the handshake is detached; the cleared flag (and the wake) or the close is the outcome.
        hostWrites.run(() -> upWrite(session, seen))
                  .onSuccess(_ -> env.wake()
                                     .signal())
                  .onFailure(cause -> clearFailed(session, cause));

        return unit();
    }

    /// `HostStore.recovered` clears the stored mark only while it is still `seen`, and remembers it as cleared, so a
    /// `hosts.lost` of it that commits later is refused (m4-design §2.5).
    private Promise<Unit> upWrite(Connection session, HostLoss seen) {
        return session.isOpen()
               ? bounded(env.hosts().recovered(host.name(), seen).flatMap(_ -> writeFacts())).map(_ -> cleared(seen))
               : LinkError.Down.FACTORY.apply(host.name()).promise();
    }

    private Unit cleared(HostLoss seen) {
        liveness.updateAndGet(current -> current.recovered(seen));
        log.info("[{}] recovered from LOST({})", host.name(), seen.reason());

        return unit();
    }

    private Unit clearFailed(Connection session, Cause cause) {
        log.warn("[{}] recording the recovery failed, reconnecting: {}", host.name(), cause.message());

        return session.close();
    }

    // ---- down --------------------------------------------------------------------------------------------------
    private Unit closed(Connection session) {
        return down(current -> owns(current, session));
    }

    /// Moves to DOWN when `guard` holds (never from Stopped) and schedules the reconnect. The failed attempt
    /// restarts at 1 after a stable UP.
    private Unit down(Predicate<LinkState> guard) {
        var now = Instant.now();

        return changed(guard,
                       current -> new LinkState.Down(failedAttempt(current, now) + 1)).map(previous -> leftLink(previous,
                                                                                                                now))
                      .or(unit());
    }

    private Unit leftLink(LinkState previous, Instant now) {
        leftUpSignal(previous);
        tickClock(upSince(previous), now);
        tickClock(none(), now);
        leftUp(previous, now);

        return reconnectLater(failedAttempt(previous, now));
    }

    /// Only a link that WAS up announces leaving it (`host-unreachable`); a failed reconnect from DOWN says nothing new.
    private Unit leftUpSignal(LinkState previous) {
        return previous instanceof LinkState.Up
               ? env.signal()
                    .down()
                    .apply(host.name())
               : unit();
    }

    /// Q-M4-12: a link that leaves UP while its verdict is deferred is LOST now, not at the next tick; the clock goes
    /// on from where it was.
    private Unit leftUp(LinkState previous, Instant now) {
        var current = liveness.get();

        return previous instanceof LinkState.Up && current.lost()
                                                          .isEmpty()
               ? decided(current, now)
               : unit();
    }

    /// The attempt that just failed: 1 after an UP that lasted `timing.stableUp`, otherwise the attempt that reached
    /// the state the link is leaving (so a link that drops right after each handshake keeps backing off).
    private int failedAttempt(LinkState current, Instant now) {
        return stable(current, now)
               ? 1
               : attemptOf(current);
    }

    private static boolean owns(LinkState current, Connection session) {
        return current.connection()
                      .filter(owned -> owned == session)
                      .isPresent();
    }

    private Unit reconnectLater(int failed) {
        var delay = env.timing().backoff().nextTimeout(failed);

        log.info("[{}] link DOWN; reconnecting in {}", host.name(), delay);
        markHost();
        SharedScheduler.schedule(() -> connect(failed + 1), delay);

        return unit();
    }

    private static int attemptOf(LinkState current) {
        return switch (current) {
            case LinkState.Connecting connecting -> connecting.attempt();
            case LinkState.Handshaking handshaking -> handshaking.attempt();
            case LinkState.Down down -> down.attempt();
            case LinkState.Up up -> up.attempt();
            case LinkState.Stopped _ -> 1;
        };
    }

    private boolean stable(LinkState current, Instant now) {
        return switch (current) {
            case LinkState.Up up -> Duration.between(up.since(), now).compareTo(env.timing().stableUp()) >= 0;
            default -> false;
        };
    }

    // ---- liveness ----------------------------------------------------------------------------------------------
    /// No frame for `timing.silence` while HANDSHAKING or UP: the session is dead (heartbeats come every 10 s). Then
    /// the LOST decision (m4-design §2.1) and, while LOST, the level-triggered settle (§2.3).
    /// A stopped link's tick does nothing but cancel the ticks (a tick scheduled as the link stopped).
    /// Package-private so tests can tick deterministically instead of waiting for the schedule.
    Unit livenessTick() {
        return state.get() instanceof LinkState.Stopped
               ? stopTicks()
               : tick();
    }

    /// A retired link is checked for its drain after the tick's own work, so a LOST host's settle runs first.
    private Unit tick() {
        var now = Instant.now();

        silence(state.get());
        tickClock(now);
        judge();

        return retired(env.clock().get())
               ? drainedThen(() -> {})
               : unit();
    }

    private Unit stopTicks() {
        ticks.getAndSet(none()).onPresent(future -> future.cancel(false));

        return unit();
    }

    private Unit silence(LinkState current) {
        return switch (current) {
            case LinkState.Up up -> silent(up.session());
            case LinkState.Handshaking handshaking -> silent(handshaking.session());
            default -> unit();
        };
    }

    private static Option<Instant> upSince(LinkState current) {
        return switch (current) {
            case LinkState.Up up -> some(up.since());
            default -> none();
        };
    }

    /// The tick's clock move, from the link state as it is when the move is written (not as the tick began). A clock
    /// cleared by an UP of `stableUp` wakes the scheduler: a deferred host takes grants again (Q-M4-12).
    private Unit tickClock(Instant now) {
        var previous = liveness.getAndUpdate(current -> moved(current, upSince(state.get()), now));

        return previous.downSince()
                       .isPresent() && liveness.get()
                                               .downSince()
                                               .isEmpty()
               ? env.wake()
                    .signal()
               : unit();
    }

    /// The down clock after `now` ([HostLiveness#clock]); it does not move while LOST (recovery clears it).
    private Unit tickClock(Option<Instant> upSince, Instant now) {
        liveness.updateAndGet(current -> moved(current, upSince, now));

        return unit();
    }

    private Liveness moved(Liveness current, Option<Instant> upSince, Instant now) {
        return current.lost()
                      .isPresent()
               ? current
               : current.clock(HostLiveness.clock(current.downSince(),
                                                  upSince,
                                                  now,
                                                  env.timing().stableUp()));
    }

    /// LOST already: settle again (level). Otherwise decide, and declare LOST when the verdict says so.
    private Unit judge() {
        var current = liveness.get();

        return current.lost()
                      .map(_ -> settleTick())
                      .or(() -> decided(current,
                                        Instant.now()));
    }

    private Unit decided(Liveness current, Instant now) {
        return switch (HostLiveness.decide(current.downSince(),
                                           now,
                                           env.hostLost(),
                                           current.hostKeyFailed(),
                                           upSession().isPresent())) {
            case HostLiveness.Lost lost -> declare(HostLoss.hostLoss(now,
                                                                     lost.reason().wire()));
            default -> unit();
        };
    }

    /// Sets the flag once; the declaring call closes the session (if any) and settles.
    private Unit declare(HostLoss loss) {
        return liveness.getAndUpdate(current -> current.declared(loss))
                       .lost()
                       .isEmpty()
               ? declared(loss)
               : unit();
    }

    private Unit declared(HostLoss loss) {
        log.warn("[{}] host LOST({}): its active units are settled (m4-design §2.3)", host.name(), loss.reason());
        state.get().connection().onPresent(Connection::close);

        return settleTick();
    }

    /// One tick's settle; skipped while the previous tick's settle still runs (bounded by the slot's timeout).
    private Unit settleTick() {
        return settling.compareAndSet(false, true)
               ? settleOnce()
               : unit();
    }

    private Unit settleOnce() {
        // May run late: the tick is detached; the next tick settles again whatever this one did.
        settle().onResultRun(() -> settling.set(false))
              .onFailure(cause -> log.warn("[{}] settle of the LOST host failed, the next tick retries: {}",
                                           host.name(),
                                           cause.message()));

        return unit();
    }

    /// The LOST mark first (the store settles only a host stored LOST, m4-design §2.3 "the settle guard"), then the
    /// settle of every unit active on the host. Settled units are logged and the scheduler is woken (a requeued unit
    /// may run elsewhere). Both run in the host-write slot, never between recovery's UP write and its clearing of the
    /// flag; a settle whose slot turn comes after the flag cleared settles nothing (a fresh grant on the recovered host
    /// stays), and no settle runs after its step timed out ([#settleNow]).
    private Promise<List<LostSettled>> settle() {
        return hostWrites.run(this::settleNow)
                         .withSuccess(this::settled);
    }

    /// Each store call is bounded before its continuation: a mark write that times out never chains to the settle, which
    /// would otherwise run outside the slot once the write ends (m4-design §2.5).
    private Promise<List<LostSettled>> settleNow() {
        return lost().isPresent()
               ? bounded(writeHostNow()).flatMap(_ -> bounded(env.units()
                                                                 .settleLost(host.name(),
                                                                             Instant.now(),
                                                                             JobDecision::decide)))
               : Promise.success(List.of());
    }

    /// A host-write slot step's store call, failed after `timing.request` so a hung store never holds the slot. The
    /// timeout fails this derived promise only: whatever is chained after it does not run, but the call itself may
    /// still commit later, which the store's mark guards make harmless (m4-design §2.5).
    private <T> Promise<T> bounded(Promise<T> storeCalls) {
        return storeCalls.timeout(env.timing().request());
    }

    private Unit settled(List<LostSettled> units) {
        return units.isEmpty()
               ? unit()
               : announceSettled(units);
    }

    private Unit announceSettled(List<LostSettled> units) {
        units.forEach(unit -> log.info("[{}] LOST host: {} attempt {} -> {}",
                                       host.name(),
                                       unit.ref().containerName(),
                                       unit.attempt(),
                                       unit.outcome()));

        return env.wake()
                  .signal();
    }

    private Unit silent(Connection session) {
        return session.silenceNanos() > env.timing()
                                           .silence()
                                           .toNanos()
               ? killSilent(session)
               : unit();
    }

    private Unit killSilent(Connection session) {
        log.warn("[{}] no frame for {}; closing the link",
                 host.name(),
                 env.timing().silence());

        return session.close();
    }

    // ---- state -------------------------------------------------------------------------------------------------
    private boolean transition(Predicate<LinkState> guard, Function<LinkState, LinkState> next) {
        return changed(guard, next).isPresent();
    }

    /// The one way the state changes: atomically, and only from a state `guard` accepts. Answers the state it
    /// changed from.
    private Option<LinkState> changed(Predicate<LinkState> guard, Function<LinkState, LinkState> next) {
        var previous = state.getAndUpdate(current -> step(current, guard, next));

        return some(previous).filter(guard);
    }

    /// `getAndUpdate` may retry this, so it must be pure: the next state, or the same state when `guard` refuses.
    private static LinkState step(LinkState current, Predicate<LinkState> guard, Function<LinkState, LinkState> next) {
        return guard.test(current)
               ? next.apply(current)
               : current;
    }

    private Unit markHost() {
        return writeHost();
    }

    /// `hosts.state` and `hosts.facts` are written one at a time, each write reading the CURRENT state and facts when
    /// it runs, so the table converges on the latest values whatever order the triggers came in. FER: the table is
    /// informational (the scheduler and `GET /hosts` read the link's memory); a failed write is logged and the next
    /// transition or heartbeat writes again.
    private Unit writeHost() {
        hostWrites.run(() -> bounded(writeHostNow()))
                  .onFailure(cause -> log.warn("[{}] recording host state failed: {}",
                                               host.name(),
                                               cause.message()));

        return unit();
    }

    /// While LOST, the mark (`HostStore.lost`) whatever the link state; otherwise the link state, which clears it.
    private Promise<Unit> writeHostNow() {
        return lost().map(this::writeMark)
                   .or(() -> env.hosts()
                                .state(host.name(),
                                       wire()))
                   .flatMap(_ -> writeFacts());
    }

    /// A mark the store refuses as stale was cleared there by a recovery whose UP write committed after its bound failed
    /// it: the store is the authority, so the flag follows it (only while it is still that mark) and the next handshake
    /// is a healthy one (m4-design §2.5).
    private Promise<Unit> writeMark(HostLoss loss) {
        return env.hosts()
                  .lost(host.name(),
                        loss)
                  .withFailure(cause -> staleMark(loss, cause));
    }

    /// Only a refusal clears the flag: any other failure (the store down) says nothing about recovery, and the flag
    /// stays so the next tick settles again (pinned by `lost_transientMarkFailure_flagKept`).
    private Unit staleMark(HostLoss loss, Cause cause) {
        return cause instanceof StoreError.StaleLoss
               ? clearedInStore(loss)
               : unit();
    }

    private Unit clearedInStore(HostLoss loss) {
        liveness.updateAndGet(current -> current.recovered(loss));
        log.warn("[{}] the store already recovered from LOST({}); the flag follows it", host.name(), loss.reason());

        return unit();
    }

    private String wire() {
        return state.get()
                    .wire();
    }

    private Promise<Unit> writeFacts() {
        return facts.get()
                    .map(this::storedFacts)
                    .or(Promise::unitPromise);
    }

    private Promise<Unit> storedFacts(HostFacts latest) {
        return env.hosts()
                  .facts(host.name(),
                         latest);
    }

    /// The LOST flag's inputs (m4-design §3): the down clock, a host-key failure seen since the last recovery, and the
    /// mark once LOST.
    private record Liveness(Option<Instant> downSince, boolean hostKeyFailed, Option<HostLoss> lost) {
        static Liveness liveness(Option<Instant> downSince, boolean hostKeyFailed, Option<HostLoss> lost) {
            return new Liveness(downSince, hostKeyFailed, lost);
        }

        Liveness clock(Option<Instant> next) {
            return liveness(next, hostKeyFailed, lost);
        }

        Liveness withHostKeyFailure() {
            return liveness(downSince, true, lost);
        }

        /// Recovery from `seen` (in memory, or found in the store) clears the flag and its clock (m4-design §3: LOST →
        /// healthy), only while the flag is still that mark (compare-and-set).
        Liveness recovered(HostLoss seen) {
            // Load-bearing: a stale refusal of an older mark can arrive after a newer loss was declared and stored;
            // clearing that newer flag would leave the store LOST while memory is healthy (a restart then requeues
            // units the link kept running). Pinned by HostLostLinkTest.staleRefusal_olderMark_newerLossKept.
            return lost.equals(some(seen))
                   ? liveness(none(), false, none())
                   : this;
        }

        Liveness declared(HostLoss loss) {
            return liveness(downSince, hostKeyFailed, lost.orElse(some(loss)));
        }
    }
}
