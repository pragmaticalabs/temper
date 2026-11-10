package org.pragmatica.temper.merge.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.merge.engine.GitHubReader.JobView;
import org.pragmatica.temper.merge.engine.GitHubReader.QueueView;
import org.pragmatica.temper.merge.engine.GitHubReader.RunView;

import org.junit.jupiter.api.Test;

import static org.pragmatica.temper.merge.engine.FakeGitHub.OTHER;
import static org.pragmatica.temper.merge.engine.FakeGitHub.REPO;
import static org.pragmatica.temper.merge.engine.FakeGitHub.SHA;
import static org.pragmatica.temper.merge.engine.FakeGitHub.run;
import static org.assertj.core.api.Assertions.assertThat;


/// One test per outcome of #101's acceptance list. The writes go to [FakeGitHub], which counts them.
class EngineTest {
    private final FakeGitHub github = new FakeGitHub();
    private final FakeGitHub.Clock clock = new FakeGitHub.Clock();
    private final FakeGitHub.Ledger ledger = new FakeGitHub.Ledger();
    private final Engine engine = Engine.engine(github, github, ledger, Policy.DEFAULT, clock);

    private Job job(boolean ready) {
        return Job.submitted("mr-1-2849-abcd", REPO, 2849, SHA, ready, clock.instant());
    }

    private Job step(Job job) {
        return engine.advance(job)
                     .await()
                     .unwrap();
    }

    private Job queued() {
        github.allGreen();
        var queued = step(step(job(false)));

        assertThat(queued.state()).isEqualTo(JobState.QUEUED);

        return queued;
    }

    @Test
    void merged_reportsTheMergeShaAndTheClosedIssues() {
        var waiting = step(job(false));

        assertThat(waiting.state()).isEqualTo(JobState.WAITING_CHECKS);
        github.allGreen();
        var queued = step(waiting);

        assertThat(queued.state()).isEqualTo(JobState.QUEUED);
        assertThat(github.enqueued).containsExactly(SHA);
        github.queue = new QueueView.Queued(2);
        var still = step(queued);

        assertThat(still.state()).isEqualTo(JobState.QUEUED);
        assertThat(still.lastObservation()).isEqualTo("queued at position 2");
        github.queue = new QueueView.Merged("9f1c2d3e4b5a69788796a5b4c3d2e1f009182736", List.of("#101", "#99"));
        var merged = step(still);

        assertThat(merged.state()).isEqualTo(JobState.MERGED);
        assertThat(merged.mergeSha()).isEqualTo("9f1c2d3e4b5a69788796a5b4c3d2e1f009182736");
        assertThat(merged.closedIssues()).containsExactly("#101", "#99");
        assertThat(ledger.changes).containsExactly(JobState.WAITING_CHECKS,
                                                   JobState.ENQUEUING,
                                                   JobState.QUEUED,
                                                   JobState.MERGED);
    }

    @Test
    void removedForFailedChecks_waitsForTheRun_thenReportsFailedJobsAndTestNamesFromTheirLogs() {
        var queued = queued();

        github.queue = new QueueView.Removed("FAILED_CHECKS");
        var removed = step(queued);

        assertThat(removed.state()).isEqualTo(JobState.AWAITING_RUN);
        assertThat(removed.removal()).isEqualTo("FAILED_CHECKS");
        var url = "https://github.com/pragmaticalabs/pragmatica/actions/runs/42420645926";

        github.run = Option.some(new RunView(42420645926L, url, "in_progress", ""));
        var running = step(removed);

        assertThat(running.state()).isEqualTo(JobState.AWAITING_RUN);
        assertThat(running.lastObservation()).isEqualTo("merge-group run 42420645926 is in_progress");
        github.run = Option.some(new RunView(42420645926L, url, "completed", "failure"));
        github.jobs = List.of(new JobView(1L, "static-gates", "success"),
                              new JobView(128548485648L, "test-group-g3", "failure"),
                              new JobView(128531192497L, "ember-shard-4", "failure"),
                              new JobView(128556313308L, "build-and-test", "failure"));
        github.logs.put(128548485648L, fixture("job-log-test-group-g3.txt"));
        github.logs.put(128531192497L, fixture("job-log-ember-shard-4.txt"));
        github.logs.put(128556313308L, fixture("job-log-build-and-test-gate.txt"));
        var reported = step(running);

        assertThat(reported.state()).isEqualTo(JobState.REMOVED);
        assertThat(reported.reason()).isEqualTo("removed from the queue: FAILED_CHECKS");
        assertThat(reported.run()).isEqualTo("42420645926 failure " + url);
        assertThat(reported.failedJobs()).containsExactly("test-group-g3:failure",
                                                          "ember-shard-4:failure",
                                                          "build-and-test:failure");
        assertThat(reported.failedTests()).containsExactly("org.pragmatica.storage.AppendLogTest.append_writesFrameBeforeReturning",
                                                           "org.pragmatica.aether.ember.EmberFirstPublishRefusedCatchupTest.firstPublish_toANewCf2Stream_isAcknowledged_whenEveryReplicasFirstCatchupReadIsRefused");
        assertThat(github.enqueued).containsExactly(SHA);
    }

