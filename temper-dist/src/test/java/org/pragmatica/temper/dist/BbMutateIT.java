package org.pragmatica.temper.dist;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// `ops/bb/bb-mutate --batch` run for real against a throwaway git checkout, with a fake `$HOME/tools/bb-build` that fabricates
/// surefire XML per module (`bb/fake-bb-build.py`). Module B depends on A, so a red A leaves B's tests unrun, the defect that
/// made a batch read SURVIVED for a mutant whose tests never ran. The installed tool before the fix (`bb/bb-mutate.prev`) is run
/// on the same scenario as the positive control.
///
/// Probes (each mutation of ops/bb/bb-mutate turns the named test red):
///  - drop the freshness filter (`st_mtime_ns > marker_ns` accepts any XML) -> [#aStaleReport_isNotRun_neverSurvived]
///  - drop `testcases > 0` in `ran` -> [#anEmptyReport_isNotRun_neverSurvived]
///  - exit 0 although a mutant is NOT-RUN -> [#everyMutantNotRun_exitsFive], [#anEmptyReport_isNotRun_neverSurvived]
///  - drop the single re-run of a NOT-RUN batch-mate -> [#aZeroFailureBatch_withAnEmptyReport_isRerunSingly]
///  - #97: `fails` back to `len(fails) > 1 and ...` in the singles condition -> [#aBatchWithAFailure_isRerunSinglyForEveryMember],
///    [#aFailureUnderRcZero_isStillNotCreditedAsABatch], [#aBatchMateBreakingAnotherMutantsTest_isNeverCreditedRed], [#aDownstreamMutant_isCreditedOnlyBySoloRuns]
///  - #97: `compatible()` without the same-file refusal -> [#mutantsOfOneFile_areNeverBatched]
///  - #97: a constant `credited` -> [#aZeroFailureBatch_isCreditedAsABatch], [#aLoneMutant_isRunWithoutFae]
///  - drop the expected-class test in `ran` -> [#aReportOfAnotherClass_isNotRun_neverSurvived]
///  - #97 review: a CALL-REFUSED credited `credit` (group site) or `credited` (finish site) -> [#aRefusedBatch_isCreditedDash],
///    [#aRefusedLoneMutant_isCreditedDash], [#aRefusedSingleAfterAFailingBatch_isCreditedDash]
///  - #97 review: `compatible()` comparing raw file strings -> [#aSecondSpellingOfAFile_isNeverBatched]
///  - #97 review: an APPLY-INVALID batch recorded instead of re-run singly -> [#anInvalidBatch_isRerunSingly_soTheValidMemberIsNotStranded]
///  - drop `rc != 0` from the singles condition -> [#aBatchWithANonZeroRcAndNoFailure_isRerunSingly]
///  - drop `compile_err` from the singles condition -> [#aBatchWithACompilerErrorInItsLog_isRerunSingly]
///  - `rec` defaulting `credited` to `solo` -> [#anInvalidMutant_isCreditedDash], [#aRunAbortedByTheQueueWait_leavesTheRestNotRunCreditedDash]
///  - a TIMEOUT credited `batch` (lone, or singly after a batch) -> [#aLoneTimeout_isCreditedSolo], [#aTimeoutInABatch_isRerunSinglyAndEachCreditedSolo]
///  - #100: a solo build failing only unlisted tests recorded SURVIVED (`elif fails` -> SURVIVED) -> [#aSoloMutantBreakingOnlyAnUnlistedTest_isKilledElsewhere_namingTheTest],
///    [#aBatchMemberBreakingOnlyAnUnlistedTest_isKilledElsewhere_creditedSolo], [#aBatchMateBreakingAnotherMutantsTest_isNeverCreditedRed]
///  - #100: the `elif fails` branch taken without a failure (`else`) -> [#aZeroFailureSoloBuild_stillReadsSurvived]
///  - #100: the detail without the failing tests' names -> [#aSoloMutantBreakingOnlyAnUnlistedTest_isKilledElsewhere_namingTheTest]
///  - #100: the verdict without the flake note -> [#theKilledElsewhereTally_andTheFlakeNote_showInTheSummaryAndTheResults]
///  - #100: the detail truncated to fewer than five failing tests (`fails[:5]` -> `fails[:1]`) -> [#aSoloMutantBreakingSixUnlistedTests_namesTheFirstFiveInTheDetail_andAllSixInAllFailures]
///  - #100: the detail's tail (expect-list hint, flake note) dropped -> [#aSoloMutantBreakingOnlyAnUnlistedTest_isKilledElsewhere_namingTheTest]
///  - #100: the summary line without the KILLED-ELSEWHERE count or note -> [#theKilledElsewhereTally_andTheFlakeNote_showInTheSummaryAndTheResults]
///  - results.tsv header columns reordered -> [#resultsTsv_headerNamesTheColumnsInRowOrder]
///  - #112: bb-build's `-pl` without `-am` check dropped (the real bb-build, not the fake, runs in its row) -> [#aBuildWithoutAm_withASiblingNotInstalled_stopsAtTheControl_withBbBuildsRefusal]
class BbMutateIT {
    private static final Path TOOL = BbFixtures.OPS_BB.resolve("bb-mutate");

    @TempDir
    Path dir;

    private Path home;
    private Path co;

