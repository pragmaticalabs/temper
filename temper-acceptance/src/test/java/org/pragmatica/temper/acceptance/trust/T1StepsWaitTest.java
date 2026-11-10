package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.state.JobState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Option A in T1's steps: the queue phase bounded by `--queue-hours`, then `temper wait` bounded by `--run-hours`.
/// Only a job that ended is judged: still waiting at the queue bound (QUEUED, or for the port group) is NOT COMPARABLE (capacity), a wait that timed out
/// is a failure with its state, and neither ever reaches step 4 (job 39 used to die on HTTP 404 from `/result`).
class T1StepsWaitTest {
    private static final String SHA = "ea364e8952";
    private static final long JOB = 39;
    private static final int RUN_HOURS = 3;
    private static final String PLANTED = TrustReportCrashTest.PLANTED;

    private final List<Long> judgedJobs = new CopyOnWriteArrayList<>();

    @Test
    void queueBoundExpired_isNotComparableCapacity_notCounted_neverJudged_andTheReportSaysSo(@TempDir Path dir) {
        var cli = new Waits.Cli(0, "wait 39 PASSED testcases=1 failed=0 observed-failures=0 evidence=stored\n");
        var clock = new Waits.Clock();

        var run = steps(cli, Waits.queue(Waits.queuedThen(Integer.MAX_VALUE, JobState.RUNNING), clock, Duration.ofHours(24)))
                      .waitedAndJudged(Snapshot.snapshot(dir), SHA, JOB, none())
                      .await()
                      .expect("a SHA run");
        var report = TrustReport.t1(LocalDate.of(2026, 10, 16), PLANTED, List.of(run), false);

        assertThat(run.starved()).isTrue();
        assertThat(run.capacity()).isEqualTo(some("job 39 still QUEUED after 24h"));
        assertThat(run.counted(PLANTED)).as("not counted toward T1").isFalse();
        assertThat(run.failure()).as("never a failure").isEqualTo(none());
        assertThat(run.job()).isEqualTo(some(JOB));
        assertThat(judgedJobs).as("never judged").isEmpty();
        assertThat(cli.argvs).as("no temper wait once the queue bound expired").isEmpty();
        assertThat(clock.elapsed()).as("the queue bound, no pause past it").isEqualTo(Duration.ofHours(24));
        assertThat(report).contains("## " + SHA + " " + TrustReport.NOT_COMPARABLE_CAPACITY)
                          .contains("- **not comparable (capacity):** job 39 still QUEUED after 24h")
                          .contains("0 not comparable (fork crash, not counted), 1 not comparable (capacity, not counted)")
                          .doesNotContain("## " + SHA + " " + TrustReport.FAIL)
                          .doesNotContain("stopped:")
                          .doesNotContain(TrustReport.UNWRITTEN);
    }

    /// Q-M1-17: a granted unit's bb-hold wait for the port group is seen while the job is QUEUED; at the queue bound
    /// it is capacity, and the report names the wait.
    @Test
    void portGroupWaitAtTheQueueBound_isNotComparableCapacity_neverJudged(@TempDir Path dir) {
        var cli = new Waits.Cli(0, "wait 39 PASSED testcases=1 failed=0 observed-failures=0 evidence=stored\n");
        var jobs = Waits.reading(Waits.view(JobState.QUEUED), Waits.queuedForPortGroup());
        var capacity = "job 39 still QUEUED (waiting for the port group since " + Waits.START + ") after 24h";

        var run = steps(cli, Waits.queue(jobs, new Waits.Clock(), Duration.ofHours(24)))
                      .waitedAndJudged(Snapshot.snapshot(dir), SHA, JOB, none())
                      .await()
                      .expect("a SHA run");

        assertThat(run.starved()).isTrue();
        assertThat(run.capacity()).isEqualTo(some(capacity));
        assertThat(run.counted(PLANTED)).isFalse();
        assertThat(judgedJobs).isEmpty();
        assertThat(cli.argvs).as("the run bound never started").isEmpty();
        assertThat(TrustReport.t1(LocalDate.of(2026, 10, 16), PLANTED, List.of(run), false))
            .contains("## " + SHA + " " + TrustReport.NOT_COMPARABLE_CAPACITY)
            .contains("- **not comparable (capacity):** " + capacity);
    }

    /// QUEUED, then QUEUED with the port group waited for, then RUNNING with it held: the run phase starts only then.
    @Test
    void queuedThenPortGroupWaitThenRunning_startsTheRunPhaseOnlyOnceRunning(@TempDir Path dir) {
        var cli = new Waits.Cli(0, "wait 39 PASSED testcases=293 failed=0 observed-failures=0 evidence=stored\n");
        var jobs = Waits.reading(Waits.view(JobState.QUEUED), Waits.queuedForPortGroup(), Waits.queuedForPortGroup(), Waits.runningHeld());

        var run = steps(cli, Waits.queue(jobs, new Waits.Clock(), Duration.ofHours(24)))
                      .waitedAndJudged(Snapshot.snapshot(dir), SHA, JOB, none())
                      .await()
                      .expect("a SHA run");

        assertThat(jobs.reads.get()).as("polled through the port-group wait until RUNNING").isEqualTo(4);
        assertThat(cli.argvs).containsExactly(List.of("temper", "wait", "39", "--timeout", "10800s"));
        assertThat(judgedJobs).containsExactly(JOB);
        assertThat(run.passed()).isTrue();
    }

