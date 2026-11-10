package org.pragmatica.temper.merge.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.merge.engine.Engine;
import org.pragmatica.temper.merge.engine.FakeGitHub;
import org.pragmatica.temper.merge.engine.Job;
import org.pragmatica.temper.merge.engine.JobLedger;
import org.pragmatica.temper.merge.engine.JobState;
import org.pragmatica.temper.merge.engine.GitHubReader.QueueView;
import org.pragmatica.temper.merge.store.EventsLog;
import org.pragmatica.temper.merge.store.FileLedger;
import org.pragmatica.temper.merge.store.JobFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// A restart is a new runner over the same files: every object built afresh, nothing carried in memory.
class ServiceTest {
    @TempDir
    Path home;

    private final FakeGitHub github = new FakeGitHub();
    private final FakeGitHub.Clock clock = new FakeGitHub.Clock();

    private RunnerConfig config() {
        return RunnerConfig.load(Map.of("TEMPER_MERGE_HOME", home.toString())).unwrap();
    }

    /// A runner as `serve` builds it, over the files under `home`.
    private Service runner() {
        var config = config();
        var jobs = JobFiles.jobFiles(config.jobs());
        var ledger = FileLedger.fileLedger(jobs,
                                           EventsLog.eventsLog(config.eventsLog()),
                                           clock);

        return Service.service(jobs,
                               Engine.engine(github, github, ledger, config.policy(), clock));
    }

    private Job only(List<Job> jobs) {
        assertThat(jobs).hasSize(1);

        return jobs.getFirst();
    }

    @Test
    void aRestartedRunner_resumesWatchingFromTheFiles_andReportsTheMerge() throws Exception {
        var id = Commands.commands(config(),
                                   clock,
                                   new Random(1))
                         .submit(List.of("2849", FakeGitHub.SHA))
                         .lines()
                         .getFirst();

        github.allGreen();
        var first = runner();

        assertThat(only(first.round().await().unwrap()).state()).isEqualTo(JobState.WAITING_CHECKS);
        assertThat(only(first.round().await().unwrap()).state()).isEqualTo(JobState.QUEUED);
        github.queue = new QueueView.Merged("9f1c2d3e4b5a69788796a5b4c3d2e1f009182736", List.of("#101"));
        var restarted = runner();

        assertThat(only(restarted.round().await().unwrap()).state()).isEqualTo(JobState.MERGED);
        assertThat(github.enqueued).containsExactly(FakeGitHub.SHA);
        assertThat(restarted.round().await().unwrap()).isEmpty();
        assertThat(Files.readAllLines(config().eventsLog())).extracting(line -> line.split("\t") [4])
                  .containsExactly("state=SUBMITTED",
                                   "state=WAITING_CHECKS",
                                   "state=ENQUEUING",
                                   "state=QUEUED",
                                   "state=MERGED");
        assertThat(Commands.commands(config(), clock, new Random(1)).status(List.of(id)).lines()).contains("state=MERGED",
                                                                                                           "merge=9f1c2d3e4b5a69788796a5b4c3d2e1f009182736",
                                                                                                           "closed=#101");
    }

    @Test
    void anEnqueuingTimeout_isOneEventsLineCarryingTheStateAndReason() throws Exception {
        var jobs = JobFiles.jobFiles(config().jobs());
        var enqueuing = Job.submitted("mr-1-2849-abcd",
                                      FakeGitHub.REPO,
                                      2849,
                                      FakeGitHub.SHA,
                                      false,
                                      clock.instant())
                           .moved(JobState.ENQUEUING,
                                  "all 2 required checks green",
                                  clock.instant());

        assertThat(jobs.create(enqueuing).isSuccess()).isTrue();
        clock.advance(java.time.Duration.ofHours(1));
        assertThat(only(runner().round().await().unwrap()).state()).isEqualTo(JobState.TIMEOUT);
        var line = Files.readAllLines(config().eventsLog()).getLast().split("\t");

        assertThat(line[4]).isEqualTo("state=TIMEOUT");
        assertThat(line[5]).startsWith("reason=phase ENQUEUING passed its deadline")
                  .contains("the enqueue may have been sent");
    }

    @Test
    void aRunnerStoppedBetweenTheEnqueueRecordAndItsAnswer_doesNotEnqueueAgain() {
        var jobs = JobFiles.jobFiles(config().jobs());
        var enqueuing = Job.submitted("mr-1-2849-abcd",
                                      FakeGitHub.REPO,
                                      2849,
                                      FakeGitHub.SHA,
                                      false,
                                      clock.instant())
                           .moved(JobState.ENQUEUING,
                                  "all 2 required checks green",
                                  clock.instant());

        assertThat(jobs.create(enqueuing).isSuccess()).isTrue();
        github.allGreen();
        var resumed = only(runner().round().await().unwrap());

        assertThat(resumed.state()).isEqualTo(JobState.QUEUED);
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void aStepThatCannotBeRecorded_leavesItsJob_andTheRoundGoesOn() {
        var jobs = JobFiles.jobFiles(config().jobs());
        var first = Job.submitted("mr-1-1-aaaa", FakeGitHub.REPO, 1, FakeGitHub.SHA, false, clock.instant());

        clock.advance(Duration.ofSeconds(1));
        var second = Job.submitted("mr-2-2-bbbb", FakeGitHub.REPO, 2, FakeGitHub.SHA, false, clock.instant());

        assertThat(jobs.create(first).isSuccess()).isTrue();
        assertThat(jobs.create(second).isSuccess()).isTrue();
        JobLedger refusingFirst = (before, after) -> before.id()
                                                           .equals(first.id())
                                                     ? Causes.cause("disk full").promise()
                                                     : Promise.success(after);
        var round = Service.service(jobs,
                                    Engine.engine(github,
                                                  github,
                                                  refusingFirst,
                                                  config().policy(),
                                                  clock))
                           .round()
                           .await()
                           .unwrap();

        assertThat(round).extracting(Job::state).containsExactly(JobState.SUBMITTED, JobState.WAITING_CHECKS);
    }
}
