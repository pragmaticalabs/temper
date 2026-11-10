package org.pragmatica.kiln.core.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.GitHubJson;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.list;
import static org.pragmatica.kiln.core.github.GitHubJson.number;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalInstant;
import static org.pragmatica.kiln.core.github.GitHubJson.text;


/// One attempt of one workflow run with its jobs (`GET /actions/runs/{id}/attempts/{n}/jobs`, #102): every attempt
/// counts, `attempt` > 1 being a re-run. A job's machine time is `completed_at - started_at`; a job that never started or
/// never completed counts 0 (every job runs on `ubuntu-latest`, so one job-hour is one machine-hour).
///
/// `runStarted` is the attempt's `run_started_at` (`GET /actions/runs/{id}/attempts/{n}`). A "re-run failed jobs" attempt
/// also lists the jobs it did not re-run, copied with the earlier attempt's timestamps (run 42398657995 attempt 2 lists
/// all 17 jobs, only `ember-shard-2` ran), so a job that started before `runStarted` is dropped: it was counted in the
/// attempt that ran it. Attempt 1 needs no `runStarted` (nothing precedes it to copy from) and is read without it.
public record RunAttempt(long run,
                         long attempt,
                         String workflow,
                         String event,
                         String headSha,
                         String headBranch,
                         Option<Instant> runStarted,
                         List<Job> jobs) {
    public static final String PULL_REQUEST = "pull_request";
    public static final String MERGE_GROUP = "merge_group";
    /// One page of jobs; [#jobs] refuses an attempt that lists more.
    public static final int PER_PAGE = 100;

    public RunAttempt {
        jobs = ran(runStarted, jobs);
    }

    public RunAttempt(long run,
                      long attempt,
                      String workflow,
                      String event,
                      String headSha,
                      String headBranch,
                      List<Job> jobs) {
        this(run, attempt, workflow, event, headSha, headBranch, Option.none(), jobs);
    }

    /// The jobs that ran in the attempt that started at `runStarted`: one that started before it was copied from an
    /// earlier attempt. A job that never started counts 0 either way and is kept.
    static List<Job> ran(Option<Instant> runStarted, List<Job> jobs) {
        return runStarted.map(start -> jobs.stream()
                                           .filter(job -> job.startedAt()
                                                             .filter(started -> started.isBefore(start))
                                                             .isEmpty())
                                           .toList())
                         .or(() -> List.copyOf(jobs));
    }

    public record Job(String name, Option<Instant> startedAt, Option<Instant> completedAt) {
        public long seconds() {
            return startedAt.flatMap(start -> completedAt.map(end -> Math.max(0,
                                                                              Duration.between(start, end).toSeconds())))
                            .or(0L);
        }
    }

    /// The attempt `attempt` of `run`, with the jobs of one page.
    public static RunAttempt runAttempt(WorkflowRun run, long attempt, List<Job> jobs) {
        return new RunAttempt(run.id(),
                              attempt,
                              run.name(),
                              run.event().or(""),
                              run.headSha(),
                              run.headBranch().or(""),
                              jobs);
    }

    /// The jobs of one page; a page whose `total_count` is over what it lists fails as [PipelineError.JobsOverPage].
    public static Result<List<Job>> jobs(long run, String json) {
        return GitHubJson.tree(json).flatMap(root -> Result.all(number(root, "total_count"),
                                                                list(root, "jobs", RunAttempt::job)).flatMap((total, jobs) -> whole(run,
                                                                                                                                    total,
                                                                                                                                    jobs)));
    }

    /// The attempt's `run_started_at`, from `GET /actions/runs/{id}/attempts/{n}`.
    public static Result<Instant> runStarted(String json) {
        return GitHubJson.tree(json).flatMap(root -> GitHubJson.instant(root, "run_started_at"));
    }

    /// The `run_started_at` a stored attempt carries; none for one read without it (attempt 1).
    public static Result<Option<Instant>> storedStart(String json) {
        return GitHubJson.tree(json).flatMap(root -> optionalInstant(root, "run_started_at"));
    }

    /// The jobs as the store keeps them: only the fields [#jobs] reads, in the shape of one page, so a stored attempt is
    /// decoded by the same code as GitHub's answer.
    public static Result<String> stored(List<Job> jobs) {
        return stored(Option.none(), jobs);
    }

    /// The jobs that ran in the attempt (see [#ran]), with its `run_started_at` when known, as the store keeps them.
    public static Result<String> stored(Option<Instant> runStarted, List<Job> jobs) {
        var ran = ran(runStarted, jobs);
        var page = new LinkedHashMap<String, Object>();

        page.put("total_count", ran.size());
        page.put("jobs",
                 ran.stream().map(RunAttempt::storedJob).toList());
        runStarted.onPresent(start -> page.put("run_started_at", start.toString()));

        return GitHubJson.MAPPER.writeAsString(page);
    }

    private static Map<String, String> storedJob(Job job) {
        return Stream.of(Option.some(Map.entry("name",
                                               job.name())),
                         job.startedAt().map(at -> Map.entry("started_at",
                                                             at.toString())),
                         job.completedAt().map(at -> Map.entry("completed_at",
                                                               at.toString())))
                     .flatMap(Option::stream)
                     .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Result<List<Job>> whole(long run, long total, List<Job> jobs) {
        return total > jobs.size()
               ? PipelineError.JobsOverPage.FACTORY.apply(run, total).result()
               : Result.success(jobs);
    }

    private static Result<Job> job(JsonNode node) {
        return Result.all(text(node, "name"), optionalInstant(node, "started_at"), optionalInstant(node, "completed_at")).map(Job::new);
    }

    /// Whether `run` belongs to merged PR `pr` on `base`: a `pull_request` run on one of its commits, or a `merge_group`
    /// run on its queue branch `gh-readonly-queue/<base>/pr-<N>-<sha>`.
    public static boolean belongs(WorkflowRun run, MergedPr pr, String base) {
        var event = run.event().or("");

        return switch (event) {
            case PULL_REQUEST -> pr.commits().stream().anyMatch(commit -> commit.oid()
                                                                                .equals(run.headSha()));
            case MERGE_GROUP -> run.headBranch().filter(branch -> queueBranch(base,
                                                                              pr.number()).matcher(branch)
                                                                             .matches()).isPresent();
            default -> false;
        };
    }

    static Pattern queueBranch(String base, long number) {
        return Pattern.compile("^gh-readonly-queue/" + Pattern.quote(base) + "/pr-" + number + "-[0-9a-f]{40}$");
    }

    /// When the attempt started: its `run_started_at`; read without it (attempt 1), its earliest job start [guess:
    /// within seconds of the run's start, and asking for it costs a request per run]; none when no job started (it then
    /// counts 0 hours anyway).
    public Option<Instant> started() {
        return runStarted.orElse(() -> Option.from(jobs.stream()
                                                       .flatMap(job -> job.startedAt()
                                                                          .stream())
                                                       .min(Instant::compareTo)));
    }

    public long seconds() {
        return jobs.stream()
                   .mapToLong(Job::seconds)
                   .sum();
    }
}
