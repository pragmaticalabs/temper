package org.pragmatica.kiln.core.feed;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.CheckRun;
import org.pragmatica.kiln.core.github.CommitStatus;
import org.pragmatica.kiln.core.github.Counted;
import org.pragmatica.kiln.core.github.PullRead;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.feed.FeedFixtures.HEAD;
import static org.pragmatica.kiln.core.feed.FeedFixtures.PR;
import static org.pragmatica.kiln.core.feed.FeedFixtures.T0;
import static org.pragmatica.kiln.core.feed.FeedFixtures.checkRun;
import static org.pragmatica.kiln.core.feed.FeedFixtures.listing;
import static org.pragmatica.kiln.core.feed.FeedFixtures.numbered;
import static org.pragmatica.kiln.core.feed.FeedFixtures.pinned;
import static org.pragmatica.kiln.core.feed.FeedFixtures.requiring;
import static org.pragmatica.kiln.core.feed.FeedFixtures.run;
import static org.pragmatica.kiln.core.feed.FeedFixtures.status;
import static org.pragmatica.kiln.core.feed.FeedFixtures.unpinned;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The rows marked B, at the judge (the HTTP-level rows are `PrFreshIT`).
class FreshGateTest {
    private static final Sha OTHER = Sha.sha("b".repeat(40)).unwrap();
    private static final BranchName MAIN = BranchName.branchName("main").unwrap();
    private static final String SKIP_LINE = " (GitHub counts this as passing; merge-gate does not)";

    private static PullRead pull(String state, Option<Instant> mergedAt, Sha head) {
        return new PullRead(state, mergedAt, head, "feat", some("o/r"), MAIN, 3, none());
    }

    private static List<String> lines(RequiredChecks.Required list, List<CheckRun> runs, List<CommitStatus> statuses, List<WorkflowRun> headRuns) {
        return FreshGate.checks(FreshGate.outcomes(list, PR, runs, statuses, headRuns))
                        .stream()
                        .map(reason -> reason.check() + " " + reason.line())
                        .toList();
    }

    @Nested
    class Pull {
        @Test
        void pull_headMoved_g2a() {
            assertThat(FreshGate.pull(HEAD, pull("open", none(), OTHER))).containsExactly(new FreshGate.Reason("G2", "head-moved pulls " + OTHER.value()));
        }

        @Test
        void pull_mergedOrClosed_notOpen_g2b() {
            assertThat(FreshGate.pull(HEAD, pull("merged", some(T0), HEAD))).containsExactly(new FreshGate.Reason("G2", "not-open merged"));
            assertThat(FreshGate.pull(HEAD, pull("closed", none(), HEAD))).containsExactly(new FreshGate.Reason("G2", "not-open closed"));
            assertThat(FreshGate.pull(HEAD, pull("open", none(), HEAD))).isEmpty();
        }
    }

    @Nested
    class Required {
        @Test
        void required_noneRequired_requiredNone_g3a() {
            assertThat(FreshGate.required(new RequiredChecks.NoneRequired("off", false))).containsExactly(new FreshGate.Reason("G3", "required-none"));
            assertThat(FreshGate.required(new RequiredChecks.Required(List.of(), "x"))).containsExactly(new FreshGate.Reason("G3", "required-none"));
        }

        @Test
        void required_unknown_requiredUnknown_g3b() {
            assertThat(FreshGate.required(new RequiredChecks.Unknown("rulesets 500"))).containsExactly(new FreshGate.Reason("G3", "required-unknown rulesets 500"));
            assertThat(FreshGate.required(requiring(pinned("ci")))).isEmpty();
        }

