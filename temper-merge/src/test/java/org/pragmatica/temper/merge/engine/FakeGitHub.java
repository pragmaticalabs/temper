package org.pragmatica.temper.merge.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// A scripted GitHub for the runner's tests: the reads answer what a test set, the writes are counted and never sent
/// anywhere (this module has no write implementation, #101).
public final class FakeGitHub implements GitHubReader, QueueWriter {
    public static final String REPO = "pragmaticalabs/pragmatica";
    public static final String SHA = "584bc4e76775a13009d1bb870b81f7b6e093e9e2";
    public static final String OTHER = "0e3ee7480f043870c73e823336fcd7172e68db73";

    public PullView pull = new PullView(SHA, false, "OPEN", "release-1.0.0-rc5");
    /// Required checks per source: `required` is the ruleset source, `protection` the branch-protection source.
    public List<String> required = List.of("build-and-test", "forge-tests");
    public List<String> protection = List.of();
    public List<CheckRun> runs = List.of();
    public QueueView queue = new QueueView.Absent();
    public Option<RunView> run = Option.none();
    public List<JobView> jobs = List.of();
    public final Map<Long, String> logs = new HashMap<>();
    public Option<String> pullFails = Option.none();
    public Option<String> enqueueFails = Option.none();
    /// Reads and writes that fail, by method name, with `<name> failed` as the cause.
    public final Set<String> failing = new HashSet<>();
    public int markReadyCalls;
    public final List<String> enqueued = new ArrayList<>();
    /// Shared with [Ledger#trace] by a test that pins the order of records and sends.
    public List<String> trace = new ArrayList<>();
    public int checkReads;
    public int queueReads;

    public static CheckRun run(String name, String status, String conclusion, int minute) {
        return new CheckRun(name,
                            status,
                            conclusion,
                            Instant.parse("2026-11-08T10:00:00Z").plus(Duration.ofMinutes(minute)));
    }

    public void allGreen() {
        runs = GitHubReader.union(required, protection)
                           .stream()
                           .map(name -> run(name, "completed", "success", 1))
                           .toList();
    }

    @Override
    public Promise<PullView> pull(String repo, long pr) {
        return pullFails.fold(() -> Promise.success(pull),
                              why -> Causes.cause(why).promise());
    }

    @Override
    public Promise<List<String>> rulesetChecks(String repo, String base) {
        if (failing.contains("requiredChecks")) {
            return Causes.cause("requiredChecks failed").promise();
        }

        checkReads++;

        return Promise.success(required);
    }

    @Override
    public Promise<List<String>> protectionChecks(String repo, String base) {
        return Promise.success(protection);
    }

    @Override
    public Promise<List<CheckRun>> checkRuns(String repo, String sha) {
        return Promise.success(runs);
    }

    @Override
    public Promise<QueueView> queue(String repo, long pr) {
        if (failing.contains("queue")) {
            return Causes.cause("queue failed").promise();
        }

        queueReads++;

        return Promise.success(queue);
    }

    @Override
    public Promise<Option<RunView>> mergeGroupRun(String repo, long pr) {
        if (failing.contains("mergeGroupRun")) {
            return Causes.cause("mergeGroupRun failed").promise();
        }

        return Promise.success(run);
    }

    @Override
    public Promise<List<JobView>> jobs(String repo, long runId) {
        if (failing.contains("jobs")) {
            return Causes.cause("jobs failed").promise();
        }

        return Promise.success(jobs);
    }

    @Override
    public Promise<String> jobLog(String repo, long jobId) {
        return Option.option(logs.get(jobId)).fold(() -> Causes.cause("log not served").promise(),
                                                   Promise::success);
    }

    @Override
    public Promise<Unit> markReady(String repo, long pr) {
        if (failing.contains("markReady")) {
            return Causes.cause("markReady failed").promise();
        }

        markReadyCalls++;
        pull = new PullView(pull.head(), false, pull.state(), pull.base());

        return Promise.unitPromise();
    }

    @Override
    public Promise<Unit> enqueue(String repo, long pr, String expectedHeadOid) {
        enqueued.add(expectedHeadOid);
        trace.add("enqueue");

        return enqueueFails.fold(Promise::unitPromise,
                                 why -> Causes.cause(why).promise());
    }

    /// A clock a test moves by hand.
    public static final class Clock implements InstantSource {
        private Instant now = Instant.parse("2026-11-08T12:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        public void advance(Duration by) {
            now = now.plus(by);
        }
    }

    /// The ledger in memory: every recorded job, and the state changes in order.
    public static final class Ledger implements JobLedger {
        public final List<JobState> changes = new ArrayList<>();
        public final List<Job> recorded = new ArrayList<>();
        public final List<String> trace = new ArrayList<>();
        /// A record into this state fails, as a store that cannot write would.
        public JobState failOn;

        @Override
        public Promise<Job> record(Job before, Job after) {
            if (after.state() == failOn) {
                return Causes.cause("store down").promise();
            }

            trace.add(after.state().name());
            if (before.state() != after.state()) {
                changes.add(after.state());
            }

            recorded.add(after);

            return Promise.success(after);
        }
    }
}
