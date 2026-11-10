package org.pragmatica.temper.worker.unit;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.link.Outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// Watches a running unit until its container exits, then processes and reports the exit (m0-design §7.4 step
/// 11 and "On container exit"). One watcher per unit: [UnitRegistry#startWatching] decides who owns it.
///
/// `docker wait` is bounded by `waitBound`; when it ends without an rc (timeout, daemon restart) the container is
/// inspected: still running → wait again after `retryDelay`; exited → collect with the inspected rc; gone with no
/// rc → an unsolicited `Failure{code: lost}` for the unit, which the controller turns into `ERROR(lost)`.
public record UnitWatcher(DockerCli docker,
                          UnitFiles files,
                          UnitRegistry registry,
                          UnitCollector collector,
                          Outbox outbox,
                          TimeSpan waitBound,
                          TimeSpan retryDelay) {
    private static final Logger LOG = LoggerFactory.getLogger(UnitWatcher.class);

    public static UnitWatcher unitWatcher(DockerCli docker,
                                          UnitFiles files,
                                          UnitRegistry registry,
                                          UnitCollector collector,
                                          Outbox outbox,
                                          TimeSpan waitBound,
                                          TimeSpan retryDelay) {
        return new UnitWatcher(docker, files, registry, collector, outbox, waitBound, retryDelay);
    }

    /// Starts watching unless a watcher already exists; returns at once.
    public Unit watch(UnitRef ref, int attempt, String container) {
        return registry.startWatching(ref, attempt, container)
               ? started(Watching.watching(ref, attempt, container))
               : unit();
    }

    /// `Collect` (after the `Ack`): an exit already recorded is (re)sent; a running container gets a watcher,
    /// whose exit processing sends the report; an exited container is collected now; a lost one is reported.
    public Promise<Unit> collectRequested(UnitRef ref, int attempt) {
        return files.exists(ref, UnitFile.RC)
               ? reported(ref, attempt, none())
               : container(ref).flatMap(container -> collectByStatus(ref, attempt, container));
    }

    private Promise<Unit> collectByStatus(UnitRef ref, int attempt, String container) {
        return docker.status(container)
                     .flatMap(status -> collectFor(ref, attempt, container, status));
    }

    private Promise<Unit> collectFor(UnitRef ref, int attempt, String container, ContainerStatus status) {
        return switch (status) {
            case RUNNING -> Promise.success(watch(ref, attempt, container));
            case EXITED -> reported(ref, attempt, none());
            case ABSENT -> lost(ref, container);
        };
    }

    /// The exit processing, shared with any concurrent `Collect`, then sent. The watch ends as a chain step, so a
    /// caller that sees the report sent no longer finds the unit watched (nor listed as running by the heartbeat).
    public Promise<Unit> reported(UnitRef ref, int attempt, Option<Integer> waitedRc) {
        return registry.collection(ref,
                                   attempt,
                                   () -> collector.collect(ref, waitedRc))
                       .flatMap(this::posted)
                       .withResult(_ -> registry.stopWatching(ref));
    }

    private Promise<Unit> posted(Collected collected) {
        return outbox.postAll(collected.messages())
                     .async();
    }

    /// Starts the wait in the background: the watcher's outcome reaches the controller as messages, and a failure
    /// can only be logged here (the unit files let the next `Collect` redo the work).
    private Unit started(Watching watching) {
        waited(watching).onFailure(cause -> LOG.error("unit {}: exit processing failed: {}",
                                                      watching.ref().containerName(),
                                                      cause));

        return unit();
    }

    private Promise<Unit> waited(Watching watching) {
        return docker.waitFor(watching.container(),
                              waitBound)
                     .fold(result -> afterWait(watching, result));
    }

    private Promise<Unit> afterWait(Watching watching, Result<Integer> waited) {
        return waited.fold(cause -> inspected(watching, cause), rc -> ownReported(watching, some(rc)));
    }

    /// The owning watcher's view after a wait that returned no rc.
    /// `docker wait` ended without an rc; the cause is logged before the container decides what comes next.
    private Promise<Unit> inspected(Watching watching, Cause cause) {
        LOG.warn("unit {}: docker wait ended without an rc, inspecting: {}",
                 watching.ref().containerName(),
                 cause);

        return docker.status(watching.container())
                     .flatMap(status -> inspectedFor(watching, status));
    }

    private Promise<Unit> inspectedFor(Watching watching, ContainerStatus status) {
        return switch (status) {
            case RUNNING -> Promise.success(rewaited(watching));
            case EXITED -> ownReported(watching, none());
            case ABSENT -> ownLost(watching);
        };
    }

    /// Still running: a fresh wait starts after `retryDelay` as a new background chain (not a nested one), so a
    /// daemon that keeps failing the wait never grows one promise chain without bound.
    private Unit rewaited(Watching watching) {
        // May run late: the next wait is a detached chain, and nobody waits for it to start.
        Promise.<Unit> promise(retryDelay, Result::unitResult).onSuccessRun(() -> started(watching));

        return unit();
    }

    // ---- a watcher's exit processing is its own attempt's only (protocol v3, m4-design §4.4) ----------------
    /// The exit is processed and sent only while this watcher still owns the unit: the registry still watches its
    /// container (a replaced or discarded attempt's watch is dropped before its container is removed, so the exit
    /// that removal causes always finds it gone), and the unit directory still holds its attempt and its container.
    /// Otherwise the directory may already be the next attempt's: nothing is written there and nothing is sent. The
    /// ownership check and the start of the processing are one step against the registry's forget, which waits for a
    /// processing that started before it ([UnitRegistry#watchedCollection]).
    private Promise<Unit> ownReported(Watching watching, Option<Integer> waitedRc) {
        return ownsDirectory(watching)
               ? registry.watchedCollection(watching.ref(),
                                            watching.attempt(),
                                            watching.container(),
                                            () -> collector.collect(watching.ref(),
                                                                    waitedRc))
                         .map(collection -> postedThenUnwatched(watching, collection))
                         .or(() -> superseded(watching))
               : superseded(watching);
    }

    private Promise<Unit> postedThenUnwatched(Watching watching, Promise<Collected> collection) {
        return collection.flatMap(this::posted)
                         .withResult(_ -> registry.stopWatching(watching.ref(),
                                                                watching.container()));
    }

    /// The unsolicited loss of a watched container, as [#lost], only while this watcher owns the unit.
    private Promise<Unit> ownLost(Watching watching) {
        return registry.watches(watching.ref(), watching.container()) && ownsDirectory(watching)
               ? lost(watching.ref(), watching.container())
               : superseded(watching);
    }

    /// The unit directory names this watcher's attempt and container; an unreadable one is not this watcher's.
    private boolean ownsDirectory(Watching watching) {
        return Result.all(files.attempt(watching.ref()),
                          files.container(watching.ref()))
                     .map((attempt, container) -> attempt == watching.attempt() && container.equals(watching.container()))
                     .or(false);
    }

    private static Promise<Unit> superseded(Watching watching) {
        LOG.info("unit {}: the exit of attempt {} (container {}) is not processed: the attempt was replaced or discarded",
                 watching.ref().containerName(),
                 watching.attempt(),
                 watching.container());

        return Promise.unitPromise();
    }

    /// The unsolicited `Failure{code: lost}` names the unit in its `unit` field (protocol v1 addition, m0-design
    /// §12.7); `detail` keeps the container name for the log. The `Collect` and `Reattach` paths answer a loss as a
    /// reply to their request instead; this unsolicited form only covers a watcher whose container vanished mid-run.
    /// It names the attempt the unit directory holds (protocol v3).
    private Promise<Unit> lost(UnitRef ref, String container) {
        var cause = WorkerError.ContainerLost.FACTORY.apply(container);

        return files.attempt(ref)
                    .flatMap(attempt -> outbox.post(Message.Failure.failure(WorkerError.code(cause),
                                                                            ref.containerName() + ": " + cause.message(),
                                                                            UnitRefDto.unitRefDto(ref, attempt))))
                    .onResultRun(() -> registry.stopWatching(ref, container))
                    .async();
    }

    private Promise<String> container(UnitRef ref) {
        return files.container(ref)
                    .async();
    }

    /// One watcher: the unit, the attempt it watches and that attempt's container.
    record Watching(UnitRef ref, int attempt, String container) {
        static Watching watching(UnitRef ref, int attempt, String container) {
            return new Watching(ref, attempt, container);
        }
    }
}
