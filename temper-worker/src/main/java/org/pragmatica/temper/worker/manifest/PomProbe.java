package org.pragmatica.temper.worker.manifest;

import java.nio.file.Path;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.unit.DockerRunArgs;
import org.pragmatica.temper.worker.unit.UnitLaunch;

import static org.pragmatica.lang.Result.success;


/// The effective-POM step (m1-design §2.1, §2.3): an auxiliary container `temper-<job>-<unit>-pom` runs the
/// planner's `pomArgv` ONLINE (Q-M1-9) before the marker and before any hold, so a broken pom fails fast and
/// outside the port group.
///
/// 1. `docker rm -f` of the probe name (a retried start may find the last attempt's container);
/// 2. `<ws>/.home/.temper` is created and a stale output removed;
/// 3. `docker run --rm …` ([DockerRunArgs#probe]) within `timeout`;
/// 4. rc 0: the XML is moved into `units/<u>/manifest/effective-pom.xml`.
///
/// rc ≠ 0 fails with [WorkerError.EffectivePomFailed] (`manifest: effective-pom rc=<n>`); anything else that keeps
/// the step from producing its XML fails with [WorkerError.EffectivePomNotRun]. Either way the build does not run
/// (Q-M1-7).
public record PomProbe(DockerCli docker, WorkerPaths paths, TimeSpan timeout) {
    public static PomProbe pomProbe(DockerCli docker, WorkerPaths paths, TimeSpan timeout) {
        return new PomProbe(docker, paths, timeout);
    }

    public Promise<Unit> probe(UnitLaunch launch, ManifestSpec spec) {
        var ref = launch.request().ref();

        return cleared(ref).flatMap(_ -> ran(launch, spec))
                      .flatMap(completed -> succeeded(completed).async())
                      .flatMap(_ -> moved(ref).async())
                      .mapToUnit();
    }

    /// No container and no output of an earlier attempt.
    private Promise<Boolean> cleared(UnitRef ref) {
        return removed(ref).mapError(origin -> WorkerError.EffectivePomNotRun.FACTORY.apply(WorkerError.EffectivePomNotRun.STAGE_REMOVE,
                                                                                            origin))
                      .flatMap(_ -> outputPrepared(ref).async());
    }

    private Promise<Completed> ran(UnitLaunch launch, ManifestSpec spec) {
        return docker.runAux(DockerRunArgs.probe(launch,
                                                 spec.pomArgv()),
                             timeout)
                     .mapError(origin -> WorkerError.EffectivePomNotRun.FACTORY.apply(WorkerError.EffectivePomNotRun.STAGE_RUN,
                                                                                      origin));
    }

    /// The probe container's `docker run`, removed again when a start fails after it (BER of a failed start).
    public Promise<Unit> removed(UnitRef ref) {
        return docker.remove(DockerRunArgs.probeName(ref));
    }

    private Result<Boolean> outputPrepared(UnitRef ref) {
        var output = paths.effectivePomOutput(ref);

        return FileOps.createDirectories(output.getParent())
                      .flatMap(_ -> staleOutputRemoved(output))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("prepare " + output, origin))
                      .mapError(origin -> WorkerError.EffectivePomNotRun.FACTORY.apply(WorkerError.EffectivePomNotRun.STAGE_PREPARE,
                                                                                       origin));
    }

    private static Result<Boolean> staleOutputRemoved(Path output) {
        return FileOps.deleteIfExists(output);
    }

    private static Result<Completed> succeeded(Completed completed) {
        return completed.rc() == 0
               ? success(completed)
               : WorkerError.EffectivePomFailed.FACTORY.apply(completed.rc()).result();
    }

    private Result<Path> moved(UnitRef ref) {
        var output = paths.effectivePomOutput(ref);
        var target = paths.effectivePom(ref);

        return FileOps.createDirectories(target.getParent())
                      .flatMap(_ -> FileOps.moveAtomic(output, target))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("move the effective POM " + output, origin))
                      .mapError(origin -> WorkerError.EffectivePomNotRun.FACTORY.apply(WorkerError.EffectivePomNotRun.STAGE_OUTPUT,
                                                                                       origin));
    }
}
