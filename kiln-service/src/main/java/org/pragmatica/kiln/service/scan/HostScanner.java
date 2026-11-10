package org.pragmatica.kiln.service.scan;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.core.scan.ScanOutcome.Complete;
import org.pragmatica.kiln.core.scan.ScanOutcome.Incomplete;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceState.Ok;
import org.pragmatica.kiln.core.source.SourceState.Unreachable;
import org.pragmatica.kiln.service.flow.SingleFlight;
import org.pragmatica.kiln.service.scan.ScanTransportError.NonZero;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.CheckoutStore;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassRecord;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.kiln.service.store.pg.RootPattern;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One host's hot, full and size passes (kiln-f1-design §3.1, §3.2). The loops (WP9) call [#hot], [#full] and [#size];
/// each is single-flight with a deadline ([SingleFlight]), and every ssh session with its store write runs through the
/// host's one [ScanHost#serial], so the host never sees two kiln ssh sessions at once and the store sees one pass of the
/// host at a time. A full pass is one serial step per chunk, so it delays at most one hot tick per chunk.
///
/// No failure makes a count read 0 (§3.4, K-5): a transport failure (ssh exit 255, a timeout, a nonzero rc, an empty
/// answer) is never a [Complete] outcome. It records `Unreachable(reason)` and touches no checkout or non-git row; only
/// exit 74 (the script's read error, after which its listing has no trailer) records an `Incomplete` pass with no rows,
/// which deletes nothing either. A listing is judged by [ScanOutcome#of]: only [Complete] may delete, and only under the
/// roots expanded with its own trailer's `home` ([ScanRoots]).
public final class HostScanner {
    /// Paths per full-pass chunk (§3.1).
    public static final int CHUNK = 100;
    /// A serial step (one ssh session and its store write) still running this long frees the host for the next one.
    public static final Duration STEP_DEADLINE = Duration.ofMinutes(10);
    /// A full pass still running this long fails its tick, and its remaining chunks do not run.
    public static final Duration FULL_DEADLINE = Duration.ofHours(4);
    /// The full-facts list of a hot pass whose hot set is empty: an empty stdin would mean full facts for every checkout
    /// (§3.2), so one path that no root can list is sent instead (`:` is outside the root alphabet), and every checkout
    /// gets a stub line.
    static final CheckoutPath STUBS_ONLY = new CheckoutPath("/kiln:stubs-only");
    /// `kiln-scan`'s read error: the listing stopped before its trailer.
    static final int READ_ERROR = 74;
    static final Cause EXPIRED = Causes.cause("the pass's tick expired before this step started");

    private final ScanHost host;
    private final CheckoutStore checkouts;
    private final SourceStore sources;
    private final Supplier<Instant> clock;
    private final Duration hotWindow;
    private final Duration stepDeadline;
    private final Duration fullDeadline;
    private final Runnable passed;
    private final SingleFlight hotFlight = SingleFlight.singleFlight();
    private final SingleFlight fullFlight = SingleFlight.singleFlight();
    private final SingleFlight sizeFlight = SingleFlight.singleFlight();
    /// The `home` of the host's last complete listing, for the size pass's root match.
    private final AtomicReference<Option<String>> home = new AtomicReference<>(none());
    /// The host's last recorded state, so a failure that goes on keeps the time it began.
    private final AtomicReference<Option<SourceState>> last = new AtomicReference<>(none());
    /// When a `size` of a path last failed: the size pass orders the path as if measured then, so one unreadable
    /// checkout goes to the back of the queue instead of starving the rest.
    private final AtomicReference<Map<CheckoutPath, Instant>> refusedAt = new AtomicReference<>(Map.of());

    private HostScanner(ScanHost host,
                        CheckoutStore checkouts,
                        SourceStore sources,
                        Supplier<Instant> clock,
                        Duration hotWindow,
                        Duration stepDeadline,
                        Duration fullDeadline,
                        Runnable passed) {
        this.host = host;
        this.checkouts = checkouts;
        this.sources = sources;
        this.clock = clock;
        this.hotWindow = hotWindow;
        this.stepDeadline = stepDeadline;
        this.fullDeadline = fullDeadline;
        this.passed = passed;
    }