        @Test
        void again_etagOrContextsDiffer_requiredChanged_g10() {
            var list = requiring(pinned("ci"));

            assertThat(FreshGate.again(list, some("\"a\""), list, some("\"a\""))).isEmpty();
            assertThat(FreshGate.again(list, some("\"a\""), list, some("\"b\""))).containsExactly(new FreshGate.Reason("G10", "required-changed"));
            assertThat(FreshGate.again(list, none(), list, none())).hasSize(1);
            assertThat(FreshGate.again(list, some("\"a\""), requiring(pinned("ci"), unpinned("lint")), some("\"a\""))).hasSize(1);
        }
    }

    @Nested
    class Checks {
        private final RequiredChecks.Required ci = requiring(pinned("ci"));

        @Test
        void checks_oneRedRequired_checkRed_g4a() {
            assertThat(lines(ci, List.of(checkRun(1, "ci", "completed", "failure", T0, 10)), List.of(), List.of(numbered(100, 1, 1, 1, "failure", 10))))
                .containsExactly("G4 check-red ci 10");
        }

        @Test
        void checks_queuedRerunOverOlderSuccess_checkPending_g4c() {
            assertThat(lines(ci,
                             List.of(checkRun(1, "ci", "completed", "success", T0, 10), new CheckRun(2, "ci", checkRun(1, "ci", "x", null, T0, 10).app(), "queued", none(), none(), some(10L))),
                             List.of(),
                             List.of(numbered(100, 1, 1, 1, "success", 10)))).containsExactly("G4 check-pending ci");
        }

        @Test
        void checks_olderFailureUnderNewerRerunSuccess_noReason_g4d() {
            assertThat(lines(ci,
                             List.of(checkRun(1, "ci", "completed", "failure", T0, 10), checkRun(2, "ci", "completed", "success", T0.plusSeconds(60), 11)),
                             List.of(),
                             List.of(numbered(100, 1, 5, 1, "failure", 10), numbered(100, 1, 5, 2, "success", 11)))).isEmpty();
        }

        @Test
        void checks_pushSuiteSuccessPrSuiteFailure_checkRed_g4e() {
            assertThat(lines(ci,
                             List.of(checkRun(1, "ci", "completed", "success", T0.plusSeconds(60), 10), checkRun(2, "ci", "completed", "failure", T0, 11)),
                             List.of(),
                             List.of(run(100, "push", "completed", "success", 1, 10, T0, T0), numbered(101, 1, 1, 1, "failure", 11))))
                .containsExactly("G4 check-red ci 11");
        }

        @Test
        void checks_pinnedWithForeignFailingStatus_noReason_g4f() {
            assertThat(lines(ci, List.of(checkRun(1, "ci", "completed", "success", T0, 10)), List.of(status(5, "ci", "failure", null)), List.of(numbered(100, 1, 1, 1, "success", 10))))
                .isEmpty();
        }

        @Test
        void checks_unpinnedWithFailingStatus_checkRedStatus_g4g() {
            assertThat(lines(requiring(unpinned("ci")),
                             List.of(checkRun(1, "ci", "completed", "success", T0, 10)),
                             List.of(status(5, "ci", "failure", null)),
                             List.of(numbered(100, 1, 1, 1, "success", 10)))).containsExactly("G4 check-red ci status");
        }

        @Test
        void counts_totalDiffersFromDistinctIds_inconsistent_g4h() {
            var runs = List.of(new Counted<>(3, List.of(checkRun(1, "ci", "completed", "success", T0, 10))),
                               new Counted<>(3, List.of(checkRun(1, "ci", "completed", "success", T0, 10), checkRun(2, "ci", "completed", "success", T0, 10))));
            var counts = FreshGate.counts(runs, List.of(new Counted<>(2, List.of(numbered(100, 1, 1, 1, "success", 10)))));

            assertThat(counts.consistent()).isFalse();
            assertThat(FreshGate.inconsistent(counts)).extracting(FreshGate.Reason::line)
                                                      .containsExactly("check-read-inconsistent check-runs 2/3", "check-read-inconsistent workflow-runs 1/2");
            assertThat(FreshGate.counts(List.of(), List.of()).consistent()).isTrue();
        }

