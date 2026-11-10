package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/dev/land-pr` (owner decision D-2): the merge the owner approved, and only once the exact head is green. The
/// sandbox's origin is a local bare repository; `gh` is a fake answering from `$HOME/gh/<key>` (`<key>.<n>` for its
/// n-th call, `<key>.sh` sourced, or `<key>` printed; the check APIs look up `<key>-<sha>` first) with what the real
/// `--jq` expressions print, and recording each call's arguments as `[arg] ...` in `$HOME/gh/calls`.
/// Probes: the --approved check removed → [#withoutApproval_nothingIsReadOrChanged] red; the checks read for the
/// PR's head instead of the merge with main → [#greenOnlyOnTheHeadBeforeMergingMain_isNotGreen] red; "none" judged
/// green → [#noChecks_timesOut_withoutMerging] red; the rerun repeated while red →
/// [#knownFlake_redAgainAfterTheRerun_failsWithoutASecondRerun] red; [#only_flakes] true for any test →
/// [#aFailureOtherThanAKnownFlake_failsWithoutRerun] red; the re-read before the merge removed →
/// [#headMovedDuringCi_isRefused] red; `--force` on the push → [#aBranchMovedWhileMergingMain_isNotOverwritten] red;
/// the subject check skipped → [#aCommitBreakingTheOneLineRule_isRefused] red; the contains-main re-check after CI
/// removed → [#mainMovingDuringCi_isMergedInAgain_andCiRunsOnTheNewSha] red; unparsed failure lines ignored, the
/// `[<n>]` of a parameterised test not parsed, or `-x` dropped from the flake match → rows of
/// [#aKnownFlakeBesideAnythingElse_failsWithoutRerun] red; the job-conclusion or job-log check removed → rows of
/// [#aKnownFlakeBesideACheckThatIsNoFailedJob_failsWithoutRerun] red; the red-at-once after a rerun removed →
/// [#aFailureDuringTheRerun_isRedAtOnce] red; required checks ignored → [#aRequiredCheckNotReported_isPending_untilTheTimeout]
/// red; --paginate dropped from either API read → its row of [#aFailureOnTheSecondPage_isRed] red; the title read
/// through @tsv again → [#aTitleWithABackslash_keepsIt] red; the dirty-checkout guard removed →
/// [#aCheckoutWithTrackedChanges_isLeftAsItIs] red; the per-round deadline removed →
/// [#mainMovingPastTheDeadline_timesOutWithoutAnotherMerge] red; the re-merge cap removed →
/// [#mainMovingAfterEveryCi_isGivenUpAfterFiveReMerges] red; required checks matched by name alone →
/// [#aRequiredCheckMetOnlyByAStatusOrAnotherApp_isPending] red; a rerun per job →
/// [#twoFlakyJobsOfOneRun_areRerunWithOneCall] red; `##[error]` lines or the summary count ignored → the `setup` or
/// summary row of [#aKnownFlakeBesideAnythingElse_failsWithoutRerun] red; CR kept → [#knownFlakesWithCrlf_stillMatch]
/// red; trailing content accepted → [#aConfigLineWithTrailingContent_isRefused] red; the app check on the run id
/// removed → [#anotherAppsCheck_withAnActionsUrl_failsWithoutRerun] red; a blank allowed in known-flakes → the
/// `A.b c` row of [#aConfigLineWithTrailingContent_isRefused] red; the missing-file check removed →
/// [#aMissingRequiredChecksFile_isRefused] red; the flake match piped from printf again →
/// [#aKnownFlakesListLargerThanAPipeBuffer_stillMatchesItsFirstLine] red.
class LandPrIT {
    private static final String FLAKE = "org.pragmatica.temper.worker.unit.TestcontainersReapTest.await_completes_afterThirtyThousandPolls";

    @TempDir
    Path dir;
    private DevSandbox sandbox;
    private Path gh;
    private String head;