    /// `passed` runs after every pass that applied a listing (the derivation's trigger, §3.1).
    public static HostScanner hostScanner(ScanHost host,
                                          CheckoutStore checkouts,
                                          SourceStore sources,
                                          Supplier<Instant> clock,
                                          Duration hotWindow,
                                          Runnable passed) {
        return hostScanner(host, checkouts, sources, clock, hotWindow, STEP_DEADLINE, FULL_DEADLINE, passed);
    }

    public static HostScanner hostScanner(ScanHost host,
                                          CheckoutStore checkouts,
                                          SourceStore sources,
                                          Supplier<Instant> clock,
                                          Duration hotWindow,
                                          Duration stepDeadline,
                                          Duration fullDeadline,
                                          Runnable passed) {
        return new HostScanner(host, checkouts, sources, clock, hotWindow, stepDeadline, fullDeadline, passed);
    }

    /// What a size tick did.
    public sealed interface Sized {
        record Measured(CheckoutPath path, long kb) implements Sized {}

        /// The host refused or failed the `size`; the path goes to the back of the queue, as if measured now.
        record Refused(CheckoutPath path, String reason) implements Sized {}

        /// Nothing to measure: no listed path under a configured root.
        record Idle() implements Sized {}
    }

    /// A hot pass (every 2 min): `scan` with the hot set on stdin (Q-2). The state recorded, or none when skipped.
    public Promise<Option<SourceState>> hot() {
        return ticked(hotFlight,
                      waitAndStep(),
                      expired -> host.exclusive(() -> live(expired, () -> hotStep(clock.get())), stepDeadline));
    }

    /// A full pass (daily): every known checkout's full facts, in chunks of [#CHUNK] paths, each its own serial step,
    /// with `k of N` in `host_pass` after each. The first chunk that is not complete ends the pass. With no known
    /// checkout, one chunk with an empty stdin gives every checkout full facts.
    public Promise<Option<SourceState>> full() {
        return ticked(fullFlight, fullDeadline, this::fullPass);
    }

    /// A size tick (one every 5 s): the next listed path, null sizes first, then the oldest, measured with `size <root>
    /// <path>` under the configured root it was listed under. It writes only `size_kb` and `size_at`.
    public Promise<Option<Sized>> size() {
        return ticked(sizeFlight,
                      waitAndStep(),
                      expired -> host.exclusive(() -> live(expired, () -> sizeStep(clock.get())), stepDeadline));
    }

    /// The host this scanner passes over, with its one serial (the assembly shares it with the derivation, WP9).
    public ScanHost host() {
        return host;
    }

    /// The deadline of one serial step (`[scan] stepDeadlineSeconds`, WP9); a transport call gets half of it.
    public Duration stepDeadline() {
        return stepDeadline;
    }

    /// The ticks skipped while the previous one ran: hot, full, size.
    public List<Long> skipped() {
        return List.of(hotFlight.skipped(), fullFlight.skipped(), sizeFlight.skipped());
    }

    /// A transport call within a step: half the step, so a call that never settles is recorded as the host's failure
    /// while the step still has time for its store writes. The transport's own timeout (WP9) is set below it.
    private Duration transportDeadline() {
        return stepDeadline.dividedBy(2);
    }

    /// A tick that queues behind one other step (a full-pass chunk) and then runs its own.
    private Duration waitAndStep() {
        return stepDeadline.multipliedBy(2);
    }

    /// A failed tick marks its steps expired: one still queued on the serial does nothing when its turn comes.
    private static <T> Promise<Option<T>> ticked(SingleFlight flight,
                                                 Duration deadline,
                                                 Function<AtomicBoolean, Promise<T>> run) {
        var expired = new AtomicBoolean();

        return flight.run(() -> run.apply(expired),
                          deadline)
                     .withFailure(_ -> expired.set(true));
    }

    private static <T> Promise<T> live(AtomicBoolean expired, Supplier<Promise<T>> step) {
        return expired.get()
               ? EXPIRED.promise()
               : step.get();
    }

    // ---- hot -----------------------------------------------------------------------------------------------------
    private Promise<SourceState> hotStep(Instant at) {
        return checkouts.hotSet(host.name(),
                                at,
                                hotWindow)
                        .flatMap(hot -> scanned(hot.isEmpty()
                                                ? List.of(STUBS_ONLY)
                                                : hot))
                        .flatMap(scan -> applied(PassKind.HOT, scan, at));
    }

