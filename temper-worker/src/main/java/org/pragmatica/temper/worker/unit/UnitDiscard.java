package org.pragmatica.temper.worker.unit;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.manifest.PomProbe;
import org.pragmatica.temper.worker.workspace.Trees;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;


/// Removes everything one attempt of a unit left on this host (m4-design §4.4, §4.5): its bb-hold holder is signalled,
/// its container (killed if it runs) and its effective-POM probe container are removed, then its network, then its
/// unit directory. Every step is idempotent (a holder, container, network or directory already gone is not a
/// failure), so a discard that failed midway is repeated whole; the first failure fails it, and nothing claims the
/// attempt is gone until the directory is.
///
/// The caller decides that the unit here IS the attempt to discard ([UnitStarter]'s adoption guard, the worker's
/// `DiscardUnit` handler): the names are per unit, not per attempt.
///
/// The registry forgets the attempt first (its timer, exit watch, hold, held container and cancel, [UnitRegistry#forget]),
/// so its exit watcher, woken by the container's removal, finds it no longer owns the unit and writes and sends
/// nothing ([UnitWatcher]); an exit processing it had already started is waited for, so nothing of it lands in a
/// directory a later attempt writes.
public record UnitDiscard(UnitFiles files,
                          DockerCli docker,
                          HolderProbe holders,
                          PomProbe pomProbe,
                          UnitRegistry registry) {
    private static final Logger LOG = LoggerFactory.getLogger(UnitDiscard.class);

    public static UnitDiscard unitDiscard(UnitFiles files,
                                          DockerCli docker,
                                          HolderProbe holders,
                                          PomProbe pomProbe,
                                          UnitRegistry registry) {
        return new UnitDiscard(files, docker, holders, pomProbe, registry);
    }

    /// Removes what `attempt` of the unit left. The unit directory's attempt is read again just before it is deleted:
    /// a directory that names another attempt by then is left (and logged), so this discard never deletes what a
    /// later attempt wrote. An unreadable `attempt` cannot name another one, and the directory goes.
    /// The attempt's exit processing is awaited first, only so that it is over before its directory goes; its outcome
    /// concerns an attempt the controller no longer wants, so a failure is dropped ([UnitRegistry#forget]; the
    /// processing logs its own failure).
    public Promise<Unit> discard(UnitRef ref, int attempt) {
        return registry.forget(ref, attempt)
                       .flatMap(_ -> containersRemoved(ref))
                       .flatMap(_ -> docker.networkRemove(ref.networkName()))
                       .flatMap(_ -> directoryRemoved(ref, attempt));
    }

    private Promise<Unit> directoryRemoved(UnitRef ref, int attempt) {
        return files.heldAttempt(ref)
                    .or(none())
                    .filter(held -> held != attempt)
                    .map(held -> directoryKept(ref, attempt, held))
                    .or(() -> Trees.deleteRecursively(files.paths().unitDir(ref)));
    }

    private static Promise<Unit> directoryKept(UnitRef ref, int attempt, int held) {
        LOG.warn("unit {}: discard of attempt {} keeps the unit directory: it now holds attempt {}",
                 ref.containerName(),
                 attempt,
                 held);

        return Promise.unitPromise();
    }

    /// The holder first (a live bb-hold would start the container again once granted), then the containers.
    private Promise<Unit> containersRemoved(UnitRef ref) {
        return holders.terminate(ref)
                      .async()
                      .flatMap(_ -> docker.remove(ref.containerName()))
                      .flatMap(_ -> pomProbe.removed(ref));
    }
}
