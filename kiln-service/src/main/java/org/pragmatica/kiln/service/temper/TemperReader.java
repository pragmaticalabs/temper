package org.pragmatica.kiln.service.temper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The temper loop (design §3.1, spec §4.5, every 60 s, Q-11): the digest text, stored verbatim and never decoded, then
/// the gate of every open PR's head (open in the full PR table). A failed exchange or a non-200 status is
/// `TEMPER-DOWN(<reason>)` and stops the cycle: the digest and gates already stored stay with their age. A gate answer
/// missing `sha` or `answer` is stored as such and makes the state `TEMPER-SCHEMA(<field>)`, never `TEMPER-DOWN`: temper
/// answered. An answer naming a SHA other than the head asked about (or a prefix of it of 7 or more hex characters:
/// temper answers a prefix query with the prefix) is `TEMPER-SCHEMA(sha)` for that PR, so it is never shown as the
/// head's ([#matched]). A store
/// failure is not temper's and fails the cycle.
///
/// A tick is single-flight (design §3.1), as [org.pragmatica.kiln.service.github.GitHubPoller]'s: a tick while a cycle
/// runs is skipped and counted ([#skippedTicks]). Every exit settles the tick and frees the next: a synchronous throw
/// fails it, and a cycle past its `deadline` reads `TEMPER-DOWN(cycle timed out)`; its late result reaches nothing, and
/// its late digest write and gate reads fail at once. Residual: a read or write already in flight at the deadline still completes.
public record TemperReader(TemperHttp http,
                           KilnStore store,
                           Supplier<Instant> clock,
                           Duration deadline,
                           AtomicReference<Option<SourceState>> last,
                           AtomicBoolean running,
                           AtomicLong skipped) {
    public static final Duration PERIOD = Duration.ofSeconds(60);
    /// A cycle still running this long after it started reads `TEMPER-DOWN(cycle timed out)` and frees the next tick.
    public static final Duration DEADLINE = Duration.ofMinutes(10);
    /// A gate answer's sha: lowercase hex, 7 to 40 characters (the view's join holds the same rule).
    static final Pattern SHA_PREFIX = Pattern.compile("[0-9a-f]{7,40}");

    public static TemperReader temperReader(TemperHttp http, KilnStore store, Supplier<Instant> clock) {
        return temperReader(http, store, clock, DEADLINE);
    }

    public static TemperReader temperReader(TemperHttp http,
                                            KilnStore store,
                                            Supplier<Instant> clock,
                                            Duration deadline) {
        return new TemperReader(http,
                                store,
                                clock,
                                deadline,
                                new AtomicReference<>(none()),
                                new AtomicBoolean(),
                                new AtomicLong());
    }

    /// One loop tick: a cycle, or none when a cycle is still running.
    public Promise<Option<SourceState>> tick() {
        return running.compareAndSet(false, true)
               ? cycle().map(Option::some)
                      .withResult(_ -> running.set(false))
               : Promise.success(skippedTick());
    }

    /// The ticks skipped because a cycle was still running.
    public long skippedTicks() {
        return skipped.get();
    }

    private Option<SourceState> skippedTick() {
        skipped.incrementAndGet();

        return none();
    }

    /// One cycle now, bypassing the single-flight guard. Every exit settles: a synchronous throw fails it, and past the
    /// deadline it reads `TEMPER-DOWN(cycle timed out)`.
    public Promise<SourceState> cycle() {
        var at = clock.get();
        var expired = new AtomicBoolean();

        return bounded(at, expired).fold(result -> judged(result, at, expired))
                      .flatMap(state -> recorded(state).timeout(TimeSpan.fromDuration(deadline)));
    }

    /// The cycle's reads, a synchronous throw as their failure, and the deadline as a [CoreError.Timeout].
    private Promise<List<GateRead>> bounded(Instant at, AtomicBoolean expired) {
        return Result.lift(Causes::fromThrowable,
                           () -> read(at, expired))
                     .async()
                     .flatMap(read -> read)
                     .timeout(TimeSpan.fromDuration(deadline));
    }

    private Promise<List<GateRead>> read(Instant at, AtomicBoolean expired) {
        return http.digestText()
                   .flatMap(text -> live(expired,
                                         () -> store.temper()
                                                    .digest(text, at)))
                   .flatMap(_ -> store.prs()
                                      .all())
                   .flatMap(all -> gates(openOnly(all),
                                         at,
                                         expired));
    }

    private static List<PullRequest> openOnly(List<PullRequest> all) {
        return all.stream()
                  .filter(pr -> pr.state() == PrState.OPEN)
                  .toList();
    }

    /// Iteration: one head after another; the first failed exchange ends the cycle, and so does the deadline.
    private Promise<List<GateRead>> gates(List<PullRequest> open, Instant at, AtomicBoolean expired) {
        return open.stream()
                   .reduce(Promise.<List<GateRead>> success(List.of()),
                           (chain, pr) -> then(chain, pr, at, expired),
                           (first, second) -> second);
    }

    private Promise<List<GateRead>> then(Promise<List<GateRead>> chain,
                                         PullRequest pr,
                                         Instant at,
                                         AtomicBoolean expired) {
        return chain.flatMap(read -> live(expired, () -> withGate(read, pr, at)));
    }

    /// A step of a cycle past its deadline fails at once: it reads and writes nothing.
    private static <T> Promise<T> live(AtomicBoolean expired, Supplier<Promise<T>> step) {
        return expired.get()
               ? TemperReadError.General.TIMED_OUT.promise()
               : step.get();
    }

    private Promise<List<GateRead>> withGate(List<GateRead> read, PullRequest pr, Instant at) {
        return http.gate(pr.head())
                   .map(gate -> matched(pr.head(),
                                        gate))
                   .flatMap(gate -> stored(pr, gate, at))
                   .map(gate -> Stream.concat(read.stream(),
                                              Stream.of(gate))
                                      .toList());
    }

    private Promise<GateRead> stored(PullRequest pr, GateRead gate, Instant at) {
        return store.temper()
                    .gate(pr.number(),
                          pr.head(),
                          gate,
                          at)
                    .map(_ -> gate);
    }

    /// An answer stands only when its sha is lowercase hex of 7 to 40 characters and the head asked about or a prefix
    /// of it (temper answers a prefix query with the prefix); any other is `TEMPER-SCHEMA(sha)`. The minimum of 7 is
    /// git's short-SHA default [guess: lead ruling, round 3]: a shorter prefix names no commit.
    static GateRead matched(Sha head, GateRead gate) {
        return switch (gate) {
            case GateRead.Answered answered when!names(head, answered.sha()) -> new GateRead.SchemaBroken("sha");
            default -> gate;
        };
    }

    private static boolean names(Sha head, String sha) {
        return SHA_PREFIX.matcher(sha).matches() && head.value()
                                                        .startsWith(sha);
    }

    private static Promise<SourceState> judged(Result<List<GateRead>> result, Instant at, AtomicBoolean expired) {
        return result.fold(cause -> down(cause, at, expired), gates -> Promise.success(answered(gates, at)));
    }

    /// Only temper's failures are `TEMPER-DOWN`; any other (the store's) fails the cycle as itself. FER: a cycle past its
    /// deadline degrades forward to `TEMPER-DOWN(cycle timed out)`, and its late digest write and gate reads are stopped ([#live]).
    private static Promise<SourceState> down(Cause cause, Instant at, AtomicBoolean expired) {
        return switch (cause) {
            case CoreError.Timeout _ -> Promise.success(timedOut(at, expired));
            case TemperReadError error -> Promise.success(new SourceState.TemperDown(TemperReadError.reason(error), at));
            default -> cause.promise();
        };
    }

    private static SourceState timedOut(Instant at, AtomicBoolean expired) {
        expired.set(true);

        return new SourceState.TemperDown(TemperReadError.reason(TemperReadError.General.TIMED_OUT), at);
    }

    private static SourceState answered(List<GateRead> gates, Instant at) {
        return Option.from(gates.stream().flatMap(TemperReader::schemaField).findFirst())
                     .<SourceState> map(field -> new SourceState.TemperSchema(field, at))
                     .or(() -> new SourceState.Ok(at));
    }

    private static Stream<String> schemaField(GateRead gate) {
        return switch (gate) {
            case GateRead.SchemaBroken(var field) -> Stream.of(field);
            case GateRead.Answered _ -> Stream.empty();
        };
    }

    private Promise<SourceState> recorded(SourceState state) {
        var continued = last.get().map(previous -> SourceState.continued(previous, state)).or(state);

        last.set(some(continued));

        return store.sources()
                    .record(Source.TEMPER, continued)
                    .map(_ -> continued);
    }
}