    @Test
    void anUnreadableJobLog_isNamedInTheReport_notDropped() {
        var queued = queued();

        github.queue = new QueueView.Removed("FAILED_CHECKS");
        github.run = Option.some(new RunView(7L, "u", "completed", "failure"));
        github.jobs = List.of(new JobView(8L, "fragment", "timed_out"));
        var reported = step(step(queued));

        assertThat(reported.state()).isEqualTo(JobState.REMOVED);
        assertThat(reported.failedJobs()).containsExactly("fragment:timed_out");
        assertThat(reported.failedTests()).containsExactly("fragment: log unreadable: log not served");
    }

    @Test
    void headMovedBeforeEnqueue_isRefused_andNothingIsWritten() {
        github.pull = new GitHubReader.PullView(OTHER, false, "OPEN", "release-1.0.0-rc5");
        var refused = step(job(true));

        assertThat(refused.state()).isEqualTo(JobState.REFUSED);
        assertThat(refused.reason()).isEqualTo("head moved before enqueue: head " + OTHER + " is not " + SHA);
        assertThat(github.enqueued).isEmpty();
        assertThat(github.markReadyCalls).isZero();
    }

    @Test
    void headMovedDuringTheCheckWait_isRefused_andNeverEnqueued() {
        var waiting = step(job(false));

        github.runs = List.of(run("build-and-test", "in_progress", "", 1));
        var pending = step(waiting);

        assertThat(pending.state()).isEqualTo(JobState.WAITING_CHECKS);
        assertThat(pending.lastObservation()).isEqualTo("required checks pending: build-and-test, forge-tests");
        github.pull = new GitHubReader.PullView(OTHER, false, "OPEN", "release-1.0.0-rc5");
        github.allGreen();
        var refused = step(pending);

        assertThat(refused.state()).isEqualTo(JobState.REFUSED);
        assertThat(refused.reason()).isEqualTo("head moved during the check wait: head " + OTHER + " is not " + SHA);
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void draftWithoutReady_isRefused_andNotMarkedReady() {
        github.pull = new GitHubReader.PullView(SHA, true, "OPEN", "release-1.0.0-rc5");
        var refused = step(job(false));

        assertThat(refused.state()).isEqualTo(JobState.REFUSED);
        assertThat(refused.reason()).isEqualTo("pr is a draft; submit with --ready to mark it ready");
        assertThat(github.markReadyCalls).isZero();
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void draftWithReady_isMarkedReady_once_thenWaitsForChecks() {
        github.pull = new GitHubReader.PullView(SHA, true, "OPEN", "release-1.0.0-rc5");
        var waiting = step(job(true));

        assertThat(waiting.state()).isEqualTo(JobState.WAITING_CHECKS);
        assertThat(waiting.reason()).contains("draft marked ready");
        assertThat(github.markReadyCalls).isEqualTo(1);
        github.allGreen();
        assertThat(step(waiting).state()).isEqualTo(JobState.QUEUED);
        assertThat(github.markReadyCalls).isEqualTo(1);
    }

    @Test
    void readyOnANonDraft_marksNothing() {
        assertThat(step(job(true)).state()).isEqualTo(JobState.WAITING_CHECKS);
        assertThat(github.markReadyCalls).isZero();
    }

    @Test
    void aRedRequiredCheck_stops_evenWithOthersPending_andIsNotRetried() {
        var waiting = step(job(false));

        github.runs = List.of(run("build-and-test", "completed", "failure", 1), run("forge-tests", "in_progress", "", 1));
        var refused = step(waiting);

        assertThat(refused.state()).isEqualTo(JobState.REFUSED);
        assertThat(refused.reason()).isEqualTo("required check red at " + SHA + ": build-and-test:failure");
        var reads = github.checkReads;

        github.allGreen();
        assertThat(step(refused)).isEqualTo(refused);
        assertThat(github.checkReads).isEqualTo(reads);
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void aRerunThatPassed_replacesTheRedRunItReran() {
        var waiting = step(job(false));

        github.runs = List.of(run("build-and-test", "completed", "failure", 1),
                              run("build-and-test", "completed", "success", 5),
                              run("forge-tests", "completed", "success", 1));
        assertThat(step(waiting).state()).isEqualTo(JobState.QUEUED);
    }

    @Test
    void aNeutralRequiredCheck_isRefused_namingTheCheckAndConclusion() {
        var waiting = step(job(false));

        github.runs = List.of(run("build-and-test", "completed", "neutral", 1),
                              run("forge-tests", "completed", "success", 1));
        var refused = step(waiting);

        assertThat(refused.state()).isEqualTo(JobState.REFUSED);
        assertThat(refused.reason()).isEqualTo("required check red at " + SHA + ": build-and-test:neutral");
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void aSkippedRequiredCheck_isRefused_namingTheCheckAndConclusion() {
        var waiting = step(job(false));

        github.runs = List.of(run("build-and-test", "completed", "success", 1),
                              run("forge-tests", "completed", "skipped", 1));
        var refused = step(waiting);

        assertThat(refused.state()).isEqualTo(JobState.REFUSED);
        assertThat(refused.reason()).isEqualTo("required check red at " + SHA + ": forge-tests:skipped");
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void successAlone_passesTheRequiredChecks() {
        var waiting = step(job(false));

        github.allGreen();
        assertThat(step(waiting).state()).isEqualTo(JobState.QUEUED);
    }

    @Test
    void requiredChecks_areTheUnionOfRulesetAndProtection_aCheckInOneSourceIsStillRequired() {
        github.required = List.of("build-and-test");
        github.protection = List.of("forge-tests", "build-and-test");
        List<String> union = github.requiredChecks(REPO, "b").await().fold(_ -> List.<String> of(), list -> list);

        assertThat(union).containsExactlyInAnyOrder("build-and-test", "forge-tests");
        var waiting = step(job(false));

        github.runs = List.of(run("build-and-test", "completed", "success", 1));
        var pending = step(waiting);

        assertThat(pending.state()).isEqualTo(JobState.WAITING_CHECKS);
        assertThat(pending.lastObservation()).isEqualTo("required checks pending: forge-tests");
        github.required = List.of();
        github.protection = List.of("only-in-protection");
        github.runs = List.of(run("only-in-protection", "completed", "success", 1));
        assertThat(step(pending).state()).isEqualTo(JobState.QUEUED);
    }

    @Test
    void theEnqueuingRecord_isDurableBeforeTheEnqueueIsSent() {
        var waiting = step(job(false));

        github.trace = ledger.trace;
        github.allGreen();
        step(waiting);
        assertThat(ledger.trace).containsSubsequence("ENQUEUING", "enqueue", "QUEUED");
        assertThat(ledger.trace.indexOf("ENQUEUING")).isLessThan(ledger.trace.indexOf("enqueue"));
    }

    @Test
    void aFailedEnqueuingRecord_meansNothingIsSent() {
        var waiting = step(job(false));

        github.allGreen();
        ledger.failOn = JobState.ENQUEUING;
        assertThat(engine.advance(waiting).await().isFailure()).isTrue();
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void aQueuedRunWithNoStartTime_isPendingAndNeverAnNpe_andLosesToARunThatStarted() {
        var waiting = step(job(false));

        github.runs = List.of(new GitHubReader.CheckRun("build-and-test", "queued", "", null),
                              run("forge-tests", "completed", "success", 1));
        var pending = step(waiting);

        assertThat(pending.state()).isEqualTo(JobState.WAITING_CHECKS);
        assertThat(pending.lastObservation()).isEqualTo("required checks pending: build-and-test");
        github.runs = List.of(new GitHubReader.CheckRun("build-and-test", "completed", "failure", null),
                              run("build-and-test", "completed", "success", 1),
                              run("forge-tests", "completed", "success", 1));
        assertThat(step(pending).state()).isEqualTo(JobState.QUEUED);
    }

    @Test
    void anEnqueuingTimeout_saysTheEnqueueMayHaveBeenSent() {
        var enqueuing = job(false).moved(JobState.ENQUEUING, "all 2 required checks green", clock.instant());

        clock.advance(Duration.ofHours(1));
        var timedOut = step(enqueuing);

        assertThat(timedOut.state()).isEqualTo(JobState.TIMEOUT);
        assertThat(timedOut.reason()).contains("phase ENQUEUING passed its deadline")
                  .contains("(the enqueue may have been sent)");
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void noRequiredChecksRead_isRefused() {
        var waiting = step(job(false));

        github.required = List.of();
        github.protection = List.of();
        var refused = step(waiting);

        assertThat(refused.state()).isEqualTo(JobState.REFUSED);
        assertThat(refused.reason()).isEqualTo("no required checks read for the base");
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void anEnqueueGitHubDidNotConfirm_isFailed_andNotRetried() {
        github.enqueueFails = Option.some("Pull request is in clean status");
        github.allGreen();
        var failed = step(step(job(false)));

        assertThat(failed.state()).isEqualTo(JobState.FAILED);
        assertThat(failed.reason()).isEqualTo("enqueue not confirmed: Pull request is in clean status");
        assertThat(step(failed)).isEqualTo(failed);
        assertThat(github.enqueued).hasSize(1);
    }

    @Test
    void aClosedPr_isRefused() {
        github.pull = new GitHubReader.PullView(SHA, false, "CLOSED", "release-1.0.0-rc5");
        assertThat(step(job(false)).reason()).isEqualTo("pr is CLOSED, not OPEN");
    }

    @Test
    void aFailedRead_isAnObservation_notAStateChange() {
        github.pullFails = Option.some("HTTP 502");
        var same = step(job(false));

        assertThat(same.state()).isEqualTo(JobState.SUBMITTED);
        assertThat(same.lastObservation()).isEqualTo("pull read failed: HTTP 502");
        assertThat(ledger.changes).isEmpty();
    }

    @Test
    void aDropWithAnUnchangedHead_isReported_andNeverReEnqueued() {
        var queued = queued();

        github.queue = new QueueView.Removed("MANUAL");
        var awaiting = step(queued);
        var removed = step(awaiting);

        assertThat(removed.state()).isEqualTo(JobState.REMOVED);
        assertThat(removed.reason()).isEqualTo("removed from the queue: MANUAL");
        assertThat(removed.run()).isEqualTo("no merge-group run found");
        github.queue = new QueueView.Absent();
        assertThat(step(removed)).isEqualTo(removed);
        assertThat(github.pull.head()).isEqualTo(SHA);
        assertThat(github.enqueued).containsExactly(SHA);
    }

    @Test
    void enqueuingFoundAtAStep_isNotSentAgain() {
        var enqueuing = job(false).moved(JobState.ENQUEUING, "all 2 required checks green", clock.instant());
        var queued = step(enqueuing);

        assertThat(queued.state()).isEqualTo(JobState.QUEUED);
        assertThat(queued.reason()).contains("not sent again");
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void eachPhase_timesOutAfterItsDeadline_namingThePhaseAndItsLastObservation() {
        var phases = List.of(JobState.SUBMITTED,
                             JobState.WAITING_CHECKS,
                             JobState.ENQUEUING,
                             JobState.QUEUED,
                             JobState.AWAITING_RUN);

        for (var phase : phases) {
            var job = job(false).moved(phase, "test", clock.instant()).observed("last seen " + phase);

            clock.advance(Policy.DEFAULT.deadline(phase));
            assertThat(step(job).state()).as("at the deadline of " + phase).isNotEqualTo(JobState.TIMEOUT);
            clock.advance(Duration.ofSeconds(1));
            var timedOut = step(job);

            assertThat(timedOut.state()).as(phase.name()).isEqualTo(JobState.TIMEOUT);
            assertThat(timedOut.reason()).isEqualTo("phase " + phase
                                                   + " passed its deadline after " + (Policy.DEFAULT.deadline(phase).toSeconds() + 1)
                                                   + "s" + (phase == JobState.ENQUEUING
                                                            ? " (the enqueue may have been sent)"
                                                            : "")
                                                   + "; last observation: last seen " + phase);
        }
    }

    @Test
    void aPhaseThatOnlyFailedToRead_timesOutNamingTheFailure() {
        github.pullFails = Option.some("no GitHub read identity is configured for temper-merge");
        var observed = step(job(false));

        clock.advance(Policy.DEFAULT.submitted().plusSeconds(1));
        assertThat(step(observed).reason()).endsWith("last observation: pull read failed: no GitHub read identity is configured for temper-merge");
    }

    @Test
    void aMarkReadyGitHubDidNotConfirm_isFailed() {
        github.pull = new GitHubReader.PullView(SHA, true, "OPEN", "release-1.0.0-rc5");
        github.failing.add("markReady");
        var failed = step(job(true));

        assertThat(failed.state()).isEqualTo(JobState.FAILED);
        assertThat(failed.reason()).isEqualTo("mark-ready not confirmed: markReady failed");
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void aPrClosedWhileWaitingForChecks_isRefused() {
        var waiting = step(job(false));

        github.pull = new GitHubReader.PullView(SHA, false, "CLOSED", "release-1.0.0-rc5");
        github.allGreen();
        var refused = step(waiting);

        assertThat(refused.reason()).isEqualTo("pr is CLOSED while waiting for checks");
        assertThat(github.enqueued).isEmpty();
    }

    @Test
    void failedReadsInEveryWatchingPhase_areObservations() {
        var waiting = step(job(false));

        github.pullFails = Option.some("HTTP 502");
        assertThat(step(waiting).lastObservation()).isEqualTo("pull read failed: HTTP 502");
        github.pullFails = Option.none();
        github.failing.add("requiredChecks");
        var unread = step(waiting);

        assertThat(unread.state()).isEqualTo(JobState.WAITING_CHECKS);
        assertThat(unread.lastObservation()).isEqualTo("checks read failed: requiredChecks failed");
        github.failing.clear();
        var queued = queued();

        github.failing.add("queue");
        assertThat(step(queued).lastObservation()).isEqualTo("queue read failed: queue failed");
        var awaiting = queued.dropped("FAILED_CHECKS", clock.instant());

        github.failing.add("mergeGroupRun");
        assertThat(step(awaiting).lastObservation()).isEqualTo("merge-group run read failed: mergeGroupRun failed");
        github.failing.clear();
        github.run = Option.some(new RunView(7L, "u", "completed", "failure"));
        github.failing.add("jobs");
        var still = step(awaiting);

        assertThat(still.state()).isEqualTo(JobState.AWAITING_RUN);
        assertThat(still.lastObservation()).isEqualTo("run jobs read failed: jobs failed");
        assertThat(ledger.changes).doesNotContain(JobState.TIMEOUT, JobState.REFUSED, JobState.FAILED);
    }

    @Test
    void theShippedPorts_sendNothing_andSayWhy() {
        var writer = QueueWriter.pending();
        var reader = GitHubReader.unconfigured();
        var noWrite = "no GitHub write identity: the write-capable App is the owner's pending decision (#101)";
        var noRead = "no GitHub read identity is configured for temper-merge";

        assertThat(writer.markReady(REPO, 1).await().fold(Cause::message, _ -> "sent")).isEqualTo(noWrite);
        assertThat(writer.enqueue(REPO, 1, SHA).await().fold(Cause::message, _ -> "sent")).isEqualTo(noWrite);
        assertThat(List.of(reader.pull(REPO, 1).await().fold(Cause::message, _ -> "read"),
                           reader.requiredChecks(REPO, "b").await().fold(Cause::message, _ -> "read"),
                           reader.checkRuns(REPO, SHA).await().fold(Cause::message, _ -> "read"),
                           reader.queue(REPO, 1).await().fold(Cause::message, _ -> "read"),
                           reader.mergeGroupRun(REPO, 1).await().fold(Cause::message, _ -> "read"),
                           reader.jobs(REPO, 1).await().fold(Cause::message, _ -> "read"),
                           reader.jobLog(REPO, 1).await().fold(Cause::message, _ -> "read"))).containsOnly(noRead)
                  .hasSize(7);
    }

    static String fixture(String name) {
        try (InputStream in = EngineTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
