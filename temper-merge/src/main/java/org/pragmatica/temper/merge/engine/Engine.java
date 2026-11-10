package org.pragmatica.temper.merge.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.merge.engine.GitHubReader.JobView;
import org.pragmatica.temper.merge.engine.GitHubReader.PullView;
import org.pragmatica.temper.merge.engine.GitHubReader.QueueView;
import org.pragmatica.temper.merge.engine.GitHubReader.RunView;


/// One step of one job (#101). The runner never decides: it enqueues only while the PR's head still equals the
/// submitted sha and every required check is green at that sha; anything else is a refusal with its reason.
///
/// Per state, on [#advance]:
/// - any non-terminal state past its phase's deadline ([Policy#deadline]): `TIMEOUT`, naming the phase, how long it
///   lasted and its last observation. Checked first, so a timeout is never reported for a phase that had time left;
/// - `SUBMITTED`: the PR is read. Not open, or head ≠ sha: `REFUSED`. A draft: `REFUSED` without `--ready`; with it,
///   marked ready (a write; unconfirmed is `FAILED`). Otherwise `WAITING_CHECKS`;
/// - `WAITING_CHECKS`: the head is read again (moved: `REFUSED`), then the required checks at the sha. A red one:
///   `REFUSED` (a stop, not a retry); none required: `REFUSED`; pending: stays; all green: `ENQUEUING`, recorded
///   before the enqueue is sent, then the enqueue: `QUEUED`, or `FAILED` when GitHub did not confirm it;
/// - `ENQUEUING` found at a step (a restart between the record and the answer): the enqueue may have been sent, so it
///   is not sent again; `QUEUED`, saying so;
/// - `QUEUED`: merged: `MERGED` with the merge sha and the closed issues; removed: `AWAITING_RUN`, never re-enqueued,
///   whatever the head;
/// - `AWAITING_RUN`: no merge-group run: `REMOVED`; a run not completed: stays (its logs are not served yet);
///   completed: `REMOVED`, with the run, its failed jobs and the failing test names read from their logs.
///
/// A failed read changes nothing but the job's `lastObservation`. Every change goes through the [JobLedger] before
/// the next action.
public record Engine(GitHubReader reader, QueueWriter writer, JobLedger ledger, Policy policy, InstantSource clock) {
    static final Set<String> PASSED_JOB = Set.of("success", "skipped", "neutral");

    public static Engine engine(GitHubReader reader,
                                QueueWriter writer,
                                JobLedger ledger,
                                Policy policy,
                                InstantSource clock) {
        return new Engine(reader, writer, ledger, policy, clock);
    }

    /// One step; answers the job as recorded. A terminal job is answered as it is.
    public Promise<Job> advance(Job job) {
        if (job.state().terminal()) {
            return Promise.success(job);
        }

        var now = clock.instant();

        if (expired(job, now)) {
            return moved(job, JobState.TIMEOUT, timedOut(job, now));
        }

        return switch (job.state()) {
            case SUBMITTED -> reader.pull(job.repo(), job.pr()).fold(read -> read.fold(cause -> unread(job,
                                                                                                       "pull",
                                                                                                       cause),
                                                                                       pull -> admitted(job, pull)));
            case WAITING_CHECKS -> reader.pull(job.repo(), job.pr()).fold(read -> read.fold(cause -> unread(job,
                                                                                                            "pull",
                                                                                                            cause),
                                                                                            pull -> headHeld(job, pull)));
            case ENQUEUING -> moved(job,
                                    JobState.QUEUED,
                                    "resumed in ENQUEUING: the enqueue may have been sent; not sent again, watching");
            case QUEUED -> reader.queue(job.repo(), job.pr()).fold(read -> read.fold(cause -> unread(job, "queue", cause),
                                                                                     view -> watched(job, view)));
            case AWAITING_RUN -> reader.mergeGroupRun(job.repo(), job.pr()).fold(read -> read.fold(cause -> unread(job,
                                                                                                                   "merge-group run",
                                                                                                                   cause),
                                                                                                   run -> awaited(job,
                                                                                                                  run)));
            case MERGED, REMOVED, REFUSED, TIMEOUT, FAILED -> Promise.success(job);
        };
    }

    private boolean expired(Job job, Instant now) {
        return Duration.between(job.phaseSince(),
                                now)
                       .compareTo(policy.deadline(job.state())) > 0;
    }

    private static String timedOut(Job job, Instant now) {
        return "phase " + job.state()
             + " passed its deadline after " + Duration.between(job.phaseSince(),
                                                                now)
                                                       .toSeconds()
             + "s" + (job.state() == JobState.ENQUEUING
                      ? " (the enqueue may have been sent)"
                      : "")
             + "; last observation: " + (job.lastObservation()
                                            .isEmpty()
                                         ? "none"
                                         : job.lastObservation());
    }

    private Promise<Job> admitted(Job job, PullView pull) {
        if (!"OPEN".equals(pull.state())) {
            return moved(job, JobState.REFUSED, "pr is " + pull.state() + ", not OPEN");
        }

        if (!job.sha().equals(pull.head())) {
            return moved(job,
                         JobState.REFUSED,
                         "head moved before enqueue: head " + pull.head() + " is not " + job.sha());
        }

        if (!pull.draft()) {
            return moved(job, JobState.WAITING_CHECKS, "head verified; waiting for the required checks");
        }

        if (!job.ready()) {
            return moved(job, JobState.REFUSED, "pr is a draft; submit with --ready to mark it ready");
        }

        return writer.markReady(job.repo(),
                                job.pr())
                     .fold(sent -> sent.fold(cause -> moved(job,
                                                            JobState.FAILED,
                                                            "mark-ready not confirmed: " + OneLine.of(cause)),
                                             _ -> moved(job,
                                                        JobState.WAITING_CHECKS,
                                                        "head verified; draft marked ready (--ready); waiting for the required checks")));
    }

