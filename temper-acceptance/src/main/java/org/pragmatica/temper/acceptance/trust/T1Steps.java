package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.JobView;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The T1 procedure for one SHA (m1-design §8.1): preflight, snapshot, submit, wait, judge. `root` is one run's
/// evidence directory, `<out>/T1-<date>[.N]`, which the run created itself (N1); each SHA gets `<root>/<sha7>/` with `sha` and `job` files, so a re-run finds them.
///
/// The wait has two bounds (owner ruling, option A): `queue` until the job leaves QUEUED (`--queue-hours`), then
/// `temper wait` bounded by `runBound` (`--run-hours`) from that moment, so a long queue behind higher-priority Gates
/// no longer eats the run's time.
public record T1Steps(Processes processes,
                      TemperCli cli,
                      QueueWait queue,
                      Judging judge,
                      Path root,
                      int priority,
                      TimeSpan runBound) {
    public static final String SHA_FILE = "sha";
    public static final String JOB_FILE = "job";
    public static final int SHORT_SHA = 7;

    /// Step 4 of one finished job: [Judge#judged] in the harness.
    @FunctionalInterface
    public interface Judging {
        Promise<ShaRun.Judged> judged(Snapshot snapshot, String sha, long job);
    }

    public static T1Steps t1Steps(Processes processes,
                                  TemperCli cli,
                                  ControllerApi api,
                                  Judge judge,
                                  Path root,
                                  int priority,
                                  int queueHours,
                                  int runHours) {
        return new T1Steps(processes,
                           cli,
                           QueueWait.queueWait(api::job,
                                               InstantSource.system(),
                                               QueueWait::scheduled,
                                               Duration.ofHours(queueHours)),
                           judge::judged,
                           root,
                           priority,
                           timeSpan(runHours).hours());
    }

    /// Steps 1-4 for `target`; a SHA that fails a step is reported with the failure.
    public Promise<ShaRun> live(T1Args.Target target) {
        var dir = dir(target.sha());

        return Preflight.check(processes,
                               target.sha(),
                               BbCheckout.bbCheckout(target.checkout()))
                        .flatMap(preflight -> afterPreflight(target, dir, preflight));
    }

    private Promise<ShaRun> afterPreflight(T1Args.Target target, Path dir, Preflight preflight) {
        return preflight.comparable()
               ? compared(target, dir, preflight)
               : Promise.success(ShaRun.shaRun(target.sha(), dir, preflight));
    }

    /// Steps 2-4 for a comparable SHA. A SHA whose snapshot, submit, wait or judgement fails is reported as failed
    /// with the cause; the other SHAs still run and the overall verdict cannot pass.
    private Promise<ShaRun> compared(T1Args.Target target, Path dir, Preflight preflight) {
        var snapshot = Snapshot.snapshot(dir);
        var sha = preflight.head().or(target.sha());

        return submitted(target.checkout(),
                         snapshot,
                         sha).flatMap(job -> waitedAndJudged(snapshot,
                                                             sha,
                                                             job,
                                                             some(preflight)))
                        // FER (degrade forward): the failed SHA is reported with its cause; the others still run.
                        .recover(cause -> ShaRun.shaRun(target.sha(),
                                                        dir,
                                                        some(preflight),
                                                        none(),
                                                        cause));
    }

    /// Snapshot first (risk R4), then the Gate; the SHA and the job id are written next to the snapshot.
    private Promise<Long> submitted(Path checkout, Snapshot snapshot, String sha) {
        return snapshot.take(processes,
                             BbCheckout.bbCheckout(checkout))
                       .flatMap(_ -> FileOps.writeString(snapshot.dir().resolve(SHA_FILE),
                                                         sha + "\n")
                                            .async())
                       .flatMap(_ -> cli.submitGate(sha, priority))
                       .flatMap(job -> recordedJob(snapshot, job));
    }

    private Promise<Long> recordedJob(Snapshot snapshot, long job) {
        return FileOps.writeString(snapshot.dir().resolve(JOB_FILE),
                                   job + "\n")
                      .async()
                      .map(_ -> job);
    }

    /// The queue phase, then the run phase, then step 4. Only a job that ended is judged: a wait that timed out
    /// ([TrustError.WaitTimedOut]) is a failure with the state it stopped at, never a judgement of an unfinished job.
    Promise<ShaRun> waitedAndJudged(Snapshot snapshot, String sha, long job, Option<Preflight> preflight) {
        return queue.left(job)
                    .flatMap(_ -> cli.finished(job, runBound))
                    .flatMap(_ -> judge.judged(snapshot, sha, job))
                    .map(judged -> ShaRun.shaRun(sha,
                                                 snapshot.dir(),
                                                 preflight,
                                                 job,
                                                 judged))
                    // FER (degrade forward): a job still waiting at the queue bound is NOT COMPARABLE (capacity); a
                    // wait or judgement that fails otherwise is reported with the job and its cause.
                    .recover(cause -> notJudged(snapshot, sha, job, preflight, cause));
    }

    private static ShaRun notJudged(Snapshot snapshot, String sha, long job, Option<Preflight> preflight, Cause cause) {
        return cause instanceof TrustError.Capacity capacity
               ? ShaRun.shaRun(sha, snapshot.dir(), preflight, job, capacity)
               : ShaRun.shaRun(sha, snapshot.dir(), preflight, some(job), cause);
    }

    /// Step 4 again from a snapshot directory written by a live run (`sha` and `job` files): the verifier's re-run.
    public Promise<ShaRun> rerun(Path dir) {
        var snapshot = Snapshot.snapshot(dir);

        return Result.all(text(dir.resolve(SHA_FILE)),
                          text(dir.resolve(JOB_FILE)).flatMap(HarnessArgs::jobId))
                     .async()
                     .flatMap((sha, job) -> rejudged(snapshot, sha, job));
    }

    /// Step 4 of one snapshot, only for a job in a final state (maintainer rule: a re-judge only when the job reached a final
    /// state). A re-run is an offline re-judge, so it reads the job once and never waits: one still QUEUED (a live
    /// run's capacity NC, say) stays NOT COMPARABLE (capacity), one RUNNING or UNKNOWN is a failure, neither judged.
    private Promise<ShaRun> rejudged(Snapshot snapshot, String sha, long job) {
        return finished(job).flatMap(_ -> judge.judged(snapshot, sha, job))
                       .map(judged -> ShaRun.shaRun(sha,
                                                    snapshot.dir(),
                                                    none(),
                                                    job,
                                                    judged))
                       // FER (degrade forward): a job still QUEUED is NOT COMPARABLE (capacity); a failed or
                       // refused re-judgement is reported with its cause, never dropped.
                       .recover(cause -> notJudged(snapshot,
                                                   sha,
                                                   job,
                                                   none(),
                                                   cause));
    }

    /// The final state of `job`, read once.
    private Promise<JobState> finished(long job) {
        return queue.jobs()
                    .apply(job)
                    .flatMap(view -> finalState(job, view).async());
    }

    static Result<JobState> finalState(long job, JobView view) {
        return view.state()
                   .isTerminal()
               ? success(view.state())
               : notFinal(job, view);
    }

    private static Result<JobState> notFinal(long job, JobView view) {
        return QueueWait.waitOf(view)
                        .map(wait -> TrustError.QueuedAtRerun.FACTORY.apply(job, wait).<JobState> result())
                        .or(() -> TrustError.NotFinishedAtRerun.FACTORY.apply(job,
                                                                              view.state().name())
                                                                       .result());
    }

    /// The snapshot directories under `root`, in name order.
    public Result<List<Path>> snapshotDirs() {
        return FileOps.list(root).map(T1Steps::withJobFile);
    }

    private static List<Path> withJobFile(List<Path> dirs) {
        return dirs.stream()
                   .filter(dir -> FileOps.isRegularFile(dir.resolve(JOB_FILE)))
                   .sorted()
                   .toList();
    }

    private Path dir(String sha) {
        return root.resolve(sha.substring(0,
                                          Math.min(SHORT_SHA, sha.length())));
    }

    private static Result<String> text(Path file) {
        return FileOps.readString(file).map(String::strip);
    }
}
