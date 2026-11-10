package org.pragmatica.temper.worker.workspace;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.worker.Serial;
import org.pragmatica.temper.worker.InFlight;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.WorkerPaths;

import static org.pragmatica.lang.Unit.unit;


/// `PrepareJob` (m0-design §7.3): mirror, checkout, seed copy, seed verification, container home. Idempotent:
/// each step skips work that is already done, and the seed verification runs every time, before any unit of
/// the job can start. Returns the workspace path.
@FunctionalInterface
public interface WorkspacePreparer {
    Promise<Path> prepare(JobId job, Sha sha, String originUrl);

    /// One preparation per job at a time: a `PrepareJob` for a job whose preparation is still running (the
    /// controller retried after its 15-minute window) joins it instead of racing the workspace delete and seed copy.
    /// The seed copy has no bound of its own: a copy cannot be interrupted part-way safely, and joining the
    /// in-flight one is what keeps a retry from colliding with it; the controller's request timeout bounds the wait.
    static WorkspacePreparer workspacePreparer(WorkerPaths paths, Git git, Seeder seeder) {
        var mirrorQueue = Serial.serial();
        var inFlight = new ConcurrentHashMap<JobId, Promise<Path>>();

        return (job, sha, originUrl) -> shared(inFlight,
                                               job,
                                               () -> prepared(paths, git, seeder, mirrorQueue, job, sha, originUrl));
    }

    private static Promise<Path> shared(Map<JobId, Promise<Path>> inFlight,
                                        JobId job,
                                        Supplier<Promise<Path>> prepare) {
        return InFlight.joined(inFlight, job, prepare);
    }

    private static Promise<Path> prepared(WorkerPaths paths,
                                          Git git,
                                          Seeder seeder,
                                          Serial mirrorQueue,
                                          JobId job,
                                          Sha sha,
                                          String originUrl) {
        return mirrorQueue.run(() -> mirror(paths, git, sha, originUrl))
                          .flatMap(_ -> checkout(paths, git, job, sha))
                          .flatMap(_ -> seed(paths, seeder, job))
                          .flatMap(_ -> SeedVerifier.verify(paths.workspaceRepo(job)))
                          .flatMap(_ -> home(paths, job));
    }

    /// Step 1: the worker's own bare mirror holds `sha`. A present commit needs no network; otherwise the mirror
    /// is created (first use) or fetched, and the commit must then be present. Serialised by the caller.
    private static Promise<Unit> mirror(WorkerPaths paths, Git git, Sha sha, String originUrl) {
        return git.hasCommit(paths.mirror(),
                             sha)
                  .flatMap(present -> unlessPresent(present,
                                                    () -> refreshed(paths, git, sha, originUrl)));
    }

    private static Promise<Unit> unlessPresent(boolean present, Supplier<Promise<Unit>> refresh) {
        return present
               ? Promise.success(unit())
               : refresh.get();
    }

    private static Promise<Unit> refreshed(WorkerPaths paths, Git git, Sha sha, String originUrl) {
        return mirrorUpdated(paths, git, originUrl).flatMap(_ -> git.hasCommit(paths.mirror(),
                                                                               sha))
                            .flatMap(present -> unlessPresent(present,
                                                              () -> WorkerError.MissingCommit.FACTORY.apply(sha.value()).promise()));
    }

    /// Fetch into an existing mirror, its `origin` first pointed at the job's `originUrl` (F1); on first use clone
    /// into a sibling and rename it into place, so a broken first clone never looks like a mirror.
    private static Promise<Unit> mirrorUpdated(WorkerPaths paths, Git git, String originUrl) {
        var mirror = paths.mirror();
        var partial = mirror.resolveSibling(mirror.getFileName() + ".tmp");

        return FileOps.isDirectory(mirror)
               ? git.originPointedAt(mirror, originUrl)
                    .flatMap(_ -> git.fetch(mirror))
               : Trees.deleteRecursively(partial)
                      .flatMap(_ -> git.cloneMirror(originUrl, partial))
                      .flatMap(_ -> moved(partial, mirror));
    }

    /// Step 2: clone the workspace from the mirror unless `ws/.git` exists (a half-made workspace without it is
    /// deleted first), then check out `sha` detached.
    private static Promise<Unit> checkout(WorkerPaths paths, Git git, JobId job, Sha sha) {
        var workspace = paths.workspace(job);

        return cloned(paths, git, workspace).flatMap(_ -> git.checkout(workspace, sha));
    }

    private static Promise<Unit> cloned(WorkerPaths paths, Git git, Path workspace) {
        return FileOps.isDirectory(workspace.resolve(".git"))
               ? Promise.success(unit())
               : Trees.deleteRecursively(workspace).flatMap(_ -> git.cloneWorkspace(paths.mirror(), workspace));
    }

    /// Step 3: copy the host seed into `ws/.m2-local` unless it exists, through a sibling and an atomic rename.
    private static Promise<Unit> seed(WorkerPaths paths, Seeder seeder, JobId job) {
        var repository = paths.workspaceRepo(job);

        return FileOps.isDirectory(repository)
               ? Promise.success(unit())
               : copiedSeed(paths, seeder, repository);
    }

    private static Promise<Unit> copiedSeed(WorkerPaths paths, Seeder seeder, Path repository) {
        var partial = repository.resolveSibling(repository.getFileName() + ".tmp");

        return FileOps.isDirectory(paths.seed())
               ? Trees.deleteRecursively(partial)
                      .flatMap(_ -> seeder.copy(paths.seed(),
                                                partial))
                      .flatMap(_ -> moved(partial, repository))
               : WorkerError.General.SEED_MISSING.promise();
    }

    /// Step 5: the container `HOME`; returns the workspace.
    private static Promise<Path> home(WorkerPaths paths, JobId job) {
        return FileOps.createDirectories(paths.workspaceHome(job))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("create " + paths.workspaceHome(job),
                                                                       origin))
                      .map(_ -> paths.workspace(job))
                      .async();
    }

    private static Promise<Unit> moved(Path from, Path to) {
        return FileOps.moveAtomic(from, to)
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("rename " + from + " to " + to, origin))
                      .mapToUnit()
                      .async();
    }
}