    private Promise<Job> headHeld(Job job, PullView pull) {
        if (!"OPEN".equals(pull.state())) {
            return moved(job, JobState.REFUSED, "pr is " + pull.state() + " while waiting for checks");
        }

        if (!job.sha().equals(pull.head())) {
            return moved(job,
                         JobState.REFUSED,
                         "head moved during the check wait: head " + pull.head() + " is not " + job.sha());
        }

        return Promise.all(reader.requiredChecks(job.repo(),
                                                 pull.base()),
                           reader.checkRuns(job.repo(),
                                            job.sha()))
                      .map(RequiredChecks::judged)
                      .fold(read -> read.fold(cause -> unread(job, "checks", cause),
                                              checks -> judged(job, checks)));
    }

    private Promise<Job> judged(Job job, RequiredChecks checks) {
        return switch (checks) {
            case RequiredChecks.Red red -> moved(job,
                                                 JobState.REFUSED,
                                                 "required check red at " + job.sha() + ": " + String.join(", ",
                                                                                                           red.failing()));
            case RequiredChecks.NoneRequired _ -> moved(job, JobState.REFUSED, "no required checks read for the base");
            case RequiredChecks.Pending pending -> observed(job,
                                                            "required checks pending: " + String.join(", ",
                                                                                                      pending.waiting()));
            case RequiredChecks.Green green -> moved(job,
                                                     JobState.ENQUEUING,
                                                     "all " + green.count() + " required checks green at " + job.sha()).flatMap(this::enqueued);
        };
    }

    private Promise<Job> enqueued(Job job) {
        return writer.enqueue(job.repo(),
                              job.pr(),
                              job.sha())
                     .fold(sent -> sent.fold(cause -> moved(job,
                                                            JobState.FAILED,
                                                            "enqueue not confirmed: " + OneLine.of(cause)),
                                             _ -> moved(job,
                                                        JobState.QUEUED,
                                                        "enqueued with expectedHeadOid " + job.sha())));
    }

    private Promise<Job> watched(Job job, QueueView view) {
        return switch (view) {
            case QueueView.Merged merged -> recorded(job,
                                                     job.merged(merged.mergeSha(),
                                                                merged.closedIssues(),
                                                                clock.instant()));
            case QueueView.Removed removed -> recorded(job,
                                                       job.dropped(removed.reason(), clock.instant()));
            case QueueView.Queued queued -> observed(job, "queued at position " + queued.position());
            case QueueView.Absent _ -> observed(job, "not in the queue, not merged, no removal seen");
        };
    }

    private Promise<Job> awaited(Job job, Option<RunView> run) {
        return run.fold(() -> recorded(job,
                                       job.reported("no merge-group run found", List.of(), List.of(), clock.instant())),
                        found -> found.completed()
                                 ? failures(job, found)
                                 : observed(job,
                                            "merge-group run " + found.id() + " is " + found.status()));
    }

    private Promise<Job> failures(Job job, RunView run) {
        return reader.jobs(job.repo(),
                           run.id())
                     .map(jobs -> jobs.stream()
                                      .filter(each -> !PASSED_JOB.contains(each.conclusion()))
                                      .toList())
                     .flatMap(failed -> Promise.allOf(failed.stream().map(each -> testsOf(job, each)).toList()).map(tests -> reportOf(job,
                                                                                                                                      run,
                                                                                                                                      failed,
                                                                                                                                      tests)))
                     .fold(read -> read.fold(cause -> unread(job, "run jobs", cause),
                                             done -> recorded(job, done)));
    }

    /// One failed job's test names; an unreadable log is said so in place of names, never dropped silently.
    private Promise<List<String>> testsOf(Job job, JobView failed) {
        return reader.jobLog(job.repo(),
                             failed.id())
                     .map(FailedTests::in)
                     .fold(read -> Promise.success(read.fold(cause -> List.of(failed.name()
                                                                             + ": log unreadable: " + OneLine.of(cause)),
                                                             names -> names)));
    }

    private Job reportOf(Job job, RunView run, List<JobView> failed, List<Result<List<String>>> tests) {
        var names = tests.stream()
                         .flatMap(each -> each.fold(_ -> List.<String> of(),
                                                    found -> found)
                                              .stream())
                         .distinct()
                         .toList();
        var jobs = failed.stream().map(each -> each.name() + ":" + each.conclusion()).toList();

        return job.reported(run.id() + " " + run.conclusion() + " " + run.url(),
                            jobs,
                            names,
                            clock.instant());
    }

    private Promise<Job> unread(Job job, String what, Cause cause) {
        return observed(job, what + " read failed: " + OneLine.of(cause));
    }

    private Promise<Job> observed(Job job, String observation) {
        return recorded(job, job.observed(observation));
    }

    private Promise<Job> moved(Job job, JobState next, String why) {
        return recorded(job,
                        job.moved(next, why, clock.instant()));
    }

    private Promise<Job> recorded(Job before, Job after) {
        return ledger.record(before, after);
    }
}
