package org.pragmatica.kiln.service.mirror;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.mirror.MirrorFixture.branch;
import static org.pragmatica.kiln.service.mirror.MirrorFixture.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [GitMirror]'s reading of git's exit codes, against a scripted runner: `diff --quiet` maps 0 to empty, 1 to a diff
/// and any other exit to UNKNOWN (never `false`); every other unexpected exit, an unreadable output or a runner failure
/// fails the answer. Also the argv and environment every call carries, and the serialised fetch.
class GitMirrorExitCodeTest {
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String ORIGIN = "https://github.com/o/r.git";
    private static final String SHALLOW = "rev-parse --is-shallow-repository";
    private static final String VERIFY = "rev-parse --verify";

    @TempDir
    Path dir;

    /// Answers each git subcommand from `answers` (rc 0 with no output when unnamed, except that the shallow check
    /// answers `false`), and records every command. `rev-parse` is keyed with its first option, since it asks two things.
    private static final class Scripted implements ProcessRunner {
        final Map<String, Promise<Completed>> answers = new ConcurrentHashMap<>(Map.of(SHALLOW,
                                                                                       Promise.success(completed(0,
                                                                                                                 "false\n"))));
        final List<Command> commands = new CopyOnWriteArrayList<>();

        Scripted answer(String subcommand, int rc, String stdout) {
            answers.put(subcommand, Promise.success(completed(rc, stdout)));
            return this;
        }

        Scripted answer(String subcommand, Promise<Completed> answer) {
            answers.put(subcommand, answer);
            return this;
        }

        @Override
        public Promise<Completed> run(Command command) {
            commands.add(command);
            return answers.getOrDefault(key(command), Promise.success(completed(0, "")));
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            return Causes.cause("unused").result();
        }

        List<String> subcommands() {
            return commands.stream().map(Scripted::key).toList();
        }

        static String key(Command command) {
            return command.argv().get(3).equals("rev-parse")
                   ? "rev-parse " + command.argv().get(4)
                   : command.argv().get(3);
        }
    }

    /// A runner failure and a failure to read git's output carry what failed, as a [MirrorError.Failed] wrapping it.
    private static Cause failure(Result<?> result) {
        return result.fold(cause -> cause, value -> Assertions.fail("expected a failure, got " + value));
    }

    private static Cause timedOutCause() {
        return ExecError.TimedOut.FACTORY.apply("git", TimeSpan.timeSpan(2).minutes());
    }

    private static Completed completed(int rc, String stdout) {
        return Completed.completed("git", rc, stdout.getBytes(StandardCharsets.UTF_8), new byte[0], false);
    }

    /// A present head with merge base `BASE` and a base three commits ahead of it.
    private static Scripted behindBy3() {
        return new Scripted().answer("merge-base", 0, BASE + "\n").answer("rev-list", 0, "3\n");
    }

    private Result<BehindFacts> behind(Scripted runner) {
        return on(runner, dir).behind(sha(HEAD), branch("main")).await();
    }

    private static GitMirror on(ProcessRunner runner, Path mirror) {
        return GitMirror.gitMirror(runner, ORIGIN, mirror).unwrap();
    }

    @Test
    void behind_diffExit0_isAnEmptyTree() {
        assertThat(behind(behindBy3().answer("diff", 0, "")).unwrap()).isEqualTo(new BehindFacts(some(3), some(true)));
    }

