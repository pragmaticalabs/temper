package org.pragmatica.temper.controller.evidence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.temper.controller.flow.Batches;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.store.api.ResultStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// `jobs/<job>/result.json` (m1-design §3.3): the job's result record, byte-identical to the stored `results.record`.
///
/// - [#write] writes a forced sibling and renames it atomically, then forces the directory.
/// - [#written] writes it from the store right after a completion finished the job.
/// - [#sweep] (at controller start and every retention tick) writes the file of every result whose job finished in
///   the last [#SWEEP_WINDOW] and has none, which closes the window between the commit and the write, a controller
///   crash inside it included.
///
/// **Guarantee:** every result row has a byte-identical `result.json` within one sweep interval of its commit, as
/// long as the evidence filesystem is writable. A failed write is logged and retried by the next sweep. `result.json`
/// is not evidence: retention never deletes it [G] (Q-M1-11).
public record ResultFiles(EvidenceLayout layout, ResultStore results, Supplier<Instant> clock) {
    private static final Logger log = LoggerFactory.getLogger(ResultFiles.class);
    public static final Duration SWEEP_WINDOW = Duration.ofDays(90);
    private static final int SWEEP_BATCH = 16;

    public static ResultFiles resultFiles(EvidenceLayout layout, ResultStore results, Supplier<Instant> clock) {
        return new ResultFiles(layout, results, clock);
    }

    /// Writes `recordJson` as the job's `result.json`.
    public Result<Unit> write(JobId job, String recordJson) {
        return published(layout.resultJson(job), recordJson.getBytes(StandardCharsets.UTF_8));
    }

    /// A sibling written, forced and renamed over `target`, then the directory forced ([Tarballs#moved]).
    private static Result<Unit> published(Path target, byte[] bytes) {
        var partial = EvidenceLayout.partial(target);

        return Tarballs.directoriesFor(target)
                       .flatMap(_ -> written(partial, bytes))
                       .flatMap(_ -> Tarballs.moved(partial, target));
    }

    private static Result<Unit> written(Path partial, byte[] bytes) {
        return FileOps.writeBytes(partial, bytes).mapError(cause -> EvidenceError.Io.FACTORY.apply("write " + partial,
                                                                                                   cause));
    }

    /// The job's file from its stored record; nothing when the job has no result yet.
    public Promise<Unit> written(JobId job) {
        return results.recordJson(job)
                      .flatMap(record -> writtenIfPresent(job, record));
    }

    /// Writes every missing file of the window; answers how many were written. A job whose write fails is logged
    /// and counted out (FER: the next sweep tries it again; the record in the database is unaffected).
    public Promise<Integer> sweep() {
        return results.finishedSince(windowStart())
                      .map(this::withoutFile)
                      .flatMap(missing -> Batches.run(missing, SWEEP_BATCH, this::logged))
                      .map(ResultFiles::succeeded);
    }

    /// Fire-and-forget after a finishing commit: the write logs its own failure, and the sweep is the backstop.
    public Unit writeLater(JobId job) {
        logged(job);

        return unit();
    }

    private Promise<Unit> writtenIfPresent(JobId job, Option<String> record) {
        return record.fold(Promise::unitPromise, json -> write(job, json).async());
    }

    private Instant windowStart() {
        return clock.get()
                    .minus(SWEEP_WINDOW);
    }

    private List<JobId> withoutFile(List<JobId> jobs) {
        return jobs.stream()
                   .filter(this::hasNoFile)
                   .toList();
    }

    private boolean hasNoFile(JobId job) {
        return ! FileOps.exists(layout.resultJson(job));
    }

    private Promise<Unit> logged(JobId job) {
        return written(job).onFailure(cause -> log.warn("result.json of job {} not written: {}", job, cause.message()));
    }

    private static int succeeded(List<Result<Unit>> results) {
        return (int) results.stream()
                            .filter(Result::isSuccess)
                            .count();
    }
}