    /// maintainer rule: a re-judge only when the job reached a final state. `--rerun` reads the job once, never waits.
    @Test
    void rerun_judgesAJobInAFinalState(@TempDir Path dir) throws Exception {
        var jobs = Waits.reading(Waits.view(JobState.PASSED));

        var run = rerun(dir, jobs);

        assertThat(jobs.reads.get()).isEqualTo(1);
        assertThat(judgedJobs).containsExactly(JOB);
        assertThat(run.passed()).isTrue();
    }

    @Test
    void rerun_ofAJobStillQueued_isNotComparableCapacity_neverJudged(@TempDir Path dir) throws Exception {
        var jobs = Waits.reading(Waits.view(JobState.QUEUED));

        var run = rerun(dir, jobs);

        assertThat(jobs.reads.get()).as("read once, no waiting").isEqualTo(1);
        assertThat(judgedJobs).isEmpty();
        assertThat(run.starved()).isTrue();
        assertThat(run.capacity()).isEqualTo(some("job 39 still QUEUED at rerun"));
        assertThat(run.failure()).isEqualTo(none());
        assertThat(run.counted(PLANTED)).isFalse();
    }

    @Test
    void rerun_ofAJobRunningOrUnknown_isAFailureWithItsState_neverJudged(@TempDir Path dir) throws Exception {
        var running = rerun(Files.createDirectory(dir.resolve("r")), Waits.reading(Waits.runningHeld()));
        var unknown = rerun(Files.createDirectory(dir.resolve("u")), Waits.reading(Waits.view(JobState.UNKNOWN)));

        assertThat(judgedJobs).isEmpty();
        assertThat(running.failure()).isEqualTo(some("job 39 not finished at rerun (state RUNNING)"));
        assertThat(unknown.failure()).isEqualTo(some("job 39 not finished at rerun (state UNKNOWN)"));
        assertThat(running.starved()).isFalse();
        assertThat(unknown.starved()).isFalse();
    }

    @Test
    void runBoundTimedOut_isAFailureWithTheState_andNeverJudged(@TempDir Path dir) {
        var cli = new Waits.Cli(7, "wait 39 RUNNING\n");

        var run = steps(cli, Waits.queue(Waits.queuedThen(2, JobState.RUNNING), new Waits.Clock(), Duration.ofHours(24)))
                      .waitedAndJudged(Snapshot.snapshot(dir), SHA, JOB, none())
                      .await()
                      .expect("a SHA run");

        assertThat(run.failure()).isEqualTo(some("wait timed out after 3h at state RUNNING"));
        assertThat(run.starved()).isFalse();
        assertThat(run.judged()).isEqualTo(none());
        assertThat(judgedJobs).as("an unfinished job is never judged").isEmpty();
        assertThat(cli.argvs).containsExactly(List.of("temper", "wait", "39", "--timeout", "10800s"));
        assertThat(TrustReport.t1(LocalDate.of(2026, 10, 16), PLANTED, List.of(run), false))
            .contains("## " + SHA + " " + TrustReport.FAIL)
            .contains("- **stopped:** wait timed out after 3h at state RUNNING");
    }

    @Test
    void queuedThenRunningThenTerminal_isJudgedAsBefore(@TempDir Path dir) {
        var cli = new Waits.Cli(0, "wait 39 PASSED testcases=293 failed=0 observed-failures=0 evidence=stored\n");
        var jobs = Waits.queuedThen(3, JobState.RUNNING);

        var run = steps(cli, Waits.queue(jobs, new Waits.Clock(), Duration.ofHours(24)))
                      .waitedAndJudged(Snapshot.snapshot(dir), SHA, JOB, none())
                      .await()
                      .expect("a SHA run");

        assertThat(jobs.reads.get()).as("polled until the job left QUEUED").isEqualTo(4);
        assertThat(cli.argvs).as("the run bound starts once the job left the queue").containsExactly(List.of("temper", "wait", "39", "--timeout", "10800s"));
        assertThat(judgedJobs).containsExactly(JOB);
        assertThat(run.judged().isPresent()).isTrue();
        assertThat(run.passed()).isTrue();
        assertThat(run.failure()).isEqualTo(none());
        assertThat(run.starved()).isFalse();
    }

    /// A live run's snapshot (`sha` and `job` files) in `dir`, re-run against `jobs`.
    private ShaRun rerun(Path dir, Waits.Jobs jobs) throws Exception {
        Files.writeString(dir.resolve(T1Steps.SHA_FILE), SHA + "\n");
        Files.writeString(dir.resolve(T1Steps.JOB_FILE), JOB + "\n");
        var cli = new Waits.Cli(0, "");

        return steps(cli, Waits.queue(jobs, new Waits.Clock(), Duration.ofHours(24))).rerun(dir).await().expect("a re-run");
    }

    private T1Steps steps(Waits.Cli cli, QueueWait queue) {
        return new T1Steps(Processes.processes(cli), cli.temperCli(), queue, this::judged, Path.of("/unused"), 1,
                           timeSpan(RUN_HOURS).hours());
    }

    private Promise<ShaRun.Judged> judged(Snapshot snapshot, String sha, long job) {
        judgedJobs.add(job);
        return Promise.success(TrustReportCrashTest.run(sha, List.of(), "").judged().expect("a judged capture"));
    }
}
