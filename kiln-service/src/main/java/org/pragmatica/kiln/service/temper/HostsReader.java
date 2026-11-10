package org.pragmatica.kiln.service.temper;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.temper.HostsRead;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The `hosts` loop (every 60 s): `GET /api/v1/fleet/alarms` with kiln's existing temper
/// url and read token, decoded tolerantly ([HostsRead]). Health uses kiln's existing vocabulary: [SourceState.Ok] on a
/// 200 that decodes; `TEMPER-DOWN(<reason>)` on a connect failure, timeout or any other status (401, 403 and a 404 from
/// a controller without the route included); `TEMPER-SCHEMA(<field>)` on a missing required field. The last reply that
/// decoded is held in memory only (G-S1-8), with the time it was read, and survives a failed read so the panel can show
/// it marked stale. The reader emits no kiln event in any state: the controller is the only emitter.
///
/// A tick is single-flight and deadline-bounded as [TemperReader]'s.
public record HostsReader(TemperHttp http,
                          SourceStore sources,
                          Supplier<Instant> clock,
                          Duration deadline,
                          AtomicReference<Option<SourceState>> last,
                          AtomicReference<Option<Observed<HostsRead.Answered>>> reply,
                          AtomicBoolean running,
                          AtomicLong skipped) {
    public static final Duration PERIOD = Duration.ofSeconds(60);
    public static final Duration DEADLINE = Duration.ofMinutes(1);

    public static HostsReader hostsReader(TemperHttp http, SourceStore sources, Supplier<Instant> clock) {
        return hostsReader(http, sources, clock, DEADLINE);
    }

    public static HostsReader hostsReader(TemperHttp http,
                                          SourceStore sources,
                                          Supplier<Instant> clock,
                                          Duration deadline) {
        return new HostsReader(http,
                               sources,
                               clock,
                               deadline,
                               new AtomicReference<>(none()),
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

    /// The last reply that decoded, with the time it was read; absent until the first (`never`).
    public Option<Observed<HostsRead.Answered>> lastReply() {
        return reply.get();
    }

    private Option<SourceState> skippedTick() {
        skipped.incrementAndGet();

        return none();
    }

    /// One cycle now, bypassing the single-flight guard. Every exit settles: a synchronous throw fails it, and past the
    /// deadline it reads `TEMPER-DOWN(cycle timed out)`.
    public Promise<SourceState> cycle() {
        var at = clock.get();

        return Result.lift(Causes::fromThrowable, http::fleetAlarms)
                     .async()
                     .flatMap(read -> read)
                     .timeout(TimeSpan.fromDuration(deadline))
                     .fold(result -> judged(result, at))
                     .flatMap(this::recorded);
    }

    private Promise<SourceState> judged(Result<HostsRead> result, Instant at) {
        return result.fold(cause -> down(cause, at), read -> Promise.success(answered(read, at)));
    }

    /// Only temper's failures and the deadline are `TEMPER-DOWN`; any other fails the cycle as itself.
    private static Promise<SourceState> down(Cause cause, Instant at) {
        return switch (cause) {
            case CoreError.Timeout _ -> Promise.success(new SourceState.TemperDown(TemperReadError.reason(TemperReadError.General.TIMED_OUT),
                                                                                   at));
            case TemperReadError error -> Promise.success(new SourceState.TemperDown(TemperReadError.reason(error), at));
            default -> cause.promise();
        };
    }

    private SourceState answered(HostsRead read, Instant at) {
        return switch (read) {
            case HostsRead.Answered answered -> held(answered, at);
            case HostsRead.SchemaBroken broken -> new SourceState.TemperSchema(broken.field(), at);
        };
    }

    private SourceState held(HostsRead.Answered answered, Instant at) {
        reply.set(some(Observed.observed(answered, at)));

        return new SourceState.Ok(at);
    }

    private Promise<SourceState> recorded(SourceState state) {
        var continued = last.get().map(previous -> SourceState.continued(previous, state)).or(state);

        last.set(some(continued));

        return sources.record(Source.HOSTS, continued)
                      .map(_ -> continued);
    }
}