        @Test
        void checks_r23WaiverRerunPassesInNewSuite_noReason_g4i() {
            assertThat(lines(ci,
                             List.of(checkRun(1, "ci", "completed", "failure", T0, 10), checkRun(2, "ci", "completed", "success", T0.plusSeconds(60), 11)),
                             List.of(),
                             List.of(numbered(100, 1, 1, 1, "failure", 10), numbered(101, 1, 2, 1, "success", 11)))).isEmpty();
        }

        @Test
        void checks_r23CancelledByCancelInProgressThenSuccess_noReason_g4j() {
            assertThat(lines(ci,
                             List.of(checkRun(1, "ci", "completed", "cancelled", T0, 10), checkRun(2, "ci", "completed", "success", T0.plusSeconds(60), 11)),
                             List.of(),
                             List.of(numbered(100, 1, 1, 1, "cancelled", 10), numbered(101, 1, 2, 1, "success", 11)))).isEmpty();
        }

        @Test
        void checks_r23CollidingJobNameOfAnotherWorkflowFails_checkRed_g4k() {
            assertThat(lines(ci,
                             List.of(checkRun(1, "ci", "completed", "success", T0.plusSeconds(60), 10), checkRun(2, "ci", "completed", "failure", T0, 11)),
                             List.of(),
                             List.of(numbered(100, 1, 9, 1, "success", 10), numbered(101, 2, 1, 1, "failure", 11)))).containsExactly("G4 check-red ci 11");
        }

        @Test
        void checks_forkPrRunListsNoPr_checkMissing_g4l() {
            assertThat(lines(ci, List.of(checkRun(1, "ci", "completed", "success", T0, 10)), List.of(), List.of(listing(100, "success", 10, List.of()))))
                .containsExactly("G4 check-missing ci");
        }

        @Test
        void checks_statusSuccessDescribedSkipped_checkSkipped_g5a() {
            assertThat(lines(requiring(unpinned("ci")), List.of(), List.of(status(5, "ci", "success", "Skipped: docs only")), List.of()))
                .containsExactly("G5 check-skipped ci" + SKIP_LINE);
        }

        @Test
        void checks_conclusionSkippedOrNeutral_checkSkipped_g5b() {
            assertThat(lines(ci, List.of(checkRun(1, "ci", "completed", "skipped", T0, 10)), List.of(), List.of(numbered(100, 1, 1, 1, "success", 10))))
                .containsExactly("G5 check-skipped ci" + SKIP_LINE);
            assertThat(lines(ci, List.of(checkRun(1, "ci", "completed", "neutral", T0, 10)), List.of(), List.of(numbered(100, 1, 1, 1, "success", 10))))
                .containsExactly("G5 check-skipped ci" + SKIP_LINE);
        }

        @Test
        void checks_allGreen_noReason_andOutcomeNamesItsObservation() {
            var outcomes = FreshGate.outcomes(requiring(unpinned("ci")),
                                              PR,
                                              List.of(checkRun(1, "ci", "completed", "success", T0, 10)),
                                              List.of(status(5, "ci", "success", null)),
                                              List.of(numbered(100, 1, 1, 1, "success", 10)));

            assertThat(FreshGate.checks(outcomes)).isEmpty();
            assertThat(outcomes).extracting(FreshGate.Outcome::outcome).containsExactly("green", "green");
            assertThat(outcomes).extracting(FreshGate::observedIn).containsExactlyInAnyOrder("10", "status");
        }

        @Test
        void checks_redAndSkippedInOneContext_redWinsAndNoG5() {
            assertThat(lines(requiring(unpinned("ci")),
                             List.of(checkRun(1, "ci", "completed", "skipped", T0, 10)),
                             List.of(status(5, "ci", "error", null)),
                             List.of(numbered(100, 1, 1, 1, "success", 10)))).containsExactly("G4 check-red ci status");
        }