    @BeforeEach
    void sandbox() throws Exception {
        sandbox = new DevSandbox(dir);
        gh = Files.createDirectories(sandbox.home.resolve("gh"));
        sandbox.fake("gh", """
            d=$HOME/gh
            for a; do printf '[%s] ' "$a"; done >> "$d/calls"; echo >> "$d/calls"
            respond() {
              n=$(( $(cat "$d/$1.n" 2> /dev/null || echo 0) + 1 )); echo $n > "$d/$1.n"
              if [ -f "$d/$1.$n" ]; then cat "$d/$1.$n"
              elif [ -f "$d/$1.sh" ]; then . "$d/$1.sh"
              elif [ -f "$d/$1" ]; then cat "$d/$1"
              else return 1; fi
              return 0
            }
            case "$1 $2" in
              "pr view") case "$*" in *mergeCommit*) k=merged ;; *) k=pr ;; esac ;;
              "pr merge") k=merge ;;
              "run list") k=running ;;
              "run view") k=log-$4 ;;
              "run watch") k=watch ;;
              "run rerun") k=rerun ;;
              "api "*) sha=${2#*/commits/}; sha=${sha%%/*}
                       case "$2" in */check-runs) k=checks ;; *) k=status ;; esac
                       respond "$k-$sha" || respond "$k" || true
                       case "$*" in *--paginate*) cat "$d/$k-$sha.page2" "$d/$k.page2" 2> /dev/null ;; esac
                       exit 0 ;;
              *) echo "fake gh: unexpected $*" >&2; exit 64 ;;
            esac
            respond "$k" || true
            exit $(cat "$d/$k.rc" 2> /dev/null || echo 0)""");
        respond("pr.sh", pr("OPEN", "main", "false", "false", "feat(dev): thing"));
        respond("merge.sh", """
            rm -rf "$d/m"
            ( git clone -q '%s' "$d/m" && cd "$d/m" && git merge -q --no-ff -m "chore: merged by the fake" origin/topic \\
              && git push -q origin HEAD:main && git rev-parse HEAD > "$d/merged" ) > /dev/null 2>&1""".formatted(sandbox.origin));
        sandbox.env.put("TEMPER_LAND_POLL_SECONDS", "1");
        sandbox.git(sandbox.main, "checkout", "-q", "-b", "topic");
        commit("thing.txt", "thing\n", "feat: thing");
        sandbox.git(sandbox.main, "push", "-q", "origin", "topic");
        sandbox.git(sandbox.main, "checkout", "-q", "main");
        head = sandbox.git(sandbox.main, "rev-parse", "topic");
    }