    // ---- full ----------------------------------------------------------------------------------------------------
    private Promise<SourceState> fullPass(AtomicBoolean expired) {
        return checkouts.rows(host.name())
                        .map(HostScanner::chunks)
                        .flatMap(chunks -> chunk(chunks, 0, expired));
    }

    static List<List<CheckoutPath>> chunks(List<CheckoutRow> rows) {
        var paths = rows.stream().map(CheckoutRow::path).toList();

        return paths.isEmpty()
               ? List.of(List.of())
               : IntStream.range(0,
                                 (paths.size() + CHUNK - 1) / CHUNK)
                          .mapToObj(index -> paths.subList(index * CHUNK,
                                                           Math.min(paths.size(),
                                                                    (index + 1) * CHUNK)))
                          .toList();
    }

    private Promise<SourceState> chunk(List<List<CheckoutPath>> chunks, int index, AtomicBoolean expired) {
        return host.exclusive(() -> live(expired,
                                         () -> fullStep(chunks.get(index),
                                                        index + 1,
                                                        chunks.size(),
                                                        clock.get())),
                              stepDeadline)
                   .flatMap(state -> next(chunks, index, expired, state));
    }

    private Promise<SourceState> next(List<List<CheckoutPath>> chunks,
                                      int index,
                                      AtomicBoolean expired,
                                      SourceState state) {
        return state instanceof Ok && index + 1 < chunks.size()
               ? chunk(chunks, index + 1, expired)
               : Promise.success(state);
    }

    private Promise<SourceState> fullStep(List<CheckoutPath> chunk, int k, int n, Instant at) {
        return scanned(chunk).flatMap(scan -> applied(PassKind.FULL, scan, at))
                      .flatMap(state -> progressed(state, k, n));
    }

    /// `k of n` once chunk `k` was applied complete.
    private Promise<SourceState> progressed(SourceState state, int k, int n) {
        return state instanceof Ok
               ? checkouts.progress(host.name(),
                                    PassKind.FULL,
                                    k,
                                    n)
                          .map(_ -> state)
               : Promise.success(state);
    }

    // ---- scan and apply ------------------------------------------------------------------------------------------
    /// The transport's answer, or its failure as a value: a store failure still fails the step, a host failure is
    /// recorded. A throw or a never-settling call of the transport is a failure too ([SingleFlight#bounded]).
    sealed interface Scanned {
        record Answered(ScanOutcome outcome) implements Scanned {}

        record Failed(Cause cause) implements Scanned {}
    }

    private Promise<Scanned> scanned(List<CheckoutPath> full) {
        return SingleFlight.bounded(() -> host.transport()
                                              .scan(host.roots(),
                                                    full),
                                    transportDeadline())
                           .map(lines -> (Scanned) new Scanned.Answered(ScanOutcome.of(lines)))
                           .recover(Scanned.Failed::new);
    }

    private Promise<SourceState> applied(PassKind kind, Scanned scan, Instant at) {
        return switch (scan) {
            case Scanned.Answered(Complete complete) -> pass(kind,
                                                             ScanRoots.expanded(host.roots(), complete.home()),
                                                             complete,
                                                             at).withSuccess(_ -> home.set(complete.home())).flatMap(_ -> afterPass(new Ok(at)));
            case Scanned.Answered(Incomplete incomplete) -> pass(kind, host.roots(), incomplete, at).flatMap(_ -> afterPass(new SourceState.Incomplete(incomplete.reason(),
                                                                                                                                                       at)));
            case Scanned.Failed(var cause) -> failed(kind, cause, at);
        };
    }

    /// Exit 74 is an incomplete listing whose lines were not kept: an INCOMPLETE pass with no rows, deleting nothing. Every
    /// other failure is the host unreachable, and touches no row.
    private Promise<SourceState> failed(PassKind kind, Cause cause, Instant at) {
        return readError(cause)
               ? pass(kind,
                      host.roots(),
                      new Incomplete(List.of(), cause.message()),
                      at).flatMap(_ -> afterPass(new SourceState.Incomplete(cause.message(), at)))
               : checkouts.passFailed(host.name(),
                                      kind,
                                      cause.message(),
                                      at)
                          .flatMap(_ -> recorded(unreachable(cause.message(),
                                                             at)));
    }