    @BeforeEach
    void checkout() throws Exception {
        assumeTrue(has("python3") && has("git"), "bb-mutate is python3 and drives git");
        dir = dir.toRealPath();
        home = Files.createDirectories(dir.resolve("home"));
        co = dir.resolve("work/co/t-x");
        var fake = BbFixtures.executable(home.resolve("tools/bb-build"), resource("fake-bb-build.py"));

        assertThat(fake).exists();
        sh("""
           set -e
           mkdir -p "%1$s" && cd "%1$s"
           git init -q . && mkdir .mvn
           echo "-Dmaven.repo.local=$PWD/.m2-local" > .mvn/maven.config
           printf '.bb/\\ntarget/\\n.m2-local/\\n' > .gitignore
           for m in A B C; do mkdir -p $m/src; printf 'ok\\nko\\n' > $m/src/f.txt; done
           git add -A && git -c user.name=t -c user.email=t@t commit -qm init
           git init -q --bare "%2$s" && git remote add origin "%2$s" && git push -q origin HEAD:refs/heads/main && git fetch -q origin
           git checkout -q --detach
           """.formatted(co, dir.resolve("remote.git")));
    }

    /// B depends on A. The batch turns A red, so B never runs in it. The batch has a failure, so both members are re-run singly and
    /// credited solo. The old tool read the same batch as B SURVIVED (see [#theOldTool_readsADownstreamMutantAsSurvived]).
    @Test
    void aDownstreamMutant_isCreditedOnlyBySoloRuns() throws Exception {
        var out = batch(modules(null), mutation("mA", "A", "ATest", "MUT"), mutation("mB", "B", "BTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "RED").containsEntry("mB", "RED");
        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mB", "solo");
        assertThat(out.rc()).isZero();
        assertThat(builds()).containsExactly("[] false",

        // control
        "[A,B] true",

        // the batch, with -fae
        "[A] false",

        // mA singly
        "[B] false");  // mB singly
        assertThat(out.log()).contains("batch mA+mB ambiguous -> re-running singly");
    }

    @Test
    void theOldTool_readsADownstreamMutantAsSurvived() throws Exception {
        var out = batchWith(resource("bb-mutate.prev"),
                            modules(null),
                            mutation("mA", "A", "ATest", "MUT"),
                            mutation("mB", "B", "BTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "RED").containsEntry("mB", "SURVIVED");
    }

    /// A report of the right module that holds no testcases is no evidence: NOT-RUN in the batch, still NOT-RUN singly, tallied apart.
    @Test
    void anEmptyReport_isNotRun_neverSurvived() throws Exception {
        var out = batch(modules("zero"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "NOT-RUN").containsEntry("mC", "RED");
        assertThat(out.rc()).as("mixed: one mutant still NOT-RUN").isEqualTo(5);
        assertThat(builds()).as("control, the batch (it has a failure), then one solo run each").hasSize(4);
        assertThat(out.stdout()).contains("NOT-RUN=1").contains("RED=1").doesNotContain("SURVIVED");
        assertThat(out.md()).contains("neither detected nor survived");
    }

    @Test
    void aReportOfAnotherClass_isNotRun_neverSurvived() throws Exception {
        var out = batch(modules("otherclass"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "NOT-RUN").containsEntry("mC", "RED");
        assertThat(out.rc()).isEqualTo(5);
    }

    /// Every mutant NOT-RUN (nothing detected, nothing survived): a caller that checks only rc must not read it as a pass.
    @Test
    void everyMutantNotRun_exitsFive() throws Exception {
        var out = batch(modules("zero"), mutation("mA", "A", "ATest", "MUT"));

        assertThat(out.outcomes()).containsOnlyKeys("mA").containsEntry("mA", "NOT-RUN");
        assertThat(out.rc()).isEqualTo(5);
        assertThat(out.stdout()).contains("NOT-RUN=1");
    }

    /// A module whose report was written before the build (the control's) is stale: not evidence for the mutant's build.
    @Test
    void aStaleReport_isNotRun_neverSurvived() throws Exception {
        var out = batch(modules("stale"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "NOT-RUN").containsEntry("mC", "RED");
        assertThat(out.rc()).isEqualTo(5);
    }

    /// Independent modules that all run and no test fails anywhere: one batch, every member credited from it (`batch`), no re-run.
    @Test
    void aZeroFailureBatch_isCreditedAsABatch() throws Exception {
        var out = batch(modules(null), mutation("mA", "A", "ATest", "fine"), mutation("mC", "C", "CTest", "fine"));

        assertThat(out.outcomes()).containsEntry("mA", "SURVIVED").containsEntry("mC", "SURVIVED");
        assertThat(out.credited()).containsEntry("mA", "batch").containsEntry("mC", "batch");
        assertThat(out.rc()).isZero();
        assertThat(builds()).containsExactly("[] false", "[] true");
        assertThat(out.md()).contains("| id | outcome | credited | expected failed |");
    }

    /// t3031g shape: one failure in the batch hits mA; mC reads "SURVIVED, failures none of which were expected" from that same build.
    /// A batch with any failure is not credited: every member is re-run singly, so mC's verdict comes from a build without mA's kill.
    @Test
    void aBatchWithAFailure_isRerunSinglyForEveryMember() throws Exception {
        var out = batch(modules(null), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "fine"));

        assertThat(out.outcomes()).containsEntry("mA", "RED").containsEntry("mC", "SURVIVED");
        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mC", "solo");
        assertThat(out.rc()).isZero();
        assertThat(builds()).containsExactly("[] false", "[A] true", "[A] false", "[] false");
        assertThat(out.md()).doesNotContain("failures none of which were expected");
    }

    /// A failing testcase under rc 0 (testFailureIgnore) is a failure all the same: the batch is not credited, every member runs solo.
    @Test
    void aFailureUnderRcZero_isStillNotCreditedAsABatch() throws Exception {
        var out = batch(modules(null).replace("{\"modules\"", "{\"ignoreFailures\":true,\"modules\""),
                        mutation("mA", "A", "ATest", "MUT"),
                        mutation("mC", "C", "CTest", "fine"));

        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mC", "solo");
        assertThat(builds()).containsExactly("[] false", "[A] true", "[A] false", "[] false");
    }

    /// #97 false RED: mC's change (file C) breaks ATest#t1, a test of mA's expected class; mA's own change is inert. The batch has exactly
    /// one failure and it matches mA's expectation, so the old tool credited mA RED from it. Solo, mA survives; mC's expected CTest passes
    /// and only the unexpected ATest#t1 fails, so mC is KILLED-ELSEWHERE (#100), not SURVIVED (a batch-mate's kill is never another mutant's RED).
    @Test
    void aBatchMateBreakingAnotherMutantsTest_isNeverCreditedRed() throws Exception {
        var out = batch("""
                        {"modules":[{"name":"C","class":"CTest","cases":["t1"],"fault":"inert"},
                                     {"name":"A","class":"ATest","cases":["t1","t2"],"breaks":["C"]}]}""",
                        mutation("mA", "A", "ATest", "fine"),
                        mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "SURVIVED").containsEntry("mC", "KILLED-ELSEWHERE");
        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mC", "solo");
        assertThat(builds()).containsExactly("[] false", "[C] true", "[] false", "[C] false");
        assertThat(out.md()).contains("failures none of which were expected: ATest#t1");
    }

    /// Scenario: C's change breaks ATest#t1 (outside the mutant's expect list CTest); C's own tests pass.
    private static final String C_BREAKS_ATEST = """
        {"modules":[{"name":"C","class":"CTest","cases":["t1"],"fault":"inert"},
                     {"name":"A","class":"ATest","cases":["t1","t2"],"breaks":["C"]}]}""";

    /// C's mutation breaks the first six testcases of A's one suite; the case names are out of alphabetical order, so the report order shows
    private static final String SIX_BROKEN = """
        {"modules":[{"name":"C","class":"CTest","cases":["t1"],"fault":"inert"},
                     {"name":"A","class":"ATest","cases":["t3","t1","t6","t2","t5","t4"],"breaks":["C"],"failN":6}]}""";

    /// #100 (row a): a lone mutant whose build fails only an unlisted test is detected, so not SURVIVED: KILLED-ELSEWHERE, the detail naming
    /// the test. The old tool read it SURVIVED, hiding a detection behind a wrong expect list.
    @Test
    void aSoloMutantBreakingOnlyAnUnlistedTest_isKilledElsewhere_namingTheTest() throws Exception {
        var out = batch(C_BREAKS_ATEST, mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsOnlyKeys("mC").containsEntry("mC", "KILLED-ELSEWHERE");
        assertThat(out.credited()).containsEntry("mC", "solo");
        assertThat(out.rc()).as("the exit code is unchanged: not NOT-RUN").isZero();
        assertThat(out.detail("mC")).contains("ATest#t1");
        assertThat(out.detail("mC")).as("the detail's tail: the expect-list hint and the flake note")
                  .contains("expect list wrong or incomplete")
                  .contains("one green control does not rule out a flake of that test");
        assertThat(builds()).containsExactly("[] false", "[C] false");
    }

    /// #100 (row e): the detail names the first five failing testcases, in report order, and no more; the all_failures column keeps all six.
    @Test
    void aSoloMutantBreakingSixUnlistedTests_namesTheFirstFiveInTheDetail_andAllSixInAllFailures() throws Exception {
        var out = batch(SIX_BROKEN, mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mC", "KILLED-ELSEWHERE");
        assertThat(out.detail("mC")).contains("ATest#t3,ATest#t1,ATest#t6,ATest#t2,ATest#t5;")
                  .doesNotContain("ATest#t4");
        assertThat(out.allFailures("mC")).isEqualTo("ATest#t3,ATest#t1,ATest#t6,ATest#t2,ATest#t5,ATest#t4");
    }

    /// #100 (row b): zero failing testcases is the only SURVIVED, and its detail stays empty.
    @Test
    void aZeroFailureSoloBuild_stillReadsSurvived() throws Exception {
        var out = batch(C_BREAKS_ATEST, mutation("mC", "C", "CTest", "fine"));

        assertThat(out.outcomes()).containsOnlyKeys("mC").containsEntry("mC", "SURVIVED");
        assertThat(out.credited()).containsEntry("mC", "solo");
        assertThat(out.detail("mC")).isEmpty();
        assertThat(out.stdout()).contains("SURVIVED=1").doesNotContain("KILLED-ELSEWHERE");
        assertThat(out.md()).doesNotContain("KILLED-ELSEWHERE");
    }

    /// #100 (row c): the summary line counts KILLED-ELSEWHERE apart from RED and SURVIVED and says one green control does not rule out a
    /// flake; results.md carries the outcome in the row and the same note in the verdict; the TSV row carries it too.
    @Test
    void theKilledElsewhereTally_andTheFlakeNote_showInTheSummaryAndTheResults() throws Exception {
        var out = batch(C_BREAKS_ATEST, mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.stdout()).contains("KILLED-ELSEWHERE=1").doesNotContain("RED=").doesNotContain("SURVIVED=");
        assertThat(out.stdout()).contains("one green control does not rule out a flake");
        assertThat(out.md()).contains("| mC | KILLED-ELSEWHERE | solo |");
        assertThat(out.md()).contains("KILLED-ELSEWHERE = a solo build failed only tests outside the mutant's expect list")
                  .contains("one green control does not rule out a flake of that test, so re-run once");
        assertThat(out.tsvRow("mC")).startsWith("mC\tKILLED-ELSEWHERE\tsolo\t");
    }

    /// #100 (row d): a batch with a failure is re-run singly; its member that breaks only an unlisted test reads KILLED-ELSEWHERE, credited
    /// solo, while its inert batch-mate (which would have been blamed for nothing) is a plain SURVIVED.
    @Test
    void aBatchMemberBreakingOnlyAnUnlistedTest_isKilledElsewhere_creditedSolo() throws Exception {
        var out = batch(C_BREAKS_ATEST, mutation("mC", "C", "CTest", "MUT"), mutation("mA", "A", "ATest", "fine"));

        assertThat(out.outcomes()).containsEntry("mC", "KILLED-ELSEWHERE").containsEntry("mA", "SURVIVED");
        assertThat(out.credited()).containsEntry("mC", "solo").containsEntry("mA", "solo");
        assertThat(out.detail("mC")).contains("ATest#t1");
        assertThat(out.log()).contains("ambiguous -> re-running singly");
        assertThat(builds()).containsExactly("[] false", "[C] true", "[C] false", "[] false");
    }

    /// Two mutants of one file are never in one batch, even with different `old`, zones and expected classes.
    @Test
    void mutantsOfOneFile_areNeverBatched() throws Exception {
        var out = batch(modules(null),
                        mutation("m1", "A", "ATest", "fine"),
                        """
                            {"id":"m2","file":"A/src/f.txt","kind":"lit","old":"ko","new":"fine","expect":["BTest"]}""");

        assertThat(out.outcomes()).containsEntry("m1", "SURVIVED").containsEntry("m2", "SURVIVED");
        assertThat(out.credited()).containsEntry("m1", "solo").containsEntry("m2", "solo");
        assertThat(builds()).containsExactly("[] false", "[] false", "[] false");
    }

    /// A zero-failure batch whose member's module reported no testcases: that member is NOT-RUN in the batch and is re-run singly (still
    /// NOT-RUN); the other member keeps its batch credit.
    @Test
    void aZeroFailureBatch_withAnEmptyReport_isRerunSingly() throws Exception {
        var out = batch(modules("zero"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "fine"));

        assertThat(out.outcomes()).containsEntry("mA", "NOT-RUN").containsEntry("mC", "SURVIVED");
        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mC", "batch");
        assertThat(out.rc()).isEqualTo(5);
        assertThat(builds()).containsExactly("[] false", "[A] true", "[A] false");
        assertThat(out.log()).contains("NOT-RUN in batch (re-running singly): mA");
    }

    /// One module, one mutant: the build is a plain single run (no -fae) and credited.
    @Test
    void aLoneMutant_isRunWithoutFae() throws Exception {
        var out = batch(modules(null), mutation("mA", "A", "ATest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "RED");
        assertThat(out.credited()).containsEntry("mA", "solo");
        assertThat(out.rc()).isZero();
        assertThat(builds()).containsExactly("[] false", "[A] false");
    }

    /// Issue #46: every bb-build call carries BB_VIA=bb-mutate:<spec basename>:<label>, the label being `control` or the mutant id(s) of the run
    /// (a batch joins them with '+', which the ticket field cannot hold, so '.'); bb-queue shows the label as [mutant <label>].
    @Test
    void everyBuild_carriesBbVia_withTheSpecBasenameAndTheMutantLabel() throws Exception {
        batch(modules(null), mutation("mA", "A", "ATest", "fine"), mutation("mC", "C", "CTest", "fine"));
        assertThat(Files.readAllLines(home.resolve("via.log"))).containsExactly("bb-mutate:spec:control",
                                                                                "bb-mutate:spec:mA.mC");
    }

    /// A spec name or mutant id outside [A-Za-z0-9._-] is cut to it, and the whole value stays within the 64 bytes bb-build accepts.
    @Test
    void bbVia_isSanitizedAndBounded() throws Exception {
        specName = "my spec+v1.json";
        batch(modules(null), mutation("m+A", "A", "ATest", "fine"), mutation("x".repeat(80), "C", "CTest", "fine"));
        var vias = Files.readAllLines(home.resolve("via.log"));

        assertThat(vias).hasSize(2);
        assertThat(vias.get(1)).startsWith("bb-mutate:my.spec.v1:m.A.xxx");
        assertThat(vias).allMatch(v -> v.matches("^[A-Za-z0-9._:-]{1,64}$"));
        assertThat(vias.get(1)).hasSize(64);
    }

    /// No build ran for a refused call (bb-build exited 1 at once), so the verdict is credited `-`, not `batch`: a group of two.
    @Test
    void aRefusedBatch_isCreditedDash() throws Exception {
        var out = batch(withKey(modules(null), "refuseWhen", 2),
                        mutation("mA", "A", "ATest", "MUT"),
                        mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "CALL-REFUSED").containsEntry("mC", "CALL-REFUSED");
        assertThat(out.credited()).containsEntry("mA", "-").containsEntry("mC", "-");
        assertThat(builds()).containsExactly("[] false");
    }

    /// The same for a group of one (the `solo` of the group site).
    @Test
    void aRefusedLoneMutant_isCreditedDash() throws Exception {
        var out = batch(withKey(modules(null), "refuseWhen", 1), mutation("mA", "A", "ATest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "CALL-REFUSED");
        assertThat(out.credited()).containsEntry("mA", "-");
        assertThat(builds()).containsExactly("[] false");
    }

    /// The batch runs and fails, so each member is re-run singly, and both of those calls are refused: the `finish` site, credited `-` too.
    @Test
    void aRefusedSingleAfterAFailingBatch_isCreditedDash() throws Exception {
        var out = batch(withKey(modules(null), "refuseWhen", 1),
                        mutation("mA", "A", "ATest", "MUT"),
                        mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "CALL-REFUSED").containsEntry("mC", "CALL-REFUSED");
        assertThat(out.credited()).containsEntry("mA", "-").containsEntry("mC", "-");
        assertThat(builds()).containsExactly("[] false", "[A,C] true");
    }

    /// rc 1 with fresh reports, every test green and a clean log: nothing names the cause, so the batch is not credited: each member runs solo.
    @Test
    void aBatchWithANonZeroRcAndNoFailure_isRerunSingly() throws Exception {
        var out = batch(inertModules("rcWhen", "inert", "inert"),
                        mutation("mA", "A", "ATest", "MUT"),
                        mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "SURVIVED").containsEntry("mC", "SURVIVED");
        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mC", "solo");
        assertThat(builds()).containsExactly("[] false", "[A,C] true", "[A] false", "[C] false");
    }

    /// A compiler error in the log under rc 0 (failOnError=false) with no failing test: the batch is not credited; the member that holds the
    /// error is COMPILE-ERROR solo, its batch-mate is SURVIVED solo from a build without that log.
    @Test
    void aBatchWithACompilerErrorInItsLog_isRerunSingly() throws Exception {
        var out = batch(modules("compileerr"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "fine"));

        assertThat(out.outcomes()).containsEntry("mA", "COMPILE-ERROR").containsEntry("mC", "SURVIVED");
        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mC", "solo");
        assertThat(builds()).containsExactly("[] false", "[A] true", "[A] false", "[] false");
    }

    /// A mutant that never applied ran no build: credited `-`; its valid neighbour is a solo run.
    @Test
    void anInvalidMutant_isCreditedDash() throws Exception {
        var out = batch(modules(null),
                        mutation("mA", "A", "ATest", "fine"),
                        """
                            {"id":"mX","file":"C/src/f.txt","kind":"lit","old":"absent","new":"x","expect":["CTest"]}""");

        assertThat(out.outcomes()).containsEntry("mA", "SURVIVED").containsEntry("mX", "APPLY-INVALID");
        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mX", "-");
    }

    /// A build that bb-build's bound runner stopped (.bb/rc 124, #116) reads exactly as one this driver stopped at the bound: same outcome, credit and
    /// detail, in a lone run. Control: the two builds differ (one leaves its rc, the other none), so each path was taken. Mutation: drop the
    /// RC_BOUND branch in run_build -> the stopped build reads as a build without results, red.
    @Test
    void aBuildStoppedByTheBoundRunner_readsAsTheDriversOwnTimeout() throws Exception {
        killableBuilds();
        buildJson = "{\"mutantTimeoutSecs\":1}";
        var driver = batch(modules("hang"), mutation("mA", "A", "ATest", "MUT"));
        var runner = batch(modules("bounded"), mutation("mA", "A", "ATest", "MUT"));

        assertThat(driver.log()).contains("wrote .bb/end and .bb/rc=124 (killed by bb-mutate)");
        assertThat(runner.log()).startsWith(driver.log());  // one log, appended to
        assertThat(runner.log().substring(driver.log().length())).contains("[mA] bb-build exit=0").doesNotContain("KILL").doesNotContain("killed by bb-mutate");
        assertThat(runner.outcomes()).containsEntry("mA", "TIMEOUT").isEqualTo(driver.outcomes());
        assertThat(runner.credited()).containsEntry("mA", "solo").isEqualTo(driver.credited());
        assertThat(runner.detail("mA")).isEqualTo("exceeded 1s from its own Maven start; job killed (counted detected)").isEqualTo(driver.detail("mA"));
        assertThat(runner.rc()).isZero();
    }

    /// A job that exits 124 by itself (no bound hit: its ticket carries job_end=, not bound_hit=) is an ordinary build, as before #116: its reports
    /// are read and, with no failing testcase, it is BUILD-FAIL with its rc, never TIMEOUT (which counts as detected). Control: the build the bound
    /// runner stopped (ticket with bound_hit=) is TIMEOUT, in [#aBuildStoppedByTheBoundRunner_readsAsTheDriversOwnTimeout]. Mutation: drop
    /// `ticket_has(ticket, "bound_hit")` from the RC_BOUND branch -> TIMEOUT here, red.
    @Test
    void aJobsOwnExit124WithoutABoundHit_isBuildFail_neverTimeout() throws Exception {
        killableBuilds();
        buildJson = "{\"mutantTimeoutSecs\":60}";
        var out = batch(modules("exit124"), mutation("mA", "A", "ATest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "BUILD-FAIL");
        assertThat(out.detail("mA")).isEqualTo("rc=124 with no failing testcase");
        assertThat(out.log()).doesNotContain("job killed");
        assertThat(out.rc()).isZero();
    }

    /// --timeout above 16666 min is a bound bb-build refuses (six digits): bb-mutate refuses it at the start, exit 2, one line naming --timeout, no
    /// build queued (the fake bb-build never ran). So is a mutantTimeoutSecs over 999999. Control: 16666 min starts (a build is made).
    /// Mutation: drop the check -> the control build is refused by bb-build instead, with no word of --timeout, red.
    @Test
    void aTimeoutOverSixDigitsOfSeconds_isRefusedAtTheStart_namingTheOption() throws Exception {
        Files.writeString(home.resolve("scenario.json"), modules(null));
        for (var row : List.of(List.of("--timeout", "16667", "{}", "--timeout 16667 min is over 16666 min"),
                               List.of("--timeout", "1", "{\"mutantTimeoutSecs\":1000000}", "build.mutantTimeoutSecs 1000000 is over 999999 s"))) {
            Files.writeString(dir.resolve(specName),
                              "{\"build\":" + row.get(2) + ",\"mutations\":[" + mutation("mA", "A", "ATest", "MUT") + "]}");
            var run = BbFixtures.run(List.of("python3",
                                             TOOL.toString(),
                                             co.toString(),
                                             dir.resolve(specName).toString(),
                                             dir.resolve("out-refused").toString(),
                                             row.get(0),
                                             row.get(1)),
                                     Map.of("PATH", System.getenv("PATH"), "HOME", home.toString(), "BB_OWNER", "t"),
                                     null,
                                     null);

            assertThat(run.rc()).as(run.stderr()).isEqualTo(2);
            assertThat(run.stderr()).contains(row.get(3)).contains("nothing queued");
            assertThat(run.stderr().lines().filter(l -> !l.isBlank()).count()).isEqualTo(1);
            assertThat(home.resolve("builds.log")).doesNotExist();
        }

        extra = List.of("--timeout", "16666");
        buildJson = "{}";
        var out = batch(modules(null), mutation("mA", "A", "ATest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "RED");
        assertThat(builds()).isNotEmpty();
    }

    /// Every build carries its bound to bb-build (#116): a mutant's own, the control's --timeout; and the run's one submit time.
    @Test
    void everyBuild_carriesItsBound_andTheRunsSubmitTime() throws Exception {
        extra = List.of("--timeout", "2");
        buildJson = "{\"mutantTimeoutSecs\":77}";
        batch(modules(null), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "MUT"));
        var bounds = Files.readAllLines(home.resolve("bounds.log")).stream().map(line -> line.split(" ")).toList();

        assertThat(bounds.stream().map(line -> line[0])).startsWith("120", "77").hasSize(builds().size());
        assertThat(bounds.stream().skip(1).map(line -> line[0])).containsOnly("77");
        assertThat(bounds.stream().map(line -> line[1]).distinct()).singleElement().asString().matches("[0-9]{19}");
    }

    /// A bound is whole seconds (bb-build takes digits): a fractional mutantTimeoutSecs is rounded UP, never down to 0 (which bb-build refuses).
    /// Mutation: `int(...)` for `math.ceil(...)` in the bound sent to bb-build -> 0 here, red.
    @Test
    void aFractionalBound_isRoundedUpToAWholeSecond() throws Exception {
        extra = List.of("--timeout", "2");
        buildJson = "{\"mutantTimeoutSecs\":0.4}";
        batch(modules(null), mutation("mA", "A", "ATest", "MUT"));
        var bounds = Files.readAllLines(home.resolve("bounds.log")).stream().map(line -> line.split(" ")[0]).toList();

        assertThat(bounds).startsWith("120", "1").containsOnly("120", "1");
    }

    /// The control's bound is its --timeout, at least 1 s: --timeout 0 sends 1, not 0. Mutation: `max(0, ...)` for `max(1, ...)` -> 0, red.
    @Test
    void aZeroTimeout_sendsABoundOfOneSecondNotZero() throws Exception {
        killableBuilds();
        extra = List.of("--timeout", "0");
        okRcs = List.of(4);
        batch(modules("nostart"), mutation("mA", "A", "ATest", "MUT"));

        assertThat(Files.readAllLines(home.resolve("bounds.log")).stream().map(line -> line.split(" ")[0]).toList()).first().isEqualTo("1");
    }

    /// The queue wait expires for a batch that never starts: the run is aborted (exit 4) and every member is NOT-RUN with no build credited.
    @Test
    void aRunAbortedByTheQueueWait_leavesTheRestNotRunCreditedDash() throws Exception {
        killableBuilds();
        extra = List.of("--timeout", "0.03");
        okRcs = List.of(4);
        var out = batch(modules("nostart"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.rc()).isEqualTo(4);
        assertThat(out.outcomes()).containsEntry("mA", "NOT-RUN").containsEntry("mC", "NOT-RUN");
        assertThat(out.credited()).containsEntry("mA", "-").containsEntry("mC", "-");
    }

    /// A lone mutant whose build outlives its bound is TIMEOUT, from a build of one: `solo`.
    @Test
    void aLoneTimeout_isCreditedSolo() throws Exception {
        killableBuilds();
        buildJson = "{\"mutantTimeoutSecs\":1}";
        var out = batch(modules("hang"), mutation("mA", "A", "ATest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "TIMEOUT");
        assertThat(out.credited()).containsEntry("mA", "solo");
        assertThat(out.rc()).isZero();
    }

    /// A batch that outlives its bound is re-run singly: the member that hangs again is TIMEOUT `solo`, the other one is judged solo.
    @Test
    void aTimeoutInABatch_isRerunSinglyAndEachCreditedSolo() throws Exception {
        killableBuilds();
        buildJson = "{\"mutantTimeoutSecs\":1}";
        var out = batch(modules("hang"), mutation("mA", "A", "ATest", "MUT"), mutation("mC", "C", "CTest", "MUT"));

        assertThat(out.outcomes()).containsEntry("mA", "TIMEOUT").containsEntry("mC", "RED");
        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mC", "solo");
        assertThat(builds()).containsExactly("[] false", "[A,C] true", "[A] false", "[C] false");
    }

    /// The TSV carries its columns in the order its rows do (readers take them by position); the md header is pinned apart.
    @Test
    void resultsTsv_headerNamesTheColumnsInRowOrder() throws Exception {
        var out = batch(modules(null), mutation("mA", "A", "ATest", "fine"));

        assertThat(out.tsvHeader()).isEqualTo("id\toutcome\tcredited\texpected_failed\tall_failures\ttestcases\trc\tsecs\tdetail");
    }

    /// `A/src/f.txt` and `./A/src/f.txt` are one file: never batched (a batch would see git name one path where two were applied and
    /// record both APPLY-INVALID); each runs solo.
    @Test
    void aSecondSpellingOfAFile_isNeverBatched() throws Exception {
        var out = batch(modules(null),
                        """
                            {"id":"m1","file":"A/src/f.txt","kind":"lit","old":"ok","new":"fine","expect":["ATest"]}""",
                        """
                            {"id":"m2","file":"./A/src/f.txt","kind":"lit","old":"ko","new":"fine","expect":["BTest"]}""");

        assertThat(out.outcomes()).containsEntry("m1", "SURVIVED").containsEntry("m2", "SURVIVED");
        assertThat(out.credited()).containsEntry("m1", "solo").containsEntry("m2", "solo");
        assertThat(builds()).containsExactly("[] false", "[] false", "[] false");
    }

    /// C/src/f.txt is a tracked symlink to A/src/f.txt: the pair batches (two names), applies, and git names one file, so the batch is invalid.
    /// It is not recorded as such: each member runs singly, the real file's mutant is judged, the aliased one is APPLY-INVALID alone.
    @Test
    void anInvalidBatch_isRerunSingly_soTheValidMemberIsNotStranded() throws Exception {
        sh("""
           set -e
           cd "%s" && rm C/src/f.txt && ln -s ../../A/src/f.txt C/src/f.txt
           git add -A && git -c user.name=t -c user.email=t@t commit -qm link
           git push -q origin HEAD:refs/heads/main && git fetch -q origin
           """.formatted(co));
        var out = batch(modules(null),
                        mutation("mA", "A", "ATest", "fine"),
                        """
                            {"id":"mC","file":"C/src/f.txt","kind":"lit","old":"ko","new":"fine","expect":["CTest"]}""");

        assertThat(out.outcomes()).containsEntry("mA", "SURVIVED").containsEntry("mC", "APPLY-INVALID");
        assertThat(out.credited()).containsEntry("mA", "solo").containsEntry("mC", "-");
        assertThat(out.log()).contains("ambiguous -> re-running singly");
    }

    /// #112: bb-mutate adds `-pl` from the spec and `-am` only through `extra`. This row runs the REAL bb-build: B depends on A, A is not installed in
    /// `.m2-local`, so the control's `-pl B test` is refused at submit (rc 11) and bb-mutate stops there, the refusal in the detail, no mutant launched.
    @Test
    void aBuildWithoutAm_withASiblingNotInstalled_stopsAtTheControl_withBbBuildsRefusal() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var tools = home.resolve("tools");

        BbFixtures.executable(tools.resolve("bb-build"),
                              Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        for (var f : List.of("bb-queue-lib.sh", "bb-portgroup.py", "bb-modules.py")) {
            Files.copy(BbFixtures.OPS_BB.resolve(f), tools.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        BbFixtures.executable(tools.resolve("bb-cp-record"), "#!/bin/sh\nexit 0\n");
        BbFixtures.executable(tools.resolve("bb-netns-run"), "#!/bin/sh\nexit 0\n");
        var ns = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>";
        var parent = "<parent><groupId>g.x</groupId><artifactId>root</artifactId><version>1</version></parent>";

        Files.writeString(co.resolve("pom.xml"),
                          ns
                         + "<groupId>g.x</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>"
                         + "<modules><module>A</module><module>B</module><module>C</module></modules></project>\n");
        Files.writeString(co.resolve("A/pom.xml"), ns + parent + "<artifactId>A</artifactId></project>\n");
        Files.writeString(co.resolve("C/pom.xml"), ns + parent + "<artifactId>C</artifactId></project>\n");
        Files.writeString(co.resolve("B/pom.xml"),
                          ns + parent
                         + "<artifactId>B</artifactId><dependencies><dependency><groupId>g.x</groupId><artifactId>A</artifactId>"
                         + "</dependency></dependencies></project>\n");
        sh("""
           set -e
           cd "%s"
           git add -A && git -c user.name=t -c user.email=t@t commit -qm poms
           git push -q origin HEAD:refs/heads/main && git fetch -q origin
           """.formatted(co));
        buildJson = "{\"pl\":\"B\"}";
        okRcs = List.of(2);
        buildEnv = new HashMap<>(Map.of("BB_TEST_QUEUE_ROOT",
                                        dir.resolve("q").toString(),
                                        "BB_TEST_PRIORITIES",
                                        dir.resolve("none-priorities").toString(),
                                        "BB_TEST_PORT_MODULES",
                                        dir.resolve("none-modules").toString(),
                                        "BB_PORT_GROUP",
                                        "0"));
        var out = batch(modules(null), mutation("mB", "B", "BTest", "MUT"));

        assertThat(out.rc()).isEqualTo(2);
        assertThat(out.md()).contains("CONTROL CALL-REFUSED")
                  .contains("bb-build exit 11")
                  .contains("A=g.x:A:1")
                  .contains("add -am, or install them first");
        assertThat(out.log()).contains("[control] bb-build").doesNotContain("[mB] bb-build");
        assertThat(out.outcomes()).as("the mutant was never launched").containsEntry("mB", "NOT-RUN");
        assertThat(home.resolve("builds.log")).as("no build ran").doesNotExist();
        assertThat(dir.resolve("q/waiting")).as("nothing queued").isEmptyDirectory();
    }

    // ---- harness
    private String specName = "spec.json";
    private String buildJson = "{}";
    private List<String> extra = List.of();
    private List<Integer> okRcs = List.of(0, 5);
    private Map<String, String> buildEnv = Map.of();

    private record Result(int rc,
                          Map<String, String> outcomes,
                          Map<String, String> credited,
                          String stdout,
                          String log,
                          String md,
                          String tsvHeader,
                          List<String> tsvRows) {
        String tsvRow(String id) {
            return tsvRows.stream()
                          .filter(r -> r.startsWith(id + "\t"))
                          .findFirst()
                          .orElseThrow();
        }

        /// the all_failures column of a mutant's TSV row
        String allFailures(String id) {
            return tsvRow(id).split("\t", -1) [4];
        }

        /// the detail column of a mutant's TSV row (empty when the column is absent)
        String detail(String id) {
            var cols = tsvRow(id).split("\t", -1);

            return cols[cols.length - 1];
        }
    }

    /// scenario with one more top-level key, put right after the opening brace (so keys stack)
    private static String withKey(String scenario, String key, int value) {
        assertThat(scenario).startsWith("{");

        return "{\"" + key + "\":" + value + "," + scenario.substring(1);
    }

    /// A and C take the given faults when mutated; `key` = 2 on the scenario, so a build of both mutated reads the key
    private static String inertModules(String key, String a, String c) {
        return withKey("""
                       {"modules":[{"name":"A","class":"ATest","cases":["t1","t2"],"fault":"%s"},
                                    {"name":"B","deps":["A"],"class":"BTest","cases":["t1"]},
                                    {"name":"C","class":"CTest","cases":["t1"],"fault":"%s"}]}""".formatted(a, c),
                       key,
                       2);
    }

    /// The builds that bb-mutate must kill (a bound or the queue wait): its kill path lists /proc and calls `~/tools/bb-queue reap`.
    private void killableBuilds() throws IOException {
        assumeTrue(Files.isDirectory(Path.of("/proc")),
                   "the kill path of bb-mutate reads /proc");
        BbFixtures.executable(home.resolve("tools/bb-queue"), "#!/bin/sh\nexit 0\n");
    }

    private static String mutation(String id, String module, String test, String to) {
        return """
            {"id":"%s","file":"%s/src/f.txt","kind":"lit","old":"ok","new":"%s","expect":["%s"]}""".formatted(id,
                                                                                                              module,
                                                                                                              to,
                                                                                                              test);
    }

    /// reactor order A, B (depends on A), C; `fault` applies to module A only (null: none)
    private static String modules(String fault) {
        var fa = fault == null
                 ? "null"
                 : "\"" + fault + "\"";

        return """
            {"modules":[{"name":"A","class":"ATest","cases":["t1","t2"],"fault":%s},
                         {"name":"B","deps":["A"],"class":"BTest","cases":["t1"]},
                         {"name":"C","class":"CTest","cases":["t1"]}]}""".formatted(fa);
    }

    private Result batch(String scenario, String... mutations) throws Exception {
        return batchWith(null, scenario, mutations);
    }

    private Result batchWith(String tool, String scenario, String... mutations) throws Exception {
        var script = tool == null
                     ? TOOL
                     : Files.writeString(dir.resolve("old-bb-mutate"), tool);
        var out = dir.resolve("out");

        Files.writeString(home.resolve("scenario.json"), scenario);
        Files.writeString(dir.resolve(specName),
                          "{\"build\":" + buildJson + ",\"mutations\":[" + String.join(",", mutations) + "]}");
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", home.toString());
        env.put("BB_OWNER", "t");
        env.putAll(buildEnv);
        var argv = new java.util.ArrayList<>(List.of("python3",
                                                     script.toString(),
                                                     co.toString(),
                                                     dir.resolve(specName).toString(),
                                                     out.toString(),
                                                     "--batch"));

        argv.addAll(extra);
        var run = BbFixtures.run(argv, env, null, null);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isIn(okRcs);
        var outcomes = new HashMap<String, String>();
        var credited = new HashMap<String, String>();

        Files.readAllLines(out.resolve("results.tsv"))
             .stream()
             .skip(1)
             .map(l -> l.split("\t"))
             .forEach(c -> {
                 outcomes.put(c[0], c[1]);
                 credited.put(c[0], c[2]);
             });

        return new Result(run.rc(),
                          outcomes,
                          credited,
                          run.stdout(),
                          Files.readString(out.resolve("bb-mutate.log")),
                          Files.readString(out.resolve("results.md")),
                          Files.readAllLines(out.resolve("results.tsv")).get(0),
                          Files.readAllLines(out.resolve("results.tsv")).stream().skip(1).toList());
    }

    /// one `[mutated modules] fae` line per build the fake bb-build ran
    private List<String> builds() throws IOException {
        return Files.readAllLines(home.resolve("builds.log"));
    }

    private void sh(String script) throws Exception {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        var run = BbFixtures.run(List.of("bash", "-c", script), env, null, null);

        assertThat(run.rc()).as(run.stderr()).isZero();
    }

    private static boolean has(String tool) {
        try {
            return BbFixtures.run(List.of(tool, "--version"),
                                  new HashMap<>(Map.of("PATH",
                                                       System.getenv("PATH"))),
                                  null,
                                  null)
                             .rc() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = BbMutateIT.class.getResourceAsStream("/bb/" + name)) {
            assertThat(in).as("test resource bb/" + name).isNotNull();

            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
