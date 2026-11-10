package org.pragmatica.temper.worker.unit;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Unit timeouts that survive an ssh drop (spec §4.3, m0-design §7.5).
///
/// `arm` computes `deadline = start + timeout` from `.temper/start` (the marker if `start` is missing) and
/// schedules `fire` after `max(0, deadline - now)`. It is armed at start and again on `Reattach` with the
/// controller's authoritative timeout, so a deadline that passed while no worker was attached fires at once.
///
/// **Honest guarantee:** the bound is enforced while a worker is attached; with none attached, enforcement is
/// deferred to the next re-attach and the bound is not reset (risk R6). The kill follows the dumps, which run one
/// JVM at a time, each bounded by `jcmdCall` (60 s): with N JVMs the kill can come up to N × 60 s after the
/// deadline (typically three JVMs that answer in about a second each).
///
/// `fire`: write `killed=timeout` (first writer wins), dump every JVM, then `docker kill`. The exit path then
/// records rc 137 with `killedBy=timeout`, which is `FAILED(timeout)`.
public record UnitTimer(UnitFiles files,
                        WorkerPaths paths,
                        DockerCli docker,
                        ThreadDumper dumper,
                        Timers timers,
                        InstantSource clock,
                        UnitRegistry registry) {
    private static final Logger LOG = LoggerFactory.getLogger(UnitTimer.class);
    public static final String TIMEOUT = "timeout";

    public static UnitTimer unitTimer(UnitFiles files,
                                      WorkerPaths paths,
                                      DockerCli docker,
                                      ThreadDumper dumper,
                                      Timers timers,
                                      InstantSource clock,
                                      UnitRegistry registry) {
        return new UnitTimer(files, paths, docker, dumper, timers, clock, registry);
    }

    /// Arms (or re-arms, replacing the previous timer) and returns the deadline.
    public Result<Instant> arm(UnitRef ref, Duration timeout) {
        return startNanos(ref).flatMap(start -> EpochNanos.plus(start, timeout))
                         .map(EpochNanos::instant)
                         .map(deadline -> scheduled(ref, deadline));
    }

    /// Fires the timeout now, but only at a container that is still running: a unit that already has an `rc`,
    /// or whose container exited on its own while no worker was attached, ran within its own life and is left
    /// alone (its exit is collected normally, without `killed=timeout`). `docker kill` always runs for a running
    /// container (idempotent; a previous worker may have died between dump and kill); the dumps run only when
    /// this timeout is the recorded kill cause.
    public Promise<Unit> fire(UnitRef ref) {
        return files.exists(ref, UnitFile.RC)
               ? Promise.success(unit())
               : container(ref).flatMap(docker::status)
                          .flatMap(status -> firedAt(ref, status));
    }

    private Promise<Unit> firedAt(UnitRef ref, ContainerStatus status) {
        return status == ContainerStatus.RUNNING
               ? killedForTimeout(ref)
               : Promise.success(unit());
    }

    private Instant scheduled(UnitRef ref, Instant deadline) {
        var delay = Duration.between(clock.instant(), deadline);

        registry.replaceTimer(ref,
                              timers.schedule(() -> fireLogged(ref),
                                              TimeSpan.fromDuration(delay.isNegative()
                                                                    ? Duration.ZERO
                                                                    : delay)));

        return deadline;
    }

    /// The scheduler's `Runnable`: fire-and-forget by contract, so the outcome can only be logged.
    @Contract
    private void fireLogged(UnitRef ref) {
        fire(ref).onSuccess(_ -> LOG.info("unit {} timed out: dumped and killed",
                                          ref.containerName()))
            .onFailure(cause -> LOG.error("unit {} timeout handling failed: {}",
                                          ref.containerName(),
                                          cause));
    }

    private Promise<Unit> killedForTimeout(UnitRef ref) {
        return files.storeOnce(ref, UnitFile.KILLED, TIMEOUT)
                    .flatMap(_ -> files.text(ref, UnitFile.KILLED))
                    .map(UnitTimer::isTimeout)
                    .async()
                    .flatMap(ours -> dumpedAndKilled(ref, ours));
    }

    private static boolean isTimeout(Option<String> recorded) {
        return recorded.map(String::trim)
                       .filter(TIMEOUT::equals)
                       .isPresent();
    }

    private Promise<Unit> dumpedAndKilled(UnitRef ref, boolean dump) {
        return container(ref).flatMap(container -> dumpedAndKilled(ref, container, dump));
    }

    private Promise<Unit> dumpedAndKilled(UnitRef ref, String container, boolean dump) {
        return dump
               ? dumper.dumpAll(container,
                                paths.threadDumpsDir(ref))
                       .flatMap(_ -> docker.kill(container))
               : docker.kill(container);
    }

    private Promise<String> container(UnitRef ref) {
        return files.container(ref)
                    .async();
    }

    private Result<Long> startNanos(UnitRef ref) {
        return files.nanos(ref, UnitFile.START)
                    .flatMap(start -> start.map(Result::success)
                                           .or(() -> markerNanos(ref)));
    }

    private Result<Long> markerNanos(UnitRef ref) {
        return files.nanos(ref, UnitFile.MARKER)
                    .flatMap(marker -> marker.toResult(WorkerError.MissingState.FACTORY.apply(files.path(ref,
                                                                                                         UnitFile.MARKER))));
    }
}