        @Test
        void checks_pendingStatus_checkPending() {
            assertThat(lines(requiring(unpinned("ci")), List.of(), List.of(status(5, "ci", "pending", null)), List.of())).containsExactly("G4 check-pending ci");
        }

        @Test
        void checks_g4LinesPrecedeG5Lines() {
            var list = new RequiredChecks.Required(List.of(unpinned("a"), unpinned("b")), "x");

            assertThat(lines(list, List.of(), List.of(status(5, "a", "success", "skip"), status(6, "b", "failure", null)), List.of()))
                .containsExactly("G4 check-red b status", "G5 check-skipped a" + SKIP_LINE);
        }

        // verifier t2wpbv
        @Test
        void vx1_sameSuiteTwoSameNamedRuns_greatestIdIsTheObservation_contractG4b() {
            assertThat(lines(ci,
                             List.of(checkRun(1, "ci", "completed", "success", T0.plusSeconds(60), 10), checkRun(2, "ci", "completed", "failure", T0, 10)),
                             List.of(),
                             List.of(numbered(100, 1, 1, 1, "failure", 10)))).containsExactly("G4 check-red ci 10");
        }

        @Test
        void vx2_newerPushRunListingThePr_sameWorkflow_neverStandsInForTheRedPrRun_g4e() {
            var push = new WorkflowRun(100, "CI", "completed", some("success"), "x", "https://x/100", T0, some("push"), some("feat"), 1, some(10L), some(T0),
                                       some(1L), some(6L), List.of(PR.value()));

            assertThat(lines(ci,
                             List.of(checkRun(1, "ci", "completed", "success", T0.plusSeconds(60), 10), checkRun(2, "ci", "completed", "failure", T0, 11)),
                             List.of(),
                             List.of(push, numbered(101, 1, 5, 1, "failure", 11)))).containsExactly("G4 check-red ci 11");
        }

        @Test
        void vx10_unpinnedPendingRunAndFailingStatus_redWinsOverPending_g4e() {
            assertThat(lines(requiring(unpinned("ci")),
                             List.of(new CheckRun(1, "ci", checkRun(1, "ci", "x", null, T0, 10).app(), "in_progress", none(), some(T0), some(10L))),
                             List.of(status(5, "ci", "failure", null)),
                             List.of(numbered(100, 1, 1, 1, "success", 10)))).containsExactly("G4 check-red ci status");
        }

        // verifier t2wpbv2: lead ruling 2026-11-04 (greatest id); queued rerun (no started_at) over an older success
        @Test
        void vx11_queuedRerunNoStartedAt_overOlderCompletedSuccess_checkPending_ruling1() {
            assertThat(lines(ci,
                             List.of(new CheckRun(7, "ci", checkRun(1, "ci", "x", null, T0, 10).app(), "queued", none(), none(), some(10L)),
                                     checkRun(3, "ci", "completed", "success", T0.plusSeconds(60), 10)),
                             List.of(),
                             List.of(numbered(100, 1, 1, 1, "success", 10)))).containsExactly("G4 check-pending ci");
        }

        // verifier t2wpbv2: greatest id decides even when a lower-id run never started
        @Test
        void vx12_lowerIdRunNeverStarted_greaterIdCompletedSuccess_noReason_ruling1() {
            assertThat(lines(ci,
                             List.of(new CheckRun(1, "ci", checkRun(1, "ci", "x", null, T0, 10).app(), "queued", none(), none(), some(10L)),
                                     checkRun(2, "ci", "completed", "success", T0.plusSeconds(60), 10)),
                             List.of(),
                             List.of(numbered(100, 1, 1, 1, "success", 10)))).isEmpty();
        }

        @Test
        void contexts_onlyARequiredListHasAny() {
            assertThat(FreshGate.contexts(new RequiredChecks.Unknown("x"))).isEmpty();
            assertThat(FreshGate.contexts(ci)).containsExactly(pinned("ci"));
        }
    }
}
