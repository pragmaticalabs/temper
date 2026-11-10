package org.pragmatica.kiln.service.web;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.pipeline.MergedPr;
import org.pragmatica.kiln.core.pipeline.PrMeasure;
import org.pragmatica.kiln.core.pipeline.RunAttempt;
import org.pragmatica.kiln.service.api.PipelineJson;
import org.pragmatica.kiln.service.pipeline.PipelineScope;
import org.pragmatica.kiln.service.pipeline.PipelineStatus;
import org.pragmatica.kiln.service.pipeline.PipelineView;
import org.pragmatica.kiln.service.store.PipelineStore;

import org.junit.jupiter.api.Test;

import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.ago;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The Pipeline tab (#102): the weekly table with its per-PR drill-down, "N of M PRs measured", the state with its reason
/// (a failure marks the tab red), unknown job names listed, and every GitHub text escaped.
class PipelinePanelTest {
    static final String BASE = "release-1.0.0-rc5";
    static final String SHA_A = "a".repeat(40);
    static final String SHA_B = "b".repeat(40);

    static RunAttempt.Job job(String name, Instant start, long seconds) {
        return new RunAttempt.Job(name, some(start), some(start.plusSeconds(seconds)));
    }

    static PrMeasure measure(long number, String title, Instant mergedAt, boolean complete) {
        return measure(number, title, mergedAt, complete, 0);
    }

    /// The sync merge B is committed between the two queue entries, so the removal reads CONFLICT (a sync only).
    static PrMeasure measure(long number, String title, Instant mergedAt, boolean complete, int incompleteRuns) {
        var pr = new MergedPr(number,
                              title,
                              mergedAt,
                              "feat-" + number,
                              List.of(new MergedPr.Commit(SHA_A, "fix", 1, mergedAt.minusSeconds(9500)),
                                      new MergedPr.Commit(SHA_B,
                                                          "merge: release-1.0.0-rc5 into feat",
                                                          2,
                                                          mergedAt.minusSeconds(4000))),
                              false,
                              List.of(new MergedPr.QueueEvent.Added(mergedAt.minusSeconds(7200)),
                                      new MergedPr.QueueEvent.Removed(mergedAt.minusSeconds(5400), "failed_checks"),
                                      new MergedPr.QueueEvent.Added(mergedAt.minusSeconds(3600)),
                                      new MergedPr.QueueEvent.Removed(mergedAt, "merged")));
        var attempts = List.of(new RunAttempt(1,
                                              1,
                                              "CI",
                                              RunAttempt.PULL_REQUEST,
                                              SHA_A,
                                              "feat",
                                              List.of(job("test-group-g1", mergedAt.minusSeconds(9000), 3600))),
                               new RunAttempt(2,
                                              1,
                                              "CI",
                                              RunAttempt.MERGE_GROUP,
                                              "c".repeat(40),
                                              "q",
                                              List.of(job("ember-shard-1", mergedAt.minusSeconds(7000), 1800))),
                               new RunAttempt(3,
                                              1,
                                              "CI",
                                              RunAttempt.PULL_REQUEST,
                                              SHA_B,
                                              "feat",
                                              List.of(job("renamed-<shard>", mergedAt.minusSeconds(5000), 900))));

        return PrMeasure.measure(pr, BASE, attempts, complete, incompleteRuns);
    }

    static PipelineView view(PipelineStatus status) {
        var first = measure(2711, "rc6 <f5b> & sync", Instant.parse("2026-11-01T20:00:00Z"), true);
        var second = measure(2726, "gauge tests", Instant.parse("2026-11-06T06:19:31Z"), false);
        var rows = List.of(new PipelineStore.Row(2711, first.mergedAt(), "{}", some(first)),
                           new PipelineStore.Row(2726, second.mergedAt(), "{}", some(second)),
                           new PipelineStore.Row(2729, Instant.parse("2026-11-06T06:34:27Z"), "{}", none()));

        return new PipelineView(PipelineScope.pipelineScope("pragmaticalabs/pragmatica"), status, rows);
    }

    static PipelineStatus filling() {
        return PipelineStatus.never(ago(3600)).then(PipelineStatus.Kind.FILLING, "1 of 3 PRs measured", ago(60));
    }

    static DashboardView dashboard(PipelineStatus status) {
        return WebFixtures.fresh().withPipeline(view(status));
    }

    @Test
    void golden_pipelineTab() {
        Golden.assertGolden("pipeline-filling", PipelinePanel.render(dashboard(filling()), NOW));
    }

    @Test
    void panel_showsProgressWeeksAndDrillDown() {
        var html = PipelinePanel.render(dashboard(filling()), NOW);

        assertThat(html).contains("1 of 3 PRs measured")
                  .contains("<td>2026-W44</td><td>1 / 1</td><td>1.8</td>")
                  .contains("<td>2026-W45</td><td>1 / 2</td>")
                  .contains("<summary>2026-W45: 1 PRs measured</summary>")
                  .contains("#2726</a> (runs in progress)")
                  .contains("conflict (failed_checks)")
                  .contains("/api/v1/pipeline/baseline")
                  .doesNotContain("STALE");
    }

    /// A removal by hand is MANUAL, counted apart from flakes and failures, in the week, the PR row and the JSON.
    @Test
    void manualRemoval_isCountedApart_inTheTabAndTheJson() {
        var mergedAt = Instant.parse("2026-11-06T06:19:31Z");
        var pr = new MergedPr(2643,
                              "by hand",
                              mergedAt,
                              "feat-2643",
                              List.of(new MergedPr.Commit(SHA_A, "fix", 1, mergedAt.minusSeconds(9500))),
                              false,
                              List.of(new MergedPr.QueueEvent.Added(mergedAt.minusSeconds(7200)),
                                      new MergedPr.QueueEvent.Removed(mergedAt.minusSeconds(5400), "manual"),
                                      new MergedPr.QueueEvent.Added(mergedAt.minusSeconds(3600)),
                                      new MergedPr.QueueEvent.Removed(mergedAt, "merged")));
        var measure = PrMeasure.measure(pr, BASE, List.of(), true);
        var view = new PipelineView(PipelineScope.pipelineScope("pragmaticalabs/pragmatica"),
                                    filling(),
                                    List.of(new PipelineStore.Row(2643, mergedAt, "{}", some(measure))));
        var html = PipelinePanel.render(WebFixtures.fresh().withPipeline(view),
                                        NOW);
        var json = PipelineJson.pipelineJson(view);

        assertThat(html).contains("removals (flake / real / conflict / manual / ?)")
                  .contains("<td>1 (0 / 0 / 0 / 1 / 0)</td>")
                  .contains("manual (manual)");
        assertThat(json.weeks()).singleElement()
                  .satisfies(week -> assertThat(week.manual()).isEqualTo(1))
                  .satisfies(week -> assertThat(week.flakes()).isZero());
        assertThat(json.prs()).singleElement()
                  .satisfies(row -> assertThat(row.removals()).extracting(removal -> removal.kind())
                                              .containsExactly("MANUAL"));
    }

    /// A PR that could not be measured reads INCOMPLETE with its reason in its row, in its week and in the scope line, as a
    /// count of PRs left out of the sums.
    @Test
    void panel_marksAnExcludedPr_incompleteWithItsReason_andCountsIt() {
        var excluded = PrMeasure.excluded(new MergedPr(2804,
                                                       "big",
                                                       Instant.parse("2026-11-07T17:17:03Z"),
                                                       "feat",
                                                       List.of(),
                                                       false,
                                                       List.of()),
                                          PrMeasure.TIMELINE_OVER_PAGE);
        var view = new PipelineView(PipelineScope.pipelineScope("pragmaticalabs/pragmatica"),
                                    filling(),
                                    List.of(new PipelineStore.Row(2804, excluded.mergedAt(), "{}", some(excluded))));
        var html = PipelinePanel.render(WebFixtures.fresh().withPipeline(view), NOW);

        assertThat(html).contains("#2804</a> (INCOMPLETE, left out of the sums, TIMELINE_OVER_PAGE: <bdi>more than 100 queue events")
                  .contains("<td>2026-W45</td><td>1 / 1 (1 INCOMPLETE, 1 left out of the sums)</td>")
                  .contains("1 INCOMPLETE and left out of the sums");
    }

    /// A PR given up with runs that never completed reads INCOMPLETE with its kind and reason in its row, is counted as left
    /// out of the sums in its week, and its partial hours show in no week sum.
    @Test
    void panel_marksAGivenUpPr_incomplete_leftOutOfTheSums() {
        var givenUp = measure(2803, "f2b", Instant.parse("2026-11-07T17:17:03Z"), true, 2);
        var view = new PipelineView(PipelineScope.pipelineScope("pragmaticalabs/pragmatica"),
                                    filling(),
                                    List.of(new PipelineStore.Row(2803, givenUp.mergedAt(), "{}", some(givenUp))));
        var html = PipelinePanel.render(WebFixtures.fresh().withPipeline(view),
                                        NOW);

        assertThat(givenUp.totalSeconds()).isPositive();
        assertThat(html).contains("#2803</a> (INCOMPLETE, left out of the sums, RUNS_NEVER_COMPLETED: <bdi>2 run(s) never completed</bdi>)")
                  .contains("<td>2026-W45</td><td>1 / 1 (1 INCOMPLETE, 1 left out of the sums)</td>")
                  .contains("1 INCOMPLETE and left out of the sums")
                  .contains("base-sync h")
                  .contains("headline rule")
                  .doesNotContain("runs in progress");
    }

    @Test
    void panel_escapesGitHubText_andListsUnknownNames() {
        var html = PipelinePanel.render(dashboard(filling()), NOW);

        assertThat(html).contains("rc6 &lt;f5b&gt; &amp; sync")
                  .contains("CI / renamed-&lt;shard&gt;")
                  .doesNotContain("<f5b>")
                  .doesNotContain("renamed-<shard>");
    }

    @Test
    void failedState_showsReason_andMarksTheTabRed() {
        var failed = filling().then(PipelineStatus.Kind.FAILED,
                                    "GitHub answered HTTP 403 (does the kiln App have actions:read?)",
                                    ago(30));
        var page = Dashboard.render(dashboard(failed), NOW, some("prs"));

        assertThat(PipelinePanel.render(dashboard(failed),
                                        NOW)).contains("state FAILED")
                  .contains("HTTP 403")
                  .contains("STALE");
        assertThat(page).containsPattern("class=\"chip red\" href=\"/\\?tab=pipeline\"");
    }

    @Test
    void needsApp_isRed_pausedIsNot() {
        var needsApp = filling().then(PipelineStatus.Kind.NEEDS_APP, "kiln reads GitHub as TOKEN", ago(30));
        var paused = filling().then(PipelineStatus.Kind.PAUSED, "GitHub budget kept in reserve until x", ago(30));

        assertThat(PipelinePanel.red(needsApp)).isTrue();
        assertThat(PipelinePanel.red(paused)).isFalse();
    }

    @Test
    void stateKeepsSince_whileTheKindStays() {
        var once = filling().then(PipelineStatus.Kind.FAILED, "a", ago(300));
        var again = once.then(PipelineStatus.Kind.FAILED, "b", ago(10));

        assertThat(again.since()).isEqualTo(ago(300));
        assertThat(again.lastOk()).isEqualTo(some(ago(60)));
    }
}