    @Test
    void behind_diffExit1_isATreeThatDiffers() {
        assertThat(behind(behindBy3().answer("diff", 1, "")).unwrap()).isEqualTo(new BehindFacts(some(3), some(false)));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 128, 129, 255, -1})
    void behind_diffExitOtherThan0Or1_isUnknown_neverFalse(int rc) {
        assertThat(behind(behindBy3().answer("diff", rc, "")).unwrap()).isEqualTo(new BehindFacts(some(3), none()));
    }

    @Test
    void behind_fails_whenTheDiffCannotRun() {
        var timedOut = timedOutCause().<Completed> promise();

        assertThat(failure(behind(behindBy3().answer("diff", timedOut)))).isEqualTo(new MirrorError.Failed("diff",
                                                                                                       timedOutCause(),
                                                                                                       "git diff failed"));
    }

    /// `rev-parse --verify --quiet` exits 1 for an object the mirror lacks: absent, so both facts are UNKNOWN.
    @Test
    void behind_isUnknown_whenTheHeadIsAbsent() {
        var runner = new Scripted().answer(VERIFY, 1, "");

        assertThat(behind(runner).unwrap()).isEqualTo(new BehindFacts(none(), none()));
        assertThat(runner.subcommands()).containsExactly(SHALLOW, VERIFY);
    }

    /// Any other exit of the presence check (a corrupt object or a broken mirror 128, a kill 137, a timeout) is a
    /// failure in every method, never "absent".
    @ParameterizedTest
    @ValueSource(ints = {128, 137, 2, -1})
    void everyMethod_fails_whenThePresenceCheckExitsOtherThan0Or1(int rc) {
        var runner = new Scripted().answer(VERIFY, rc, "");

        assertThat(behind(runner).isFailure()).isTrue();
        assertThat(on(runner, dir).reachable(Set.of(sha(HEAD))).await().isFailure()).isTrue();
        assertThat(on(runner, dir).ancestry(sha(HEAD), List.of(branch("main"))).await().isFailure()).isTrue();
    }

    @Test
    void everyMethod_fails_whenThePresenceCheckCannotRun() {
        var runner = new Scripted().answer(VERIFY, timedOutCause().<Completed> promise());

        assertThat(behind(runner).isFailure()).isTrue();
        assertThat(on(runner, dir).reachable(Set.of(sha(HEAD))).await().isFailure()).isTrue();
        assertThat(on(runner, dir).ancestry(sha(HEAD), List.of(branch("main"))).await().isFailure()).isTrue();
    }

    /// A shallow mirror answers nothing: every query fails, and so does the fetch that leaves it shallow. The control is
    /// the same runner answering `false`.
    @Test
    void everyMethod_fails_onAShallowMirror() {
        var shallow = new Scripted().answer(SHALLOW, 0, "true\n");

        assertThat(failure(behind(shallow))).isEqualTo(MirrorError.General.SHALLOW_MIRROR);
        assertThat(failure(on(shallow, dir).reachable(Set.of(sha(HEAD))).await())).isEqualTo(MirrorError.General.SHALLOW_MIRROR);
        assertThat(failure(on(shallow, dir).ancestry(sha(HEAD), List.of(branch("main"))).await())).isEqualTo(MirrorError.General.SHALLOW_MIRROR);
        assertThat(failure(on(shallow, dir).fetch().await())).isEqualTo(MirrorError.General.SHALLOW_MIRROR);
        assertThat(shallow.subcommands()).doesNotContain(VERIFY);
        assertThat(on(new Scripted(), dir).reachable(Set.of(sha(HEAD))).await().isSuccess()).isTrue();
    }

    @Test
    /// Over a script under which `behind` answers (the control), only the shallow check can fail it.
    void everyMethod_fails_whenTheShallowFlagIsUnreadableOrTheCheckFails() {
        assertThat(behind(behindBy3()).isSuccess()).isTrue();
        assertThat(shallowCheckFailure(behind(behindBy3().answer(SHALLOW, 0, "maybe\n")))).isTrue();
        assertThat(shallowCheckFailure(behind(behindBy3().answer(SHALLOW, 128, "")))).isTrue();
    }

    private static boolean shallowCheckFailure(Result<BehindFacts> answer) {
        return failure(answer) instanceof MirrorError.Failed failed && failed.operation().equals("rev-parse");
    }

    @Test
    void behind_isUnknown_forNoMergeBase() {
        assertThat(behind(new Scripted().answer("merge-base", 1, "")).unwrap()).isEqualTo(new BehindFacts(none(), none()));
    }

    @Test
    void behind_fails_whenMergeBaseFails() {
        assertThat(behind(new Scripted().answer("merge-base", 128, "")).isFailure()).isTrue();
        assertThat(behind(new Scripted().answer("merge-base", 1, BASE + "\n")).isFailure()).isTrue();
        assertThat(behind(new Scripted().answer("merge-base", 0, "not a sha\n")).isFailure()).isTrue();
    }

    @Test
    void behind_fails_whenTheCountFailsOrIsUnreadable() {
        assertThat(behind(behindBy3().answer("rev-list", 128, "")).isFailure()).isTrue();
        var unreadable = failure(behind(behindBy3().answer("rev-list", 0, "three\n")));

        assertThat(unreadable).isInstanceOf(MirrorError.Failed.class);
        assertThat(((MirrorError.Failed) unreadable).operation()).isEqualTo("rev-list");
    }

    @Test
    void reachable_fails_whenForEachRefFails_neverReadingFalse() {
        var runner = new Scripted().answer("for-each-ref", 128, "");
        var answer = on(runner, dir).reachable(Set.of(sha(HEAD))).await();

        assertThat(answer.isFailure()).isTrue();
        assertThat(on(new Scripted().answer("for-each-ref", 0, "refs/heads/main\n"), dir)
                            .reachable(Set.of(sha(HEAD)))
                            .await()
                            .unwrap()).isEqualTo(Map.of(sha(HEAD), true));
    }

    /// The presence short-circuit: a missing object is unreachable without a `for-each-ref`.
    @Test
    void reachable_missingObject_isFalse_withoutAskingForEachRef() {
        var runner = new Scripted().answer(VERIFY, 1, "");

        assertThat(on(runner, dir).reachable(Set.of(sha(HEAD))).await().unwrap()).isEqualTo(Map.of(sha(HEAD), false));
        assertThat(runner.subcommands()).containsExactly(SHALLOW, VERIFY);
    }

    @Test
    void ancestry_fails_whenAPullHeadLineIsUnreadable() {
        var runner = new Scripted().answer("for-each-ref", 0, "refs/pull/x/head\n");

        assertThat(on(runner, dir).ancestry(sha(HEAD), List.of(branch("main"))).await().isFailure())
            .isTrue();
        assertThat(on(new Scripted().answer("for-each-ref", 0, "refs/pull/12/head\n"), dir)
                            .ancestry(sha(HEAD), List.of(branch("main")))
                            .await()
                            .unwrap()
                            .headsContainingHead()).hasSize(1);
    }

    @Test
    void ancestry_fails_whenOffBaseOutputIsUnreadable() {
        var runner = new Scripted().answer("rev-list", 0, "not a sha\n");
        var unreadable = failure(on(runner, dir).ancestry(sha(HEAD), List.of(branch("main"))).await());

        assertThat(unreadable).isInstanceOf(MirrorError.Failed.class);
        assertThat(((MirrorError.Failed) unreadable).operation()).isEqualTo("rev-list");
    }

    @Test
    void ancestry_isNotInMirror_whenTheHeadIsAbsent() {
        var runner = new Scripted().answer(VERIFY, 1, "");

        assertThat(on(runner, dir).ancestry(sha(HEAD), List.of(branch("main"))).await().unwrap())
            .isEqualTo(new Ancestry(false, false, Set.of(), Set.of()));
    }

    /// argv only, with `GIT_TERMINAL_PROMPT=0`, and every query names the mirror; the base is named in full.
    @Test
    void everyCall_isArgvWithTheMirrorAndNoPrompt() {
        var runner = behindBy3();

        behind(runner).unwrap();

        assertThat(runner.commands).hasSize(5)
                                   .allSatisfy(command -> assertThat(command.argv().subList(0, 3)).containsExactly("git",
                                                                                                                  "--git-dir",
                                                                                                                  dir.toString()))
                                   .allSatisfy(command -> assertThat(command.env()).containsEntry("GIT_TERMINAL_PROMPT", "0"));
        assertThat(runner.commands.get(1).argv()).endsWith("--verify", "--quiet", HEAD + "^{commit}");
        assertThat(runner.commands.get(2).argv()).endsWith(HEAD, "refs/heads/main");
    }

    /// The fetch passes the configured origin after `--` and exactly the three refspecs; the mirror's own configured
    /// refspec (`+refs/*:refs/*` after a `--mirror` clone) is never used. Then it checks the mirror is whole.
    @Test
    void fetch_namesTheOriginAndTheThreeRefspecs() {
        var runner = new Scripted();

        on(runner, dir).fetch().await().unwrap();

        assertThat(runner.commands.getFirst().argv()).containsExactly("git",
                                                                      "--git-dir",
                                                                      dir.toString(),
                                                                      "fetch",
                                                                      "--prune",
                                                                      "--quiet",
                                                                      "--",
                                                                      ORIGIN,
                                                                      "+refs/heads/*:refs/heads/*",
                                                                      "+refs/tags/*:refs/tags/*",
                                                                      "+refs/pull/*/head:refs/pull/*/head");
        assertThat(runner.subcommands()).containsExactly("fetch", SHALLOW);
    }

    @Test
    void fetch_clones_whenTheMirrorIsAbsent() {
        var runner = new Scripted();
        var absent = dir.resolve("mirror.git");

        on(runner, absent).fetch().await().unwrap();

        assertThat(runner.commands.getFirst().argv()).containsExactly("git",
                                                                      "clone",
                                                                      "--mirror",
                                                                      "--quiet",
                                                                      "--",
                                                                      ORIGIN,
                                                                      absent.toString());
    }

    /// A second fetch starts only once the first has resolved. The operation may start on another thread, so the test
    /// waits for the first fetch to reach the runner, then gives the second time to start if nothing held it back.
    @Test
    void fetch_isSerialised() throws InterruptedException {
        var first = Promise.<Completed> promise();
        var runner = new Scripted().answer("fetch", first);
        var mirror = on(runner, dir);

        var one = mirror.fetch();
        var two = mirror.fetch();
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (runner.commands.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(300);

        assertThat(runner.subcommands()).containsExactly("fetch");

        first.succeed(completed(0, ""));
        one.await().unwrap();
        two.await().unwrap();

        assertThat(runner.subcommands()).filteredOn("fetch"::equals).hasSize(2);
    }

    @Test
    void fetch_fails_onANonzeroExit() {
        Result<Unit> answer = on(new Scripted().answer("fetch", 128, ""), dir).fetch().await();

        assertThat(answer.isFailure()).isTrue();
        answer.onFailure(cause -> assertThat(cause).isInstanceOf(MirrorError.Failed.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://github.com/o/r.git", "https://github.com", "https://github.com:443/o/r.git",
                            "https://github.com/o/r@v.git", "file:///srv/origin.git", "/srv/origin.git", "/srv/a@b.git"})
    void gitMirror_acceptsHttpsFileAndAbsoluteOrigins(String origin) {
        assertThat(GitMirror.gitMirror(new Scripted(), origin, dir).isSuccess()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "--upload-pack=touch x", "-c", "git@github.com:o/r.git", "ssh://github.com/o/r",
                            "http://github.com/o/r.git", "origin", "relative/path", "https://github.com/o/r\n.git",
                            "/srv/origin\u0000.git", "file:///a\tb", "file://host/srv/origin.git", "file://relative/x.git",
                            "file://--upload-pack=touch x;git-upload-pack", "file://", "file:relative", "https://",
                            "https:///o/r.git", "https://user@github.com/o/r.git", "https://user:pass@github.com/o/r.git",
                            "https://ghp_token@github.com/o/r.git", "https://:pass@github.com/o/r", "https://u@h"})
    void gitMirror_refusesAnyOtherOrigin(String origin) {
        assertThat(failure(GitMirror.gitMirror(new Scripted(), origin, dir))).isEqualTo(MirrorError.General.INVALID_ORIGIN);
    }

    /// A refused origin's credential never reaches the failure's text.
    @Test
    void gitMirror_neverEchoesARefusedOrigin() {
        var refused = failure(GitMirror.gitMirror(new Scripted(), "https://user:s3cret@github.com/o/r.git", dir));

        assertThat(refused.message()).doesNotContain("s3cret")
                                     .doesNotContain("github.com");
    }

    @Test
    void gitMirror_refusesANullOrigin() {
        assertThat(failure(GitMirror.gitMirror(new Scripted(), null, dir))).isEqualTo(MirrorError.General.INVALID_ORIGIN);
    }
}
