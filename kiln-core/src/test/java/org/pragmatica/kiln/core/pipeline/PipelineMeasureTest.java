package org.pragmatica.kiln.core.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.github.WorkflowRun;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The pipeline baseline's rules (#102): the decode of a real GraphQL answer (`search-sample.json`: the nodes of every
/// page of kiln's own search query for pragmaticalabs/pragmatica PRs merged into `release-1.0.0-rc5` since 2026-10-28,
/// recorded with `gh` on 2026-11-07, 166 PRs including #2784, #2803 and #2830, nothing synthesized), the removal
/// classifier (the kind of every removal in that sample is pinned), sync detection, the family split, the three-way
/// split and the weekly table.
class PipelineMeasureTest {
    static final String BASE = "release-1.0.0-rc5";
    static final Instant T0 = Instant.parse("2026-11-06T05:00:00Z");

    static String resource(String name) {
        try (InputStream in = PipelineMeasureTest.class.getResourceAsStream("/pipeline/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<MergedPr> sample() {
        return MergedPr.page(resource("search-sample.json"))
                       .map(page -> page.prs()
                                        .stream()
                                        .map(MergedPr.Listed::pr)
                                        .toList())
                       .unwrap();
    }

    static MergedPr pr(long number, List<MergedPr.Commit> commits, List<MergedPr.QueueEvent> queue) {
        return new MergedPr(number, "pr " + number, T0.plusSeconds(7200), "b/" + number, commits, false, queue);
    }

    /// A commit made an hour before T0, before any queue entry of the synthetic PRs.
    static MergedPr.Commit commit(String oid) {
        return commit(oid, -60);
    }

    static MergedPr.Commit commit(String oid, long minutes) {
        return new MergedPr.Commit(oid, "fix: " + oid, 1, T0.plusSeconds(minutes * 60));
    }

    static MergedPr.Commit merge(String oid, String headline) {
        return merge(oid, headline, -60);
    }

    static MergedPr.Commit merge(String oid, String headline, long minutes) {
        return new MergedPr.Commit(oid, headline, 2, T0.plusSeconds(minutes * 60));
    }

    static MergedPr.QueueEvent added(long minutes) {
        return new MergedPr.QueueEvent.Added(T0.plusSeconds(minutes * 60));
    }

    static MergedPr.QueueEvent.Removed removed(long minutes, String reason) {
        return new MergedPr.QueueEvent.Removed(T0.plusSeconds(minutes * 60), reason);
    }

    static MergedPr.QueueEvent forcePushed(long minutes) {
        return new MergedPr.QueueEvent.ForcePushed(T0.plusSeconds(minutes * 60));
    }

    static String oid(int index) {
        return String.format("%040x", index);
    }

    // ---- the real sample --------------------------------------------------------------------------------------------
    static MergedPr samplePr(long number) {
        return sample().stream()
                     .filter(pr -> pr.number() == number)
                     .findFirst()
                     .orElseThrow();
    }

    @Test
    void sample_decodesEveryPr_withItsRemovalReasons() {
        var prs = sample();
        var reasons = prs.stream()
                         .flatMap(pr -> Removal.removals(pr).stream())
                         .collect(Collectors.groupingBy(Removal::reason,
                                                        Collectors.counting()));

        assertThat(prs).hasSize(166);
        assertThat(reasons).isEqualTo(Map.of("failed_checks",
                                             11L,
                                             "manual",
                                             2L,
                                             "merge_conflict",
                                             2L,
                                             "checks_timed_out",
                                             2L));
    }

    /// Every removal of the real sample, by kind, read off the data by hand (commit dates against the entry windows):
    /// #2784's one commit (21:01) predates both entries (21:28, 21:54), so its failed_checks removal is a FLAKE; #2677 got
    /// d84fa019 (14:24) between its entries (12:37, 15:30), so it is a REAL_FAILURE; #2505 got only the sync merge
    /// eb4b3f18 (16:20) between 15:30 and 16:53, so it is a CONFLICT; the two `manual` removals (#2469, #2643) are MANUAL
    /// whatever followed them: removed by hand is neither a flake nor a failure.
    @Test
    void sample_everyRemovalKind_isPinned() {
        var kinds = sample().stream()
                          .filter(pr -> !Removal.removals(pr).isEmpty())
                          .collect(Collectors.toMap(MergedPr::number,
                                                    pr -> Removal.removals(pr)
                                                                 .stream()
                                                                 .map(removal -> removal.reason() + ":" + removal.kind())
                                                                 .toList()));

        assertThat(kinds).isEqualTo(Map.ofEntries(Map.entry(2469L, List.of("manual:MANUAL")),
                                                  Map.entry(2505L, List.of("failed_checks:CONFLICT")),
                                                  Map.entry(2643L,
                                                            List.of("manual:MANUAL",
                                                                    "merge_conflict:CONFLICT",
                                                                    "failed_checks:FLAKE")),
                                                  Map.entry(2647L,
                                                            List.of("checks_timed_out:FLAKE", "merge_conflict:CONFLICT")),
                                                  Map.entry(2661L, List.of("checks_timed_out:FLAKE")),
                                                  Map.entry(2675L, List.of("failed_checks:FLAKE")),
                                                  Map.entry(2677L, List.of("failed_checks:REAL_FAILURE")),
                                                  Map.entry(2698L, List.of("failed_checks:FLAKE")),
                                                  Map.entry(2711L, List.of("failed_checks:FLAKE")),
                                                  Map.entry(2717L, List.of("failed_checks:FLAKE")),
                                                  Map.entry(2725L, List.of("failed_checks:FLAKE")),
                                                  Map.entry(2726L, List.of("failed_checks:FLAKE")),
                                                  Map.entry(2727L, List.of("failed_checks:FLAKE")),
                                                  Map.entry(2784L, List.of("failed_checks:FLAKE"))));
    }

    /// #2803 and #2830 (the review's hand-checked PRs) left the queue only by merging, and each has one base sync.
    @Test
    void sample_2803and2830_mergedOnly_oneBaseSyncEach() {
        var f2b = PrMeasure.measure(samplePr(2803), BASE, List.of(), true);
        var preflight = PrMeasure.measure(samplePr(2830), BASE, List.of(), true);

        assertThat(samplePr(2803).commits()).hasSize(17);
        assertThat(samplePr(2803).queue()).hasSize(2);
        assertThat(f2b.removals()).isEmpty();
        assertThat(f2b.baseSyncs()).isEqualTo(1);
        assertThat(f2b.otherMerges()).isZero();
        assertThat(preflight.removals()).isEmpty();
        assertThat(preflight.baseSyncs()).isEqualTo(1);
        assertThat(samplePr(2830).commits().getLast().committed()).isEqualTo(Instant.parse("2026-11-07T17:56:01Z"));
    }

    @Test
    void sample_syncMerges_baseSyncsNameTheBase_otherMergesDoNot() {
        var byNumber = sample().stream()
                             .collect(Collectors.toMap(MergedPr::number,
                                                       pr -> PrMeasure.measure(pr,
                                                                               BASE,
                                                                               List.of(),
                                                                               true)));

        assertThat(byNumber.get(2615L).baseSyncs()).isEqualTo(4);
        assertThat(byNumber.get(2615L).otherMerges()).isZero();
        assertThat(byNumber.get(2711L).baseSyncs()).isEqualTo(2);
        assertThat(byNumber.get(2711L).otherMerges()).isEqualTo(1);
        assertThat(byNumber.get(2726L).baseSyncs() + byNumber.get(2726L).otherMerges()).isZero();
    }

    @Test
    void sample_prWithoutQueueEvents_hasNoRemovals() {
        var pr2426 = samplePr(2426);

        assertThat(pr2426.queue()).isEmpty();
        assertThat(Removal.removals(pr2426)).isEmpty();
    }

    /// #2469 was force-pushed on 10-28, before its first queue entry (11-05): outside every window, so it does not decide
    /// the removal.
    @Test
    void sample_forcePushBeforeTheEntry_isOutsideTheWindow() {
        assertThat(samplePr(2469).queue()).first().isInstanceOf(MergedPr.QueueEvent.ForcePushed.class);
        assertThat(Removal.removals(samplePr(2469))).extracting(Removal::detail)
                  .containsExactly("removed by hand; 1 sync merge(s) added");
    }

    @Test
    void graphQlErrors_failTheRead_neverAnEmptyList() {
        var refused = MergedPr.page("{\"data\":null,\"errors\":[{\"message\":\"Resource not accessible by integration\"}]}");

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(PipelineError.GraphQl.class))
               .onFailure(cause -> assertThat(cause.message()).contains("Resource not accessible by integration"));
    }

    // ---- removal classifier ----------------------------------------------------------------------------------------
    @Test
    void removal_reEnteredWithSameHead_isFlake() {
        var pr = pr(1,
                    List.of(commit(oid(1))),
                    List.of(added(0), removed(20, "failed_checks"), added(25), removed(60, "merged")));

        assertThat(Removal.removals(pr)).singleElement().extracting(Removal::kind).isEqualTo(Removal.Kind.FLAKE);
    }

    @Test
    void removal_nonMergeCommitAdded_isRealFailure() {
        var pr = pr(2,
                    List.of(commit(oid(1)), commit(oid(2), 30)),
                    List.of(added(0), removed(20, "failed_checks"), added(40), removed(90, "merged")));

        assertThat(Removal.removals(pr)).singleElement().extracting(Removal::kind).isEqualTo(Removal.Kind.REAL_FAILURE);
    }

    @Test
    void removal_onlySyncMergesAdded_isConflict() {
        var pr = pr(3,
                    List.of(commit(oid(1)), merge(oid(2), "merge: release-1.0.0-rc5 into rc6/f5b", 30)),
                    List.of(added(0), removed(20, "failed_checks"), added(40), removed(90, "merged")));

        assertThat(Removal.removals(pr)).singleElement().extracting(Removal::kind).isEqualTo(Removal.Kind.CONFLICT);
    }

    @Test
    void removal_mergeAndFixAdded_isRealFailure() {
        var pr = pr(4,
                    List.of(commit(oid(1)), merge(oid(2), "merge: release-1.0.0-rc5", 25), commit(oid(3), 30)),
                    List.of(added(0), removed(20, "failed_checks"), added(40), removed(90, "merged")));

        assertThat(Removal.removals(pr)).singleElement().extracting(Removal::kind).isEqualTo(Removal.Kind.REAL_FAILURE);
    }

    @Test
    void removal_reasonNamingConflict_isConflict_andKeepsRawReason() {
        var pr = pr(5,
                    List.of(commit(oid(1))),
                    List.of(added(0), removed(20, "merge_conflict"), added(25), removed(60, "merged")));

        assertThat(Removal.removals(pr)).singleElement()
                  .satisfies(removal -> assertThat(removal.kind()).isEqualTo(Removal.Kind.CONFLICT))
                  .satisfies(removal -> assertThat(removal.reason()).isEqualTo("merge_conflict"));
    }

    /// A commit exactly at the next entry is inside the window (it is `(entry, next]`); one exactly at the entry is not.
    @Test
    void removal_windowBounds_entryExcluded_nextEntryIncluded() {
        var atNext = pr(17, List.of(commit(oid(1), 25)), List.of(added(0), removed(20, "failed_checks"), added(25)));
        var atEntry = pr(18, List.of(commit(oid(1), 0)), List.of(added(0), removed(20, "failed_checks"), added(25)));

        assertThat(Removal.removals(atNext)).extracting(Removal::kind).containsExactly(Removal.Kind.REAL_FAILURE);
        assertThat(Removal.removals(atEntry)).extracting(Removal::kind).containsExactly(Removal.Kind.FLAKE);
    }

    @Test
    void removal_forcePushInTheWindow_isRealFailure_andNoEntryBeforeIsUnclassified() {
        var rewritten = pr(6,
                           List.of(commit(oid(9))),
                           List.of(added(0),
                                   removed(20, "failed_checks"),
                                   forcePushed(22),
                                   added(25),
                                   removed(60, "merged")));
        var unknown = pr(7,
                         List.of(commit(oid(1))),
                         List.of(removed(20, "failed_checks"), added(25), removed(60, "merged")));

        assertThat(Removal.removals(rewritten)).singleElement()
                  .satisfies(removal -> assertThat(removal.kind()).isEqualTo(Removal.Kind.REAL_FAILURE))
                  .satisfies(removal -> assertThat(removal.detail()).isEqualTo("head force-pushed"));
        assertThat(Removal.removals(unknown)).singleElement()
                  .extracting(Removal::kind)
                  .isEqualTo(Removal.Kind.UNCLASSIFIED);
    }

    @Test
    void removal_mergedRemovalIsNeverCounted() {
        var pr = pr(8, List.of(commit(oid(1))), List.of(added(0), removed(60, "MERGED")));

        assertThat(Removal.removals(pr)).isEmpty();
    }

    @Test
    void removal_twoFailures_eachComparedWithTheNextEntry() {
        var pr = pr(9,
                    List.of(commit(oid(1)), commit(oid(2), 35)),
                    List.of(added(0),
                            removed(10, "failed_checks"),
                            added(12),
                            removed(30, "failed_checks"),
                            added(40),
                            removed(80, "merged")));

        assertThat(Removal.removals(pr)).extracting(Removal::kind)
                  .containsExactly(Removal.Kind.FLAKE, Removal.Kind.REAL_FAILURE);
    }

    // ---- family split -----------------------------------------------------------------------------------------------
    @Test
    void family_namesFileIntoTheirFamilies() {
        assertThat(Family.filed("CI", "test-group-g3")).isEqualTo(new Family.Filed(Family.FULL_REACTOR, true));
        assertThat(Family.filed("CI", "build-and-test")).isEqualTo(new Family.Filed(Family.OTHER, true));
        assertThat(Family.filed("CI", "push-dedupe")).isEqualTo(new Family.Filed(Family.OTHER, true));
        assertThat(Family.FULL).doesNotContain("build-and-test", "push-dedupe")
                  .containsExactlyInAnyOrder("test-group-g1",
                                             "test-group-g2",
                                             "test-group-g3",
                                             "test-group-g4",
                                             "static-gates",
                                             "stub-suites",
                                             "quarantine");
        assertThat(Family.filed("CI", "test-group-g1 (linux)")).isEqualTo(new Family.Filed(Family.FULL_REACTOR, true));
        assertThat(Family.filed("CI", "ember-shard-2")).isEqualTo(new Family.Filed(Family.EMBER, true));
        assertThat(Family.filed("FSM2 engine (Ember shards)", "setup")).isEqualTo(new Family.Filed(Family.EMBER, true));
        assertThat(Family.filed("CI", "forge-tests")).isEqualTo(new Family.Filed(Family.FORGE, true));
        assertThat(Family.filed("Heavy forge tests", "run")).isEqualTo(new Family.Filed(Family.FORGE, true));
        assertThat(Family.filed("CI", "consensus-shard-1")).isEqualTo(new Family.Filed(Family.CONSENSUS, true));
        assertThat(Family.filed("Changelog check", "changelog")).isEqualTo(new Family.Filed(Family.OTHER, true));
    }

    @Test
    void family_renamedShard_isOtherAndListedAsUnknown() {
        var renamed = new RunAttempt(1,
                                     1,
                                     "CI",
                                     RunAttempt.PULL_REQUEST,
                                     oid(1),
                                     "b",
                                     List.of(job("test-group-g5", 0, 600), job("test-group-g1", 0, 300)));
        var measure = PrMeasure.measure(pr(10, List.of(commit(oid(1))), List.of()),
                                        BASE,
                                        List.of(renamed),
                                        true);

        assertThat(Family.filed("CI", "test-group-g5")).isEqualTo(new Family.Filed(Family.OTHER, false));
        assertThat(measure.unknown()).containsExactly("CI / test-group-g5");
        assertThat(measure.otherSeconds()).isEqualTo(600);
        assertThat(measure.fullReactorSeconds()).isEqualTo(300);
    }

    // ---- machine time and the three-way split -----------------------------------------------------------------------
    static RunAttempt.Job job(String name, long startMinute, long seconds) {
        var start = T0.plusSeconds(startMinute * 60);

        return new RunAttempt.Job(name, some(start), some(start.plusSeconds(seconds)));
    }

    static RunAttempt attempt(long run, long attempt, String event, String sha, List<RunAttempt.Job> jobs) {
        return new RunAttempt(run, attempt, "CI", event, sha, "b", jobs);
    }

    @Test
    void jobs_skippedOrNeverStarted_countZero() {
        var never = new RunAttempt.Job("quarantine", none(), none());
        var skipped = new RunAttempt.Job("quarantine", some(T0), some(T0));
        var running = new RunAttempt.Job("quarantine", some(T0), none());

        assertThat(never.seconds() + skipped.seconds() + running.seconds()).isZero();
    }

    @Test
    void threeWaySplit_prRuns_firstQueueEntry_andRerunsAfterRemoval() {
        var pr = pr(11,
                    List.of(commit(oid(1)), commit(oid(2))),
                    List.of(added(30), removed(60, "failed_checks"), added(90), removed(150, "merged")));
        var attempts = List.of(attempt(1, 1, RunAttempt.PULL_REQUEST, oid(1), List.of(job("test-group-g1", 0, 1000))),
                               attempt(2, 1, RunAttempt.MERGE_GROUP, oid(7), List.of(job("test-group-g1", 31, 2000))),
                               attempt(1, 2, RunAttempt.PULL_REQUEST, oid(1), List.of(job("ember-shard-1", 61, 400))),
                               attempt(3, 1, RunAttempt.PULL_REQUEST, oid(2), List.of(job("forge-tests", 70, 300))),
                               attempt(4, 1, RunAttempt.MERGE_GROUP, oid(8), List.of(job("consensus-shard-1", 91, 50))));
        var measure = PrMeasure.measure(pr, BASE, attempts, true);

        assertThat(measure.prSeconds()).isEqualTo(1000);
        assertThat(measure.queueSeconds()).isEqualTo(2000);
        assertThat(measure.rerunSeconds()).isEqualTo(750);
        assertThat(measure.totalSeconds()).isEqualTo(3750);
        assertThat(measure.runs()).isEqualTo(4);
        assertThat(measure.attempts()).isEqualTo(5);
        assertThat(measure.fullReactorSeconds()).isEqualTo(3000);
        assertThat(measure.emberSeconds()).isEqualTo(400);
        assertThat(measure.forgeSeconds()).isEqualTo(300);
        assertThat(measure.consensusSeconds()).isEqualTo(50);
        assertThat(measure.otherSeconds()).isZero();
    }

    @Test
    void threeWaySplit_withoutRemoval_hasNoReruns() {
        var pr = pr(12, List.of(commit(oid(1))), List.of(added(30), removed(90, "merged")));
        var attempts = List.of(attempt(1, 1, RunAttempt.PULL_REQUEST, oid(1), List.of(job("static-gates", 0, 100))),
                               attempt(2, 1, RunAttempt.MERGE_GROUP, oid(7), List.of(job("static-gates", 31, 200))),
                               attempt(1, 2, RunAttempt.PULL_REQUEST, oid(1), List.of(job("static-gates", 40, 50))));
        var measure = PrMeasure.measure(pr, BASE, attempts, true);

        assertThat(measure.prSeconds()).isEqualTo(150);
        assertThat(measure.queueSeconds()).isEqualTo(200);
        assertThat(measure.rerunSeconds()).isZero();
    }

    @Test
    void syncMergeHours_arePrRunsOnBaseSyncMergesOnly() {
        var pr = pr(13,
                    List.of(commit(oid(1)),
                            merge(oid(2), "Merge remote-tracking branch 'origin/release-1.0.0-rc5' into x"),
                            merge(oid(3), "merge: feature/rc6-scaling into rc6/f5b")),
                    List.of());
        var attempts = List.of(attempt(1, 1, RunAttempt.PULL_REQUEST, oid(1), List.of(job("static-gates", 0, 100))),
                               attempt(2, 1, RunAttempt.PULL_REQUEST, oid(2), List.of(job("static-gates", 5, 200))),
                               attempt(3, 1, RunAttempt.PULL_REQUEST, oid(3), List.of(job("static-gates", 9, 40))));
        var measure = PrMeasure.measure(pr, BASE, attempts, true);

        assertThat(measure.baseSyncs()).isEqualTo(1);
        assertThat(measure.otherMerges()).isEqualTo(1);
        assertThat(measure.syncSeconds()).isEqualTo(200);
    }

    // ---- run membership ---------------------------------------------------------------------------------------------
    static WorkflowRun run(String event, String sha, String branch) {
        return new WorkflowRun(1,
                               "CI",
                               "completed",
                               some("success"),
                               sha,
                               "https://x",
                               T0,
                               some(event),
                               some(branch),
                               1,
                               none(),
                               none(),
                               none(),
                               none(),
                               List.of());
    }

    @Test
    void belongs_prRunsByCommit_queueRunsByQueueBranch() {
        var pr = pr(2711, List.of(commit(oid(1))), List.of());

        assertThat(RunAttempt.belongs(run("pull_request", oid(1), "b/2711"), pr, BASE)).isTrue();
        assertThat(RunAttempt.belongs(run("pull_request", oid(5), "b/2711"), pr, BASE)).isFalse();
        assertThat(RunAttempt.belongs(run("merge_group", oid(5), "gh-readonly-queue/release-1.0.0-rc5/pr-2711-" + oid(5)),
                                      pr,
                                      BASE)).isTrue();
        assertThat(RunAttempt.belongs(run("merge_group",
                                          oid(5),
                                          "gh-readonly-queue/release-1.0.0-rc5/pr-27110-" + oid(5)),
                                      pr,
                                      BASE)).isFalse();
        assertThat(RunAttempt.belongs(run("merge_group",
                                          oid(5),
                                          "gh-readonly-queue/feature/rc6-scaling/pr-2711-" + oid(5)),
                                      pr,
                                      BASE)).isFalse();
        assertThat(RunAttempt.belongs(run("push", oid(1), "b/2711"), pr, BASE)).isFalse();
    }

    @Test
    void jobsPage_overOnePage_isRefused() {
        var json = "{\"total_count\":101,\"jobs\":[{\"name\":\"a\",\"started_at\":null,\"completed_at\":null}]}";

        assertThat(RunAttempt.jobs(7, json).isFailure()).isTrue();
        RunAttempt.jobs(7, json).onFailure(cause -> assertThat(cause).isInstanceOf(PipelineError.JobsOverPage.class));
    }

    // ---- weekly table -----------------------------------------------------------------------------------------------
    @Test
    void weeks_isoWeeksUtc_countUnmeasuredPrs() {
        var sunday = PrMeasure.measure(new MergedPr(1,
                                                    "a",
                                                    Instant.parse("2026-11-01T23:59:59Z"),
                                                    "a",
                                                    List.of(),
                                                    false,
                                                    List.of()),
                                       BASE,
                                       List.of(attempt(1,
                                                       1,
                                                       RunAttempt.PULL_REQUEST,
                                                       oid(1),
                                                       List.of(job("static-gates", 0, 3600)))),
                                       true);
        var monday = PrMeasure.measure(new MergedPr(2,
                                                    "b",
                                                    Instant.parse("2026-11-02T00:00:00Z"),
                                                    "b",
                                                    List.of(),
                                                    false,
                                                    List.of()),
                                       BASE,
                                       List.of(),
                                       false);
        var rows = WeekRow.weeks(List.of(new WeekRow.Entry(1, sunday.mergedAt(), some(sunday)),
                                         new WeekRow.Entry(2, monday.mergedAt(), some(monday)),
                                         new WeekRow.Entry(3, Instant.parse("2026-11-06T10:00:00Z"), none())));

        assertThat(rows).extracting(WeekRow::week).containsExactly("2026-W44", "2026-W45");
        assertThat(rows.getFirst().totalSeconds()).isEqualTo(3600);
        assertThat(rows.getLast().prs()).isEqualTo(2);
        assertThat(rows.getLast().measured()).isEqualTo(1);
        assertThat(rows.getLast().complete()).isZero();
    }

    // ---- decode details and sums (PIT follow-ups) ---------------------------------------------------------------------
    static String node(long totalCount) {
        return "{\"number\":5,\"title\":\"t\",\"mergedAt\":\"2026-11-06T06:00:00Z\",\"headRefName\":\"h\","
             + "\"commits\":{\"totalCount\":" + totalCount
             + ",\"nodes\":[{\"commit\":{\"oid\":\"" + oid(1)
             + "\",\"messageHeadline\":\"x\",\"committedDate\":\"2026-11-06T04:00:00Z\",\"parents\":{\"totalCount\":1}}}]},"
             + "\"timelineItems\":{\"nodes\":["
             + "{\"__typename\":\"HeadRefForcePushedEvent\",\"createdAt\":\"2026-11-06T04:30:00Z\"},"
             + "{\"__typename\":\"RemovedFromMergeQueueEvent\",\"createdAt\":\"2026-11-06T05:00:00Z\",\"reason\":\"failed_checks\"},"
             + "{\"__typename\":\"LabeledEvent\"}]}}";
    }

    @Test
    void decode_commitDate_forcePush_andRemoval_otherItemsSkipped() {
        var pr = MergedPr.one(node(1)).unwrap();

        assertThat(pr.commits()).containsExactly(new MergedPr.Commit(oid(1),
                                                                     "x",
                                                                     1,
                                                                     Instant.parse("2026-11-06T04:00:00Z")));
        assertThat(pr.queue()).containsExactly(new MergedPr.QueueEvent.ForcePushed(Instant.parse("2026-11-06T04:30:00Z")),
                                               new MergedPr.QueueEvent.Removed(Instant.parse("2026-11-06T05:00:00Z"),
                                                                               "failed_checks"));
    }

    @Test
    void decode_commitWithoutDate_failsTheRead() {
        assertThat(MergedPr.one(node(1).replace(",\"committedDate\":\"2026-11-06T04:00:00Z\"", "")).isFailure()).isTrue();
    }

    /// A timeline with a next page is flagged on the decoded PR; a whole one is not.
    @Test
    void decode_timelineWithANextPage_isFlagged_aWholeOneIsNot() {
        var more = node(1).replace("\"timelineItems\":{\"nodes\"",
                                   "\"timelineItems\":{\"pageInfo\":{\"hasNextPage\":true},\"nodes\"");
        var last = node(1).replace("\"timelineItems\":{\"nodes\"",
                                   "\"timelineItems\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\"");

        assertThat(more).contains("\"hasNextPage\":true");
        assertThat(MergedPr.one(more).unwrap().timelineTruncated()).isTrue();
        assertThat(MergedPr.one(last).unwrap().timelineTruncated()).isFalse();
        assertThat(MergedPr.one(node(1)).unwrap().timelineTruncated()).isFalse();
    }

    /// An excluded PR is complete (never read again), INCOMPLETE, carries its reason and no hours or removals; a week counts
    /// it but sums only the PRs around it, even when an excluded record carries hours.
    @Test
    void excluded_isCompleteIncompleteAndCountedApart_neverSummed() {
        var pr = MergedPr.one(node(1)).unwrap();
        var excluded = PrMeasure.excluded(pr, PrMeasure.TIMELINE_OVER_PAGE);
        var normal = PrMeasure.measure(new MergedPr(2, "b", pr.mergedAt(), "b", List.of(), false, List.of()),
                                       BASE,
                                       List.of(attempt(1, 1, RunAttempt.PULL_REQUEST, oid(1), List.of(job("static-gates", 0, 3600)))),
                                       true);
        var loaded = new PrMeasure(3, "c", pr.mergedAt(), excluded.week(), true, 1, 1, 7200, 7200, 7200, 7200, 7200, 7200, 7200, 7200,
                                   List.of(), 5, 5, 7200, List.of(), false, 0, "reason");
        var row = WeekRow.weeks(List.of(new WeekRow.Entry(1, pr.mergedAt(), some(excluded)),
                                        new WeekRow.Entry(2, pr.mergedAt(), some(normal)),
                                        new WeekRow.Entry(3, pr.mergedAt(), some(loaded)))).getFirst();

        assertThat(excluded.complete()).isTrue();
        assertThat(excluded.incomplete()).isTrue();
        assertThat(excluded.excluded()).isTrue();
        assertThat(excluded.excludedReason()).contains("more than 100 queue events");
        assertThat(excluded.totalSeconds()).isZero();
        assertThat(excluded.removals()).isEmpty();
        assertThat(normal.excluded()).isFalse();
        assertThat(normal.incomplete()).isFalse();
        assertThat(row.prs()).isEqualTo(3);
        assertThat(row.measured()).isEqualTo(3);
        assertThat(row.incomplete()).isEqualTo(2);
        assertThat(row.excluded()).isEqualTo(2);
        assertThat(row.totalSeconds()).isEqualTo(3600);
        assertThat(row.prSeconds()).isEqualTo(3600);
        assertThat(row.syncSeconds()).isZero();
        assertThat(row.baseSyncs()).isZero();
    }

    @Test
    void decode_truncatedOnlyWhenTotalExceedsNodes() {
        assertThat(MergedPr.one(node(1)).unwrap().truncated()).isFalse();
        assertThat(MergedPr.one(node(2)).unwrap().truncated()).isTrue();
    }

    @Test
    void decode_pageCursor_onlyWhenThereIsANextPage_andNodeRoundTrips() {
        var more = "{\"data\":{\"search\":{\"pageInfo\":{\"hasNextPage\":true,\"endCursor\":\"c9\"},\"nodes\":[" + node(1)
                 + "]}}}";
        var last = "{\"data\":{\"search\":{\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":\"c9\"},\"nodes\":[]}}}";
        var page = MergedPr.page(more).unwrap();

        assertThat(page.after()).isEqualTo(some("c9"));
        assertThat(MergedPr.page(last).unwrap().after()).isEqualTo(none());
        assertThat(MergedPr.one(page.prs().getFirst().node()).unwrap()).isEqualTo(page.prs().getFirst().pr());
    }

    @Test
    void removal_lastRemovalNotFollowed_looksUpToTheMerge() {
        var pr = pr(14, List.of(commit(oid(1)), commit(oid(2), 30)), List.of(added(0), removed(20, "failed_checks")));
        var same = pr(15, List.of(commit(oid(1))), List.of(added(0), removed(20, "failed_checks")));

        assertThat(Removal.removals(pr)).extracting(Removal::kind).containsExactly(Removal.Kind.REAL_FAILURE);
        assertThat(Removal.removals(same)).extracting(Removal::kind).containsExactly(Removal.Kind.FLAKE);
    }

    @Test
    void runAttempt_takesTheRunsFields_andAWholePageIsAccepted() {
        var jobs = RunAttempt.jobs(7,
                                   "{\"total_count\":1,\"jobs\":[{\"name\":\"a\",\"started_at\":null,\"completed_at\":null}]}");
        var attempt = RunAttempt.runAttempt(run("merge_group", oid(5), "q/b"), 2, jobs.unwrap());

        assertThat(attempt).isEqualTo(new RunAttempt(1, 2, "CI", "merge_group", oid(5), "q/b", jobs.unwrap()));
    }

    /// A "re-run failed jobs" attempt lists the jobs it did not re-run with the earlier attempt's times: only a job that
    /// started at or after its `run_started_at` counts, and that start (not the earliest listed job) files the attempt.
    @Test
    void rerunAttempt_countsOnlyJobsStartedInIt_andStartsAtItsRunStart() {
        var start = T0.plusSeconds(60 * 60);
        var listed = List.of(job("test-group-g1", 0, 1000),
                             job("ember-shard-2", 61, 400),
                             new RunAttempt.Job("quarantine", none(), none()));
        var rerun = new RunAttempt(1, 2, "CI", RunAttempt.PULL_REQUEST, oid(1), "b", some(start), listed);
        var stored = RunAttempt.stored(some(start), listed).unwrap();

        assertThat(rerun.jobs()).extracting(RunAttempt.Job::name).containsExactly("ember-shard-2", "quarantine");
        assertThat(rerun.seconds()).isEqualTo(400);
        assertThat(rerun.started()).isEqualTo(some(start));
        assertThat(PrMeasure.split(rerun, some(T0.plusSeconds(30 * 60)))).isEqualTo(PrMeasure.Split.RERUN);
        assertThat(PrMeasure.split(rerun, some(T0.plusSeconds(60 * 60 + 30)))).isEqualTo(PrMeasure.Split.PR);
        assertThat(RunAttempt.storedStart(stored).unwrap()).isEqualTo(some(start));
        assertThat(RunAttempt.jobs(1, stored).unwrap()).extracting(RunAttempt.Job::name)
                  .containsExactly("ember-shard-2", "quarantine");
        assertThat(RunAttempt.storedStart(RunAttempt.stored(listed).unwrap()).unwrap()).isEqualTo(none());
        assertThat(new RunAttempt(1, 1, "CI", RunAttempt.PULL_REQUEST, oid(1), "b", listed).jobs()).hasSize(3);
        assertThat(RunAttempt.runStarted("{\"run_attempt\":2,\"run_started_at\":\"2026-11-07T18:27:08Z\"}").unwrap()).isEqualTo(Instant.parse("2026-11-07T18:27:08Z"));
        assertThat(RunAttempt.runStarted("{\"run_attempt\":2}").isFailure()).isTrue();
    }

    @Test
    void weeks_sumRemovalsByKindAndSyncs() {
        var flake = pr(16,
                       List.of(commit(oid(1)),
                               merge(oid(2), "merge: release-1.0.0-rc5 into x"),
                               merge(oid(3), "merge: other"),
                               commit(oid(4), 32)),
                       List.of(removed(5, "failed_checks"),
                               added(8),
                               removed(10, "failed_checks"),
                               added(12),
                               removed(20, "merge_conflict"),
                               added(25),
                               removed(30, "failed_checks"),
                               added(35),
                               removed(40, "manual"),
                               added(45),
                               removed(90, "merged")));
        var measure = PrMeasure.measure(flake, BASE, List.of(), true);
        var row = WeekRow.weeks(List.of(new WeekRow.Entry(16, measure.mergedAt(), some(measure)))).getFirst();

        assertThat(measure.removalCount(Removal.Kind.FLAKE)).isEqualTo(1);
        assertThat(row.removals()).isEqualTo(5);
        assertThat(row.manual()).isEqualTo(1);
        assertThat(row.flakes()).isEqualTo(1);
        assertThat(row.conflicts()).isEqualTo(1);
        assertThat(row.realFailures()).isEqualTo(1);
        assertThat(row.unclassified()).isEqualTo(1);
        assertThat(row.baseSyncs()).isEqualTo(1);
        assertThat(row.otherMerges()).isEqualTo(1);
    }
}