    @Test
    void withoutApproval_nothingIsReadOrChanged() throws Exception {
        respond("checks", run(1, "success", "build"));
        var outcome = sandbox.run("land-pr", "7");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: no --approved");
        assertThat(gh.resolve("calls")).as("gh never called").doesNotExist();
        assertThat(originHead("main")).isEqualTo(sandbox.git(sandbox.main, "rev-parse", "main"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MERGED|main|false|false|is MERGED, not OPEN", "CLOSED|main|false|false|is CLOSED, not OPEN",
                            "OPEN|develop|false|false|is based on develop, not main", "OPEN|main|true|false|is a draft",
                            "OPEN|main|false|true|comes from a fork"})
    void aPrThatIsNotLandable_isRefused(String row) throws Exception {
        var field = row.split("\\|");
        respond("pr.sh", pr(field[0], field[1], field[2], field[3], "feat: thing"));
        respond("checks", run(1, "success", "build"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("refused: #7 " + field[4]);
        assertThat(calls()).doesNotContain("[merge]").doesNotContain("[api]");
    }

    @Test
    void originBranchNotAtThePrHead_isRefused() throws Exception {
        respond("pr.sh", "printf 'OPEN\\tmain\\ttopic\\t%s\\tfalse\\tfalse\\nfeat: thing\\n' 0123456789012345678901234567890123456789");
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("refused: origin's topic is at " + head);
    }

    @Test
    void aCommitBreakingTheOneLineRule_isRefused() throws Exception {
        sandbox.git(sandbox.main, "checkout", "-q", "topic");
        commit("a.txt", "a\n", "feat: a\n\nCo-Authored-By: x <x@x>");
        commit("b.txt", "b\n", "Update b.txt");
        sandbox.git(sandbox.main, "push", "-q", "origin", "topic");
        sandbox.git(sandbox.main, "checkout", "-q", "main");
        respond("checks", run(1, "success", "build"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("break the one-line rule")
                                    .contains("has more than one line (a body or trailers): 'feat: a'")
                                    .contains("does not start with a conventional prefix: 'Update b.txt'")
                                    .doesNotContain(head.substring(0, 12));
        assertThat(calls()).doesNotContain("[merge]");
    }

    @Test
    void green_mergesWithTheExactCommandLine_andFastForwardsMain() throws Exception {
        respond("checks-" + head, run(1, "success", "build") + run(2, "skipped", "docs") + run(3, "neutral", "lint"));
        respond("status-" + head, "success\tci/external\n");
        respond("checks", run(9, "failure", "build"));
        var outcome = land("--allow-neutral", "docs", "--allow-neutral", "lint");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        var merged = originHead("main");
        assertThat(outcome.output()).contains("ok: approval: --approved given for #7")
                                    .contains("ok: pr: #7 'feat(dev): thing' is open")
                                    .contains("ok: subjects: the 1 commits of origin/main..topic keep the one-line rule")
                                    .contains("ok: main: topic contains origin/main")
                                    .contains("ok: ci: green on " + head + ": 4 checks")
                                    .contains("ok: head: #7 is still at " + head)
                                    .contains("ok: merge: #7 merged as 'chore: merge thing (#7)'")
                                    .contains("ok: main: merge commit " + merged + " chore: merged by the fake")
                                    .contains("ok: checkout: fast-forwarded " + sandbox.main);
        assertThat(calls()).contains("[pr] [merge] [7] [--merge] [--subject] [chore: merge thing (#7)] [--body] [] "
                                     + "[--match-head-commit] [" + head + "]")
                           .contains("[api] [repos/{owner}/{repo}/commits/" + head + "/check-runs]");
        assertThat(sandbox.git(sandbox.main, "rev-parse", "HEAD")).isEqualTo(merged);
    }

    /// Neutral and skipped are green only for a check named in --allow-neutral; a failed commit status is red.
    @ParameterizedTest
    @ValueSource(strings = {"neutral", "skipped", "status"})
    void neutralOrSkippedNotAllowed_orAFailedStatus_isRed(String kind) throws Exception {
        if (kind.equals("status")) {
            respond("checks", run(1, "success", "build"));
            respond("status", "error\tci/external\n");
        } else {
            respond("checks", run(1, "success", "build") + run(2, kind, "docs"));
        }
        var outcome = land("--allow-neutral", "build");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(5);
        assertThat(outcome.output()).contains("FAILED: ci: red on " + head)
                                    .contains(kind.equals("status") ? "ci/external (failure)" : "docs (" + kind + ")");
        assertThat(calls()).doesNotContain("[merge]").doesNotContain("[rerun]");
    }

    @Test
    void mainMovedOn_isMergedIn_andCiJudgedOnTheMergeCommit() throws Exception {
        commit("other.txt", "other\n", "feat: other");
        sandbox.git(sandbox.main, "push", "-q", "origin", "main");
        var main = sandbox.git(sandbox.main, "rev-parse", "HEAD");
        respond("checks", run(1, "success", "build"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        var merge = sandbox.git(sandbox.main, "rev-parse", "origin/topic");
        assertThat(merge).isNotEqualTo(head);
        assertThat(sandbox.git(sandbox.main, "log", "-1", "--format=%P%n%B", merge)).isEqualTo(head + " " + main + "\nchore: merge main into topic");
        assertThat(outcome.output()).contains("ok: main: merged origin/main into topic as " + merge + " and pushed it")
                                    .contains("ok: ci: green on " + merge);
        assertThat(calls()).contains("[repos/{owner}/{repo}/commits/" + merge + "/check-runs]")
                           .doesNotContain("commits/" + head + "/")
                           .contains("[--match-head-commit] [" + merge + "]");
        assertThat(sandbox.git(sandbox.main, "worktree", "list")).as("temporary worktree removed").doesNotContain("land-pr");
    }

    @Test
    void greenOnlyOnTheHeadBeforeMergingMain_isNotGreen() throws Exception {
        commit("other.txt", "other\n", "feat: other");
        sandbox.git(sandbox.main, "push", "-q", "origin", "main");
        respond("checks-" + head, run(1, "success", "build"));
        respond("checks", run(2, "in_progress", "", "build"));
        var outcome = land("--timeout", "2");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(6);
        assertThat(outcome.output()).contains("FAILED: ci: timed out after 2s on " + originHead("topic") + ": build");
        assertThat(calls()).doesNotContain("[merge]");
    }

    @Test
    void mergingMainConflicts_leavesTheBranch_andNamesTheFiles() throws Exception {
        sandbox.git(sandbox.main, "checkout", "-q", "topic");
        commit("README", "topic\n", "feat: readme on topic");
        sandbox.git(sandbox.main, "push", "-q", "origin", "topic");
        sandbox.git(sandbox.main, "checkout", "-q", "main");
        commit("README", "main\n", "feat: readme on main");
        sandbox.git(sandbox.main, "push", "-q", "origin", "main");
        var topic = originHead("topic");
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(4);
        assertThat(outcome.output()).contains("FAILED: main: merging origin/main into topic conflicts in: README;");
        assertThat(originHead("topic")).isEqualTo(topic);
        assertThat(sandbox.git(sandbox.main, "worktree", "list").lines()).hasSize(1);
        assertThat(calls()).doesNotContain("[api]").doesNotContain("[merge]");
    }

    /// The push of the merge with main is a plain one: a branch someone pushed to meanwhile is not overwritten.
    @Test
    void aBranchMovedWhileMergingMain_isNotOverwritten() throws Exception {
        commit("other.txt", "other\n", "feat: other");
        sandbox.git(sandbox.main, "push", "-q", "origin", "main");
        var hooks = Files.createDirectories(dir.resolve("hooks"));
        var hook = Files.writeString(hooks.resolve("post-merge"), """
            #!/bin/sh
            unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE
            rm -rf "%1$s/late" && git clone -q -b topic '%2$s' "%1$s/late" > /dev/null 2>&1 && cd "%1$s/late" \\
              && echo late > late.txt && git add late.txt && git commit -q -m "feat: late" && git push -q origin HEAD:topic
            """.formatted(dir, sandbox.origin));
        hook.toFile().setExecutable(true);
        sandbox.git(sandbox.main, "config", "core.hooksPath", hooks.toString());
        respond("checks", run(1, "success", "build"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(7);
        assertThat(outcome.output()).contains("FAILED: git push of the merge with main to topic");
        assertThat(sandbox.git(sandbox.main, "log", "-1", "--format=%s", originHeadRef("topic"))).isEqualTo("feat: late");
        assertThat(calls()).doesNotContain("[merge]");
    }

    @Test
    void pending_isWatched_untilGreen() throws Exception {
        respond("checks-" + head + ".1", run(1, "in_progress", "", "build"));
        respond("checks", run(1, "success", "build"));
        respond("running.1", "4242\n");
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(calls()).contains("[run] [watch] [4242]").contains("[merge]");
    }

    @Test
    void pendingStatus_isPolled_untilGreen() throws Exception {
        respond("checks", run(1, "success", "build"));
        respond("status.1", "pending\tci/external\n");
        respond("status", "success\tci/external\n");
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(calls()).doesNotContain("[watch]").contains("[merge]");
    }

    @Test
    void noChecks_timesOut_withoutMerging() throws Exception {
        var outcome = land("--timeout", "2");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(6);
        assertThat(outcome.output()).contains("FAILED: ci: timed out after 2s on " + head + ": no check reported");
        assertThat(calls()).doesNotContain("[merge]");
    }

    @Test
    void neverCompleting_timesOut_namingThePendingChecks() throws Exception {
        respond("checks", run(1, "success", "build") + run(2, "queued", "", "it"));
        respond("running", "77\n");
        var outcome = land("--timeout", "2");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(6);
        assertThat(outcome.output()).contains("FAILED: ci: timed out after 2s on " + head + ": it");
        assertThat(calls()).doesNotContain("[merge]");
    }

    @Test
    void aFailureOtherThanAKnownFlake_failsWithoutRerun() throws Exception {
        respond("checks", run(5, "failure", "build"));
        respond("log-5", log(FLAKE + "(Path)", "FAILURE") + log("org.example.OtherTest.breaks", "ERROR"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(5);
        assertThat(outcome.output()).contains("FAILED: ci: red on " + head + ": build (failure); job 5 failed by more than known flakes;")
                                    .contains("failing tests: org.example.OtherTest.breaks " + FLAKE);
        assertThat(calls()).doesNotContain("[rerun]").doesNotContain("[merge]");
    }

    /// Flake laundering: a failed job is a flake only when every failure in its log is a known flake's test line. A
    /// `line` row adds a line of the maven step to the log of the job the known flake failed: a compile error, a failed
    /// jbct goal, a failure line that does not parse, one more module summary counting a failure no line names. A
    /// `setup` row adds an `##[error]` of another step; a `test` row a further failed test: a parameterised real
    /// failure, a test whose name is part of the flake's. An `other` row is the log of a second failed job: only a
    /// compile error, or BUILD FAILURE with no test line.
    @ParameterizedTest
    @ValueSource(strings = {"line|[ERROR] COMPILATION ERROR : ",
                            "line|[ERROR] Failed to execute goal org.pragmatica-lite:jbct-maven-plugin:1.0.0-rc4:check (jbct-check) on project temper-worker: 2 violations",
                            "line|[ERROR] org.example.Odd Name -- Time elapsed: 0.01 s <<< ERROR!",
                            "line|[ERROR] Tests run: 40, Failures: 1, Errors: 0, Skipped: 0",
                            "setup|##[error]Unable to resolve action actions/setup-java@v9",
                            "test|org.example.ParamTest.adds(int)[2]",
                            "test|org.pragmatica.temper.worker.unit.TestcontainersReapTest.await_completes",
                            "other|[ERROR] COMPILATION ERROR : ",
                            "other|[INFO] BUILD FAILURE"})
    void aKnownFlakeBesideAnythingElse_failsWithoutRerun(String row) throws Exception {
        var field = row.split("\\|", 2);
        var at = "\t2026-10-24T09:28:59.9670049Z " + field[1] + "\n";
        var job = field[0].equals("other") ? 7 : 5;
        var flake = log(FLAKE + "(Path)", "FAILURE");
        switch (field[0]) {
            case "line" -> respond("log-5", flake + "build\tBuild and test" + at);
            case "setup" -> respond("log-5", "build\tSet up job" + at + flake);
            case "test" -> respond("log-5", flake + log(field[1], "FAILURE"));
            default -> {
                respond("log-5", flake);
                respond("log-7", "it\tBuild and test" + at);
            }
        }
        respond("checks", run(5, "failure", "build") + (job == 7 ? run(7, "failure", "it") : ""));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(5);
        assertThat(outcome.output()).contains("FAILED: ci: red on " + head).contains("job " + job + " failed by more than known flakes");
        assertThat(calls()).doesNotContain("[rerun]").doesNotContain("[merge]");
    }

    /// A check of another app is no Actions job, even with an Actions-looking details_url: never rerun.
    @Test
    void anotherAppsCheck_withAnActionsUrl_failsWithoutRerun() throws Exception {
        respond("checks", run(5, "failure", "build")
                          + "7\tcompleted\tfailure\tcircleci\thttps://github.com/o/r/actions/runs/900/job/7\tit\n");
        respond("log-5", log(FLAKE + "(Path)", "FAILURE"));
        respond("log-7", log(FLAKE + "(Path)", "FAILURE"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(5);
        assertThat(outcome.output()).contains("check 7 is no Actions job");
        assertThat(calls()).doesNotContain("[rerun]").doesNotContain("[merge]");
    }

    @Test
    void aMissingRequiredChecksFile_isRefused() throws Exception {
        Files.delete(sandbox.main.resolve("ops/dev/required-checks"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("refused: land-pr: ops/dev/required-checks missing").doesNotContain("awk");
        assertThat(gh.resolve("calls")).doesNotExist();
    }

    /// Two jobs of one run failed by the flake: one `gh run rerun <run> --failed`, as a second call while the run
    /// re-runs may be refused.
    @Test
    void twoFlakyJobsOfOneRun_areRerunWithOneCall() throws Exception {
        respond("checks-" + head + ".1", run(5, "failure", "build") + run(7, "failure", "it"));
        respond("checks", run(6, "success", "build") + run(8, "success", "it"));
        respond("log-5", log(FLAKE + "(Path)", "FAILURE"));
        respond("log-7", log(FLAKE + "(Path)", "FAILURE"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(count("[rerun]")).isEqualTo(1);
        assertThat(calls()).contains("[run] [rerun] [900] [--failed]");
    }

    /// known-flakes written with CRLF still names the flake: CR is stripped.
    @Test
    void knownFlakesWithCrlf_stillMatch() throws Exception {
        Files.writeString(sandbox.main.resolve("ops/dev/known-flakes"), "# flakes\r\n\r\n" + FLAKE + "\r\n");
        respond("checks-" + head + ".1", run(5, "failure", "build"));
        respond("checks", run(6, "success", "build"));
        respond("log-5", log(FLAKE + "(Path)", "FAILURE"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(count("[rerun]")).isEqualTo(1);
    }

    /// A known-flakes list larger than a pipe buffer, the flake on its first line: `grep -q` stops reading at the
    /// match, so a writer piped into it (`printf | grep -q` under pipefail) dies of SIGPIPE (141) and the flake reads as
    /// unknown. The list goes to grep as a here-string.
    @Test
    void aKnownFlakesListLargerThanAPipeBuffer_stillMatchesItsFirstLine() throws Exception {
        var others = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            others.append("org.example.padding.Padding")
                  .append(i)
                  .append("Test.aTestNameLongEnoughToFillTheBuffer\n");
        }
        Files.writeString(sandbox.main.resolve("ops/dev/known-flakes"), FLAKE + "\n" + others);
        respond("checks-" + head + ".1", run(5, "failure", "build"));
        respond("checks", run(6, "success", "build"));
        respond("log-5", log(FLAKE + "(Path)", "FAILURE"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(count("[rerun]")).isEqualTo(1);
    }

    /// A line with trailing content would match nothing; it is refused, naming file and line, before gh is asked.
    @ParameterizedTest
    @ValueSource(strings = {"required-checks|# checks\nbuild # the CI job\n|ops/dev/required-checks:2",
                            "known-flakes|" + FLAKE + " #38\n|ops/dev/known-flakes:1",
                            "known-flakes|# flakes\norg.example.A.b c\n|ops/dev/known-flakes:2"})
    void aConfigLineWithTrailingContent_isRefused(String row) throws Exception {
        var field = row.split("\\|");
        Files.writeString(sandbox.main.resolve("ops/dev/" + field[0]), field[1]);
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("refused: " + field[2] + ": ").contains("has trailing content");
        assertThat(gh.resolve("calls")).doesNotExist();
    }

    /// A parameterised test's `(<params>)[<n>]` is dropped before matching: listed, it is a flake.
    @Test
    void aParameterisedKnownFlake_isRerun() throws Exception {
        respond("checks-" + head + ".1", run(5, "failure", "build"));
        respond("checks", run(6, "success", "build"));
        respond("log-5", log("org.example.ParamTest.adds(int)[2]", "FAILURE"));
        var outcome = land("--flake", "org.example.ParamTest.adds");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(count("[rerun]")).isEqualTo(1);
    }

    /// No rerun unless every failed check is an Actions job that failed by known flakes: a cancelled job, a check run
    /// that is no Actions job (no log), a failed status.
    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "no-job", "status"})
    void aKnownFlakeBesideACheckThatIsNoFailedJob_failsWithoutRerun(String kind) throws Exception {
        respond("log-5", log(FLAKE + "(Path)", "FAILURE"));
        switch (kind) {
            case "cancelled" -> {
                respond("checks", run(5, "failure", "build") + run(7, "cancelled", "it"));
                respond("log-7", log(FLAKE + "(Path)", "FAILURE"));  // its log alone would pass for a flake
            }
            case "no-job" -> {
                respond("checks", run(5, "failure", "build") + run(7, "failure", "external"));
                respond("log-7.rc", "1");
            }
            default -> {
                respond("checks", run(5, "failure", "build"));
                respond("status", "failure\tci/external\n");
            }
        }
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(5);
        assertThat(outcome.output()).contains(switch (kind) {
            case "cancelled" -> "check 7 concluded cancelled";
            case "no-job" -> "check 7 has no Actions job log";
            default -> "a commit status failed";
        });
        assertThat(calls()).doesNotContain("[rerun]");
    }

    /// After the rerun a check that fails is red at once, while the rerun jobs still run: waiting cannot change it.
    @Test
    void aFailureDuringTheRerun_isRedAtOnce() throws Exception {
        respond("checks-" + head + ".1", run(5, "failure", "build") + run(8, "success", "lint"));
        respond("checks", run(5, "failure", "build") + run(8, "failure", "lint"));
        respond("log-5", log(FLAKE + "(Path)", "FAILURE"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(5);
        assertThat(outcome.output()).contains("FAILED: ci: red again after the one rerun on " + head + ": lint (failure)");
        assertThat(count("[rerun]")).isEqualTo(1);
    }

    /// A check named in ops/dev/required-checks (`build`) that never reports keeps CI pending, however green the rest.
    @Test
    void aRequiredCheckNotReported_isPending_untilTheTimeout() throws Exception {
        respond("checks", run(1, "success", "lint"));
        var outcome = land("--timeout", "2");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(6);
        assertThat(outcome.output()).contains("FAILED: ci: timed out after 2s on " + head + ": build (not reported)");
        assertThat(calls()).doesNotContain("[merge]");
    }

    /// Only an Actions check run satisfies a required check: a commit status or another app's check run named
    /// `build` does not.
    @ParameterizedTest
    @ValueSource(strings = {"status", "app"})
    void aRequiredCheckMetOnlyByAStatusOrAnotherApp_isPending(String kind) throws Exception {
        if (kind.equals("status")) {
            respond("checks", run(1, "success", "lint"));
            respond("status", "success\tbuild\n");
        } else {
            respond("checks", run(1, "success", "lint") + check(2, "completed", "success", "other-ci", "build"));
        }
        var outcome = land("--timeout", "2");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(6);
        assertThat(outcome.output()).contains("FAILED: ci: timed out after 2s on " + head + ": build (not reported)");
    }

    /// The second page of check runs or statuses holds a failure; read without --paginate it would be missed.
    @ParameterizedTest
    @ValueSource(strings = {"checks", "status"})
    void aFailureOnTheSecondPage_isRed(String key) throws Exception {
        respond("checks", run(1, "success", "build"));
        respond(key + ".page2", key.equals("checks") ? run(2, "failure", "it") : "failure\tci/external\n");
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(5);
        assertThat(calls()).doesNotContain("[merge]");
    }

    /// The seeded flake of issue #38 as surefire 3.5 printed it on run 40081606980; the rerun's replaced check run
    /// (same id) counts as pending until the new one reports.
    @Test
    void knownFlake_isRerunOnce_thenGreen() throws Exception {
        respond("checks-" + head + ".1", run(5, "failure", "build"));
        respond("checks-" + head + ".2", run(5, "failure", "build"));
        respond("checks", run(6, "success", "build"));
        respond("log-5", log(FLAKE + "(Path)", "FAILURE"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("ok: ci: red on " + head + " only by known flakes (" + FLAKE + "); reran the failed jobs once");
        assertThat(count("[run] [rerun] [900] [--failed]")).isEqualTo(1);
        assertThat(calls()).contains("[merge]");
    }

    @Test
    void knownFlake_redAgainAfterTheRerun_failsWithoutASecondRerun() throws Exception {
        respond("checks-" + head + ".1", run(5, "failure", "build"));
        respond("checks", run(6, "failure", "build"));
        respond("log-5", log(FLAKE + "(Path)", "FAILURE"));
        respond("log-6", log(FLAKE + "(Path)", "FAILURE"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(5);
        assertThat(outcome.output()).contains("FAILED: ci: red again after the one rerun on " + head + ": build (failure); failing tests: " + FLAKE);
        assertThat(count("[rerun]")).isEqualTo(1);
        assertThat(calls()).doesNotContain("[merge]");
    }

    @Test
    void aFlakeNamedOnTheCommandLine_isRerun() throws Exception {
        respond("checks-" + head + ".1", run(5, "failure", "build"));
        respond("checks", run(6, "success", "build"));
        respond("log-5", log("org.example.OtherTest.flickers", "ERROR"));
        var outcome = land("--flake", "org.example.OtherTest.flickers");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(count("[rerun]")).isEqualTo(1);
    }

    @Test
    void headMovedDuringCi_isRefused() throws Exception {
        respond("checks-" + head + ".sh", """
            rm -rf "$d/late"
            ( git clone -q -b topic '%s' "$d/late" && cd "$d/late" && echo late > late.txt && git add late.txt \\
              && git commit -q -m "feat: late" && git push -q origin HEAD:topic ) > /dev/null 2>&1
            printf '%s'""".formatted(sandbox.origin, run(1, "success", "build").replace("\t", "\\t").replace("\n", "\\n")));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("refused: head: #7 moved to " + originHead("topic") + " after CI was judged on " + head);
        assertThat(calls()).doesNotContain("[merge]");
    }

    /// main moves while CI runs on a head that contained it: merged in again, CI on the new SHA, then the merge.
    @Test
    void mainMovingDuringCi_isMergedInAgain_andCiRunsOnTheNewSha() throws Exception {
        respond("checks-" + head + ".sh", """
            [ -f "$d/moved" ] || ( rm -rf "$d/c" && git clone -q '%s' "$d/c" && cd "$d/c" && echo moved > moved.txt \\
              && git add moved.txt && git commit -q -m "feat: moved" && git push -q origin HEAD:main && touch "$d/moved" ) > /dev/null 2>&1
            printf '%s'""".formatted(sandbox.origin, run(1, "success", "build").replace("\t", "\\t").replace("\n", "\\n")));
        respond("checks", run(2, "success", "build"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        var merge = sandbox.git(sandbox.main, "rev-parse", "origin/topic");
        var main = sandbox.git(sandbox.main, "rev-parse", "origin/main~1");  // the fake's merge of #7 sits on top
        assertThat(sandbox.git(sandbox.main, "log", "-1", "--format=%P%n%s", merge)).isEqualTo(head + " " + main + "\nchore: merge main into topic");
        assertThat(outcome.output()).contains("ok: ci: green on " + head)
                                    .contains("ok: main: origin/main moved to " + main + " during CI; merging it in again")
                                    .contains("ok: main: merged origin/main into topic as " + merge)
                                    .contains("ok: ci: green on " + merge);
        assertThat(calls()).contains("[repos/{owner}/{repo}/commits/" + merge + "/check-runs]")
                           .contains("[--match-head-commit] [" + merge + "]");
    }

    /// main moving after every CI: re-merged 5 times, then given up (exit 6), not looped for the whole timeout.
    @Test
    void mainMovingAfterEveryCi_isGivenUpAfterFiveReMerges() throws Exception {
        respond("checks.sh", moveMain() + "printf '%s'".formatted(tsv(run(1, "success", "build"))));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(6);
        assertThat(outcome.output()).contains("FAILED: main: origin/main kept moving: it moved after CI 6 times (at most 5 merges of main)");
        assertThat(outcome.output().lines().filter(line -> line.contains("merging it in again"))).hasSize(5);
        assertThat(calls()).doesNotContain("[merge]");
    }

    /// The deadline holds on every round, not only while a check is pending.
    @Test
    void mainMovingPastTheDeadline_timesOutWithoutAnotherMerge() throws Exception {
        respond("checks.sh", moveMain() + "printf '%s'".formatted(tsv(run(1, "success", "build"))));
        var outcome = land("--timeout", "0");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(6);
        assertThat(outcome.output()).contains("FAILED: ci: timed out after 0s: origin/main moved after CI on " + head)
                                    .doesNotContain("merging it in again");
        assertThat(originHead("topic")).isEqualTo(head);
    }

    /// The subject is built from the raw title: no @tsv escaping doubles a backslash.
    @Test
    void aTitleWithABackslash_keepsIt() throws Exception {
        respond("pr.sh", pr("OPEN", "main", "false", "false", "feat: a\\b"));
        respond("checks", run(1, "success", "build"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(calls()).contains("[--subject] [chore: merge a\\b (#7)]");
    }

    @Test
    void aCheckoutWithTrackedChanges_isLeftAsItIs() throws Exception {
        Files.writeString(sandbox.main.resolve("README"), "mine\n");
        var before = sandbox.git(sandbox.main, "rev-parse", "HEAD");
        respond("checks", run(1, "success", "build"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("ok: checkout: " + sandbox.main + " has tracked changes; left as it is");
        assertThat(sandbox.git(sandbox.main, "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(sandbox.main.resolve("README")).hasContent("mine");
    }

    @Test
    void aCheckoutNotOnMain_isLeftAsItIs() throws Exception {
        sandbox.git(sandbox.main, "checkout", "-q", "topic");
        respond("checks", run(1, "success", "build"));
        var outcome = land();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("ok: checkout: " + sandbox.main + " is not on main; left as it is");
        assertThat(sandbox.git(sandbox.main, "rev-parse", "HEAD")).isEqualTo(head);
    }

    /// A fake step pushing a new commit to origin's main.
    private String moveMain() {
        return """
            ( rm -rf "$d/c" && git clone -q '%s' "$d/c" && cd "$d/c" && echo $$ > moved.txt && git add moved.txt \\
              && git commit -q -m "feat: moved" && git push -q origin HEAD:main ) > /dev/null 2>&1
            """.formatted(sandbox.origin);
    }

    private static String tsv(String line) {
        return line.replace("\t", "\\t").replace("\n", "\\n");
    }

    private DevSandbox.Outcome land(String... extra) throws Exception {
        var args = new java.util.ArrayList<>(List.of("7", "--approved", "--timeout", "20"));  // a broken guard fails fast
        args.addAll(List.of(extra));
        return sandbox.run("land-pr", args.toArray(String[]::new));
    }

    private void respond(String key, String body) throws Exception {
        Files.writeString(gh.resolve(key), body);
    }

    private String calls() throws Exception {
        var calls = gh.resolve("calls");
        return Files.exists(calls) ? Files.readString(calls) : "";
    }

    private long count(String call) throws Exception {
        return calls().lines().filter(line -> line.contains(call)).count();
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(sandbox.main.resolve(file), content);
        sandbox.git(sandbox.main, "add", file);
        sandbox.git(sandbox.main, "commit", "-q", "-m", message);
    }

    private String originHead(String branch) throws Exception {
        return sandbox.git(sandbox.main, "ls-remote", "origin", "refs/heads/" + branch).split("\t")[0];
    }

    private String originHeadRef(String branch) throws Exception {
        sandbox.git(sandbox.main, "fetch", "-q", "origin", branch);
        return originHead(branch);
    }

    /// `gh pr view` as the land-pr --jq prints it (the fields @tsv, then the raw title), with the head read from
    /// origin's topic at each call.
    private String pr(String state, String base, String draft, String fork, String title) {
        return "printf '%s\\t%s\\ttopic\\t%s\\t%s\\t%s\\n%s\\n' " + state + " " + base + " \"$(git --git-dir='" + sandbox.origin
               + "' rev-parse refs/heads/topic)\" " + draft + " " + fork + " '" + title + "'";
    }

    /// A check run as the land-pr --jq prints it: id, status, conclusion, app slug, details_url, name; a job of the
    /// GitHub Actions run 900 unless another app is given.
    private static String run(long id, String conclusion, String name) {
        return run(id, "completed", conclusion, name);
    }

    private static String run(long id, String status, String conclusion, String name) {
        return check(id, status, conclusion, "github-actions", name);
    }

    private static String check(long id, String status, String conclusion, String app, String name) {
        var details = app.equals("github-actions") ? "https://github.com/o/r/actions/runs/900/job/" + id : "https://example.com/" + id;
        return id + "\t" + status + "\t" + conclusion + "\t" + app + "\t" + details + "\t" + name + "\n";
    }

    /// A surefire 3.5 test failure as `gh run view --log-failed` printed it on run 40081606980: the class summary, the
    /// test's line, the module's summary, an `##[error]` of the same (maven) step and the step's exit.
    private static String log(String test, String kind) {
        var prefix = "build\tBuild and test\t2026-10-24T09:28:59.9670049Z ";
        var failures = kind.equals("FAILURE") ? "1, Errors: 0" : "0, Errors: 1";
        return prefix + "[ERROR] Tests run: 12, Failures: " + failures + ", Skipped: 0, Time elapsed: 61.88 s <<< " + kind
               + "! -- in " + test.substring(0, test.lastIndexOf('.')) + "\n"
               + prefix + "[ERROR] " + test + " -- Time elapsed: 60.01 s <<< " + kind + "!\n"
               + prefix + "##[error]Exception in thread \"\" java.lang.NoClassDefFoundError: Could not initialize class ch.qos.logback.classic.spi.ThrowableProxy\n"
               + prefix + "[ERROR] Tests run: 363, Failures: " + failures + ", Skipped: 1\n"
               + prefix + "##[error]Process completed with exit code 1.\n";
    }
}
