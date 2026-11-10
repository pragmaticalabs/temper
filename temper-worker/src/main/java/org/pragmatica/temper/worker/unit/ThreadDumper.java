package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.docker.DockerCli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// Thread dumps of every JVM in a unit container, taken before a timeout kill (spec §4.3, m0-design §7.5): list the
/// JVMs, then `docker exec <c> jcmd <pid> Thread.print` per pid, one at a time, each bounded by `perCall`. A dump is
/// written to `threaddumps/<pid>.txt`; a dump that fails is written as `threaddumps/<pid>.err` (and a failed
/// listing as `threaddumps/jvms.err`), so the evidence always says what was attempted.
///
/// **Deviation from m0-design §7.5 (`jcmd -l`):** the listing is `RunSteps --jvms` from the runner image. The unit
/// runs as the host uid, which has no passwd entry in the image, so its JVMs create no `hsperfdata` directory and
/// `jcmd -l` lists none of them (verified on the runner image); attaching by pid is unaffected.
public record ThreadDumper(DockerCli docker, TimeSpan perCall) {
    private static final Logger LOG = LoggerFactory.getLogger(ThreadDumper.class);
    public static final String LISTING_ERROR = "jvms.err";
    /// The JVM listing, in `jcmd -l` shape (parsed by [JcmdList]).
    public static final List<String> LIST_JVMS = List.of("java", "-cp", "/opt/temper", "RunSteps", "--jvms");

    public static ThreadDumper threadDumper(DockerCli docker, TimeSpan perCall) {
        return new ThreadDumper(docker, perCall);
    }

    /// Dumps every JVM; completes when every attempt is recorded. Never fails: each failure becomes an `.err`
    /// file (FER, degrade forward: the kill that follows must not depend on the dumps succeeding).
    public Promise<Unit> dumpAll(String container, Path directory) {
        return created(directory).onFailure(cause -> LOG.error("thread dump directory {} not created; dumps are attempted anyway: {}",
                                                               directory,
                                                               cause))
                      .fold(_ -> listedAndDumped(container, directory));
    }

    private Promise<Unit> listedAndDumped(String container, Path directory) {
        return docker.exec(container, LIST_JVMS, perCall)
                     .fold(listing -> dumpListed(container, directory, listing));
    }

    private static Promise<Unit> created(Path directory) {
        return FileOps.createDirectories(directory)
                      .mapToUnit()
                      .async();
    }

    private Promise<Unit> dumpListed(String container, Path directory, Result<Completed> listing) {
        return listing.flatMap(ThreadDumper::listed)
                      .fold(cause -> Promise.success(recorded(directory.resolve(LISTING_ERROR),
                                                              cause.message())),
                            pids -> dumpEach(container, directory, pids));
    }

    private static Result<List<Long>> listed(Completed completed) {
        return completed.rc() == 0
               ? success(JcmdList.pids(completed.stdoutText()))
               : WorkerError.UnexpectedOutput.FACTORY.apply("RunSteps --jvms",
                                                            "rc " + completed.rc() + ": " + completed.stderrText())
                                                     .result();
    }

    private Promise<Unit> dumpEach(String container, Path directory, List<Long> pids) {
        return pids.stream()
                   .reduce(Promise.success(unit()),
                           (previous, pid) -> previous.fold(_ -> dumpOne(container, directory, pid)),
                           (first, second) -> first.fold(_ -> second));
    }

    private Promise<Unit> dumpOne(String container, Path directory, long pid) {
        return docker.exec(container,
                           List.of("jcmd",
                                   Long.toString(pid),
                                   "Thread.print"),
                           perCall)
                     .fold(result -> Promise.success(written(directory, pid, result)));
    }

    private static Unit written(Path directory, long pid, Result<Completed> result) {
        return result.fold(cause -> recorded(directory.resolve(pid + ".err"), cause.message()),
                           completed -> writtenDump(directory, pid, completed));
    }

    private static Unit writtenDump(Path directory, long pid, Completed completed) {
        return completed.rc() == 0
               ? recorded(directory.resolve(pid + ".txt"), completed.stdoutText())
               : recorded(directory.resolve(pid + ".err"),
                          "jcmd exited " + completed.rc() + "\n" + completed.stderrText() + completed.stdoutText());
    }

    /// FER (degrade forward): if even the evidence file cannot be written, that one attempt is missing from the
    /// evidence; the kill still follows, and the kill is what the timeout guarantees.
    private static Unit recorded(Path file, String text) {
        return UnitFiles.storeAtomic(file, text).or(unit());
    }
}