    static boolean readError(Cause cause) {
        return cause instanceof NonZero nonZero && nonZero.rc() == READ_ERROR;
    }

    private Promise<PassRecord> pass(PassKind kind, List<String> roots, ScanOutcome outcome, Instant at) {
        return checkouts.applyPass(host.name(), kind, roots, outcome, at);
    }

    /// A pass that applied a listing: its state recorded, then the derivation triggered.
    private Promise<SourceState> afterPass(SourceState state) {
        return recorded(state).withSuccess(_ -> passed.run());
    }

    /// An unreachable host that was already unreachable keeps the time it became so.
    private SourceState unreachable(String reason, Instant at) {
        return new Unreachable(reason,
                               last.get()
                                   .filter(Unreachable.class::isInstance)
                                   .map(Unreachable.class::cast)
                                   .map(Unreachable::since)
                                   .or(at));
    }

    private Promise<SourceState> recorded(SourceState state) {
        return sources.record(Source.host(host.name()),
                              state)
                      .map(_ -> state)
                      .withSuccess(_ -> last.set(some(state)));
    }

    // ---- size ----------------------------------------------------------------------------------------------------
    /// A path to measure, and the configured root word it is listed under (the script expands a `~` root itself).
    record Target(String root, CheckoutPath path) {}

    private Promise<Sized> sizeStep(Instant at) {
        return checkouts.rows(host.name())
                        .flatMap(rows -> target(rows).map(target -> measured(target, at))
                                               .or(() -> Promise.success(new Sized.Idle())));
    }

    /// The listed paths under a root: null sizes first, then the oldest, a failed `size` counting as a measurement.
    private Option<Target> target(List<CheckoutRow> rows) {
        var refused = refusedAt.get();

        return Option.from(rows.stream()
                               .sorted(Comparator.comparing((CheckoutRow row) -> attempted(row, refused).or(Instant.MIN)).thenComparing(row -> row.path()
                                                                                                                                                  .value()))
                               .flatMap(row -> rootOf(row.path()).map(root -> new Target(root,
                                                                                         row.path()))
                                                     .stream())
                               .findFirst());
    }

    /// The later of the row's `size_at` and the path's last failed `size`.
    private static Option<Instant> attempted(CheckoutRow row, Map<CheckoutPath, Instant> refused) {
        var failed = Option.option(refused.get(row.path()));

        return row.sizeAt()
                  .map(sized -> failed.filter(at -> at.isAfter(sized))
                                      .or(sized))
                  .orElse(failed);
    }

    /// The first configured root whose expansion lists `path` (Ruling 4: the caller picks the root it was listed under).
    Option<String> rootOf(CheckoutPath path) {
        var expanded = ScanRoots.expanded(host.roots(), home.get());

        return Option.from(IntStream.range(0,
                                           host.roots().size())
                                    .filter(index -> RootPattern.covered(RootPattern.patterns(List.of(expanded.get(index))),
                                                                         path.value()))
                                    .mapToObj(index -> host.roots()
                                                           .get(index))
                                    .findFirst());
    }

    private Promise<Sized> measured(Target target, Instant at) {
        return SingleFlight.bounded(() -> host.transport()
                                              .size(target.root(),
                                                    target.path()),
                                    transportDeadline())
                           .flatMap(lines -> ScanAnswers.size(target.path(),
                                                              lines)
                                                        .async())
                           .fold(result -> result.fold(cause -> refused(target, cause, at),
                                                       kb -> written(target, kb, at)));
    }

    private Promise<Sized> written(Target target, long kb, Instant at) {
        return checkouts.size(host.name(),
                              target.path(),
                              kb,
                              at)
                        .map(_ -> new Sized.Measured(target.path(),
                                                     kb));
    }

    private Promise<Sized> refused(Target target, Cause cause, Instant at) {
        refusedAt.getAndUpdate(paths -> Stream.concat(paths.entrySet()
                                                           .stream()
                                                           .filter(entry -> !entry.getKey()
                                                                                  .equals(target.path())),
                                                      Stream.of(Map.entry(target.path(),
                                                                          at)))
                                              .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                                    Map.Entry::getValue)));

        return Promise.success(new Sized.Refused(target.path(), cause.message()));
    }
}
