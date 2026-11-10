package org.pragmatica.temper.worker.gc;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.unit.UnitFiles;
import org.pragmatica.temper.worker.unit.UnitRefs;
import org.pragmatica.temper.worker.unit.UnitRegistry;
import org.pragmatica.temper.worker.workspace.Trees;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Host checkout GC (m1-design §4.3): `GcJobs{jobs}` → `GcDone{deleted, kept}`. A job directory
/// (`~/.temper/jobs/<job>`) is deleted only when all of these hold, checked in this order:
/// 1. no unit of the job is known to this worker (starting, holding, held, watched, timed or being collected); the
///    registry then also drops the job's attempt owner tags ([UnitRegistry#released]);
/// 2. `docker ps -a --filter label=temper.job=<job>` is empty (units, the effective-POM probe, and any other
///    container labelled with the job);
/// 3. every unit directory has an `rc`, or no marker (a unit that never ran).
///
/// Deletion walks the tree WITHOUT following symlinks (a symlink is removed as a link; its target is never
/// touched). Before anything is deleted, every directory of the tree must be writable and searchable by the worker:
/// a directory it cannot empty (root-owned, left by a sibling container) keeps the whole job, with the reason,
/// rather than half-deleting it. A delete that still fails midway (a race, a file appearing) is reported as kept
/// with "partially deleted", which is what it is. A job directory that does not exist is reported deleted.
///
/// **The gap between check 1 and the deletion is not closed here:** nothing stops a unit of the job from starting
/// after `knows` and before the tree is gone. It is closed by the caller's precondition: the controller sends
/// `GcJobs` only for terminal jobs past their checkout retention (m1-design §4.1, rule "every non-terminal job
/// produces no action"), and a terminal job never starts a unit again. The checks protect what a worker may still
/// be doing for such a job (collecting, a straggler container), not a new start.
///
/// After a `GcJobs` that deleted a job, the orphan network sweep runs ([NetworkSweep]): the unit networks a lost
/// report ack or `DiscardUnit` left behind go with the checkout they belonged to.
public record JobGc(WorkerPaths paths,
                    UnitFiles files,
                    DockerCli docker,
                    UnitRegistry registry,
                    NetworkSweep networks) {
    private static final Logger LOG = LoggerFactory.getLogger(JobGc.class);
    /// Jobs checked and deleted at once.
    public static final int PARALLELISM = 4;

    public static JobGc jobGc(WorkerPaths paths,
                              UnitFiles files,
                              DockerCli docker,
                              UnitRegistry registry,
                              NetworkSweep networks) {
        return new JobGc(paths, files, docker, registry, networks);
    }

    /// Fork-Join over batches of at most `PARALLELISM` jobs, one batch after another: each job's checks and deletion
    /// touch only its own directory and containers, and the bound keeps a long `GcJobs` from running a docker call
    /// and a tree walk per job all at once.
    public Promise<Message.GcDone> collect(Message.GcJobs request) {
        return collectedFrom(request.jobs(),
                             List.of()).map(JobGc::gcDone)
                            .flatMap(this::networksSwept);
    }

    /// A deleted job is the moment its unit networks are surely unwanted: the orphan sweep ([NetworkSweep]) runs
    /// once per `GcJobs` that deleted anything, and never fails the reply.
    private Promise<Message.GcDone> networksSwept(Message.GcDone done) {
        return done.deleted()
                   .isEmpty()
               ? Promise.success(done)
               : networks.sweep()
                         .map(_ -> done);
    }

    private Promise<List<Outcome>> collectedFrom(List<Long> jobs, List<Outcome> done) {
        var now = Math.min(PARALLELISM, jobs.size());

        return jobs.isEmpty()
               ? Promise.success(done)
               : batch(jobs.subList(0, now)).flatMap(outcomes -> collectedFrom(jobs.subList(now, jobs.size()),
                                                                               concatenated(done, outcomes)));
    }

    private Promise<List<Outcome>> batch(List<Long> jobs) {
        return Promise.allOf(jobs.stream().map(this::outcomeLogged).toList()).map(JobGc::settled);
    }

    private static List<Outcome> settled(List<Result<Outcome>> outcomes) {
        return outcomes.stream()
                       .flatMap(Result::stream)
                       .toList();
    }

    private static List<Outcome> concatenated(List<Outcome> first, List<Outcome> second) {
        return Stream.concat(first.stream(),
                             second.stream())
                     .toList();
    }

    /// One job: deleted (`none`) or kept with its reason.
    public Promise<Option<Message.GcDone.Kept>> collected(long job) {
        return JobId.jobId(job)
                    .async()
                    .flatMap(id -> collected(job, id));
    }

    /// FER (degrade forward): a check that fails (docker unreachable, an unreadable `rc`) keeps the job with the
    /// failure as its reason, so nothing is deleted on a failed check and the controller sees why; the next GC
    /// tick asks again.
    private Promise<Outcome> outcomeLogged(Long job) {
        return collected(job).map(kept -> Outcome.outcome(job, kept))
                        .onFailure(cause -> LOG.warn("job {}: checkout GC check failed: {}", job, cause))
                        .recover(cause -> Outcome.outcome(job,
                                                          some(Message.GcDone.Kept.kept(job,
                                                                                        "check failed: " + cause.message()))));
    }

    private static Message.GcDone gcDone(List<Outcome> settled) {
        return new Message.GcDone(settled.stream()
                                         .filter(outcome -> outcome.kept()
                                                                   .isEmpty())
                                         .map(Outcome::job)
                                         .toList(),
                                  settled.stream().flatMap(outcome -> outcome.kept()
                                                                             .stream()).toList());
    }

    private Promise<Option<Message.GcDone.Kept>> collected(long job, JobId id) {
        return registry.released(id)
               ? docker.idsByJobLabel(id.toString())
                       .flatMap(containers -> containersChecked(job, id, containers))
               : Promise.success(kept(job, "a unit of the job is still active on the worker"));
    }

    private Promise<Option<Message.GcDone.Kept>> containersChecked(long job, JobId id, Set<String> containers) {
        return containers.isEmpty()
               ? unitWithoutRc(id).async()
                              .flatMap(unfinished -> unitsChecked(job, id, unfinished))
               : Promise.success(kept(job, containers.size() + " container(s) labelled temper.job=" + id));
    }

    private Promise<Option<Message.GcDone.Kept>> unitsChecked(long job, JobId id, Option<String> unfinished) {
        return unfinished.map(unit -> Promise.success(kept(job, "unit " + unit + " has a marker and no rc")))
                         .or(() -> deleted(job,
                                           paths.jobDir(id)));
    }

    private static Option<Message.GcDone.Kept> kept(long job, String reason) {
        return some(Message.GcDone.Kept.kept(job, reason));
    }

    /// The first unit directory with a marker and no `rc`.
    private Result<Option<String>> unitWithoutRc(JobId id) {
        var units = paths.unitsDir(id);

        return Files.isDirectory(units, LinkOption.NOFOLLOW_LINKS)
               ? FileOps.list(units)
                        .mapError(origin -> WorkerError.Io.FACTORY.apply("list " + units, origin))
                        .map(JobGc::unitNames)
                        .map(names -> firstUnfinished(id, names))
               : success(none());
    }

    private Option<String> firstUnfinished(JobId id, List<String> names) {
        return Option.from(names.stream().filter(name -> isUnfinished(id, name)).findFirst());
    }

    private static List<String> unitNames(List<Path> entries) {
        return entries.stream()
                      .filter(JobGc::isDirectory)
                      .map(JobGc::fileName)
                      .toList();
    }

    private static boolean isDirectory(Path entry) {
        return Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
    }

    private static String fileName(Path entry) {
        return entry.getFileName()
                    .toString();
    }

    private boolean isUnfinished(JobId id, String unit) {
        return UnitRefs.unitRef(id.value(),
                                unit)
                       .map(this::isUnfinished)
                       .or(false);
    }

    private boolean isUnfinished(UnitRef ref) {
        return files.exists(ref, UnitFile.MARKER) && !files.exists(ref, UnitFile.RC);
    }

    // ---- deletion ------------------------------------------------------------------------------------------
    private static Promise<Option<Message.GcDone.Kept>> deleted(long job, Path directory) {
        return Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
               ? blocker(directory).async()
                        .flatMap(blocked -> blockedOrDeleted(job, directory, blocked))
               : Promise.success(none());
    }

    private static Promise<Option<Message.GcDone.Kept>> blockedOrDeleted(long job,
                                                                         Path directory,
                                                                         Option<Path> blocked) {
        return blocked.map(path -> Promise.success(kept(job, "cannot delete " + path + ": not writable")))
                      .or(() -> deletedTree(job, directory));
    }

    /// The first directory of the tree the worker could not empty, or none. A tree that cannot be walked fails the
    /// check (the job is then kept as "check failed" with the walk's failure, and nothing is deleted).
    static Result<Option<Path>> blocker(Path root) {
        var visitor = new UnwritableVisitor(root);

        return Result.lift(thrown -> WorkerError.Io.FACTORY.apply("walk " + root,
                                                                  Causes.fromThrowable(thrown)),
                           () -> Files.walkFileTree(root, visitor))
                     .map(_ -> visitor.first());
    }

    /// FER (degrade forward): a delete that fails midway is not undone (what is gone is gone); the job is reported
    /// kept as "partially deleted" with the failure, which is what it is, and the next GC tick deletes the rest.
    private static Promise<Option<Message.GcDone.Kept>> deletedTree(long job, Path directory) {
        return Trees.deleteRecursively(directory)
                    .map(_ -> Option.<Message.GcDone.Kept> none())
                    .onFailure(cause -> LOG.warn("job {}: checkout partially deleted: {}", job, cause))
                    .recover(cause -> kept(job,
                                           "partially deleted: " + cause.message()));
    }

    record Outcome(long job, Option<Message.GcDone.Kept> kept) {
        static Outcome outcome(long job, Option<Message.GcDone.Kept> kept) {
            return new Outcome(job, kept);
        }
    }

    /// Stops at the first directory the worker cannot empty (not writable or not searchable).
    static final class UnwritableVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final List<Path> found = new ArrayList<>();

        UnwritableVisitor(Path root) {
            this.root = root;
        }

        Option<Path> first() {
            return Option.from(found.stream().findFirst());
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (Files.isWritable(directory) && Files.isExecutable(directory)) {
                return FileVisitResult.CONTINUE;
            }

            found.add(root.relativize(directory));

            return FileVisitResult.TERMINATE;
        }
    }
}
