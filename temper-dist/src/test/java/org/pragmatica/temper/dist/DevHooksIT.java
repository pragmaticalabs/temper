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

/// `ops/dev/hooks` (owner decision D-2): the commit rule of docs/developer-guide.md §7 and the jbct format gate,
/// checked at commit time instead of in prose and in a failed build. `pre-commit` runs a fake `mvn` here; the real
/// `jbct:format-check` goal is the one the root pom's plugin provides.
/// Probes: the line count check removed from commit-msg → the trailer and body rows of [#commitMsg_rejects] red;
/// `style` added to the prefix list → its row red; the `'*.java'` filter dropped from pre-commit →
/// [#preCommit_withoutJava_neverRunsMaven] red; the "not properly formatted" match changed →
/// [#preCommit_unformatted_isRejected_withTheFix] red; the scope regex back to `\\(.+\\)` → the `fix(a)(b):` row of
/// [#commitMsg_rejects] red; pre-commit back to the nearest pom.xml → [#preCommit_aFixtureProjectInsideAModule_checksTheModule]
/// red; the pathspecs unquoted → [#preCommit_aFileWithASpace_isNamed] red; the pre-push one-line check removed → the
/// body rows of [#prePush_rejectsStoredMessagesThatBreakTheRule] red; `--not --remotes` dropped →
/// [#prePush_judgesOnlyCommitsNoRemoteHolds] and [#prePush_acceptsAConventionalMergeCommit_refusesTheDefaultMessage]
/// red; merge commits refused outright → the latter red; the carriage-return check removed → the `\r` row of
/// [#prePush_rejectsStoredMessagesThatBreakTheRule] red; the local tracking refs used instead of ls-remote →
/// [#prePush_staleTrackingRefs_doNotHideCommits] red; the fallback warning dropped →
/// [#prePush_withoutLsRemote_fallsBackToTrackingRefs_warning] red; the cannot-run-mvn branch removed →
/// [#preCommit_withoutJava_saysMavenCannotRun] red; its exit-127 clause removed → [#preCommit_withoutMaven_saysMavenCannotRun]
/// red; the launcher check over the whole log, not its first line → [#preCommit_mavenMentioningJavaHomeLater_isAnOrdinaryFailure]
/// red; the java_home lookup removed, its Darwin test or its unset-JAVA_HOME test dropped → the Darwin, Linux or `/mine`
/// row of [#preCommit_unsetJavaHome_onMacOs_comesFromJavaHome] red.
class DevHooksIT {
    @TempDir
    Path dir;
    private DevSandbox sandbox;
    private Path mvnArgs;

    @BeforeEach
    void sandbox() throws Exception {
        sandbox = new DevSandbox(dir);
        mvnArgs = dir.resolve("mvn-args");
        sandbox.env.put("JAVA_HOME", "/jdk/set");  // "" is unset: the hook reads ${JAVA_HOME:-}, the map cannot unset
        sandbox.env.put("TEMPER_JAVA_HOME_CMD", dir.resolve("no-java_home").toString());  // never the machine's
        sandbox.fake("mvn", "echo \"$*\" > '" + mvnArgs + "'\n"
                            + "case \"$FAKE_MVN\" in\n"
                            + "  unformatted) echo '[ERROR] Failed to execute goal ...format-check: 1 file(s) are not properly formatted:'\n"
                            + "               echo '[ERROR]   /x/mod-a/src/main/java/A.java'; exit 1 ;;\n"
                            + "  broken) echo '[ERROR] Could not resolve dependencies'; exit 1 ;;\n"
                            + "  java-later) echo '[INFO] Scanning for projects...'\n"
                            + "              echo 'The JAVA_HOME environment variable is not defined correctly, in a forked JVM'; exit 1 ;;\n"
                            + "esac\n"
                            + "echo \"JAVA_HOME=$JAVA_HOME\" > '" + mvnArgs + ".java'\n"
                            + "case \"$JAVA_HOME\" in\n"
                            + "  '') echo; echo 'The JAVA_HOME environment variable is not defined correctly,'\n"
                            + "      echo 'this environment variable is needed to run this program.'; exit 1 ;;\n"
                            + "esac");
        for (var module : List.of("mod-a", "mod-b")) {
            Files.writeString(Files.createDirectories(sandbox.main.resolve(module + "/src/main/java")).resolve("Keep.java"),
                              "class Keep {}\n");
            Files.writeString(sandbox.main.resolve(module + "/pom.xml"), "<project/>\n");
        }
        Files.writeString(sandbox.main.resolve("pom.xml"), """
            <project>
                <modules>
                    <module>mod-a</module>
                    <module>mod-b</module>
                </modules>
                <profiles><profile><id>acceptance</id><modules><module>mod-p</module></modules></profile></profiles>
            </project>
            """);
        sandbox.git(sandbox.main, "add", ".");
        sandbox.git(sandbox.main, "commit", "-q", "-m", "chore: modules");
    }

    @ParameterizedTest
    @ValueSource(strings = {"feat: add wt-new", "fix(store): refuse a stale lease", "refactor: x", "chore: merge main",
                            "docs: y", "test: pin z", "feat: trailing blanks\n\n\n",
                            "# Please enter the commit message\nfix: under a comment\n# another\n",
                            "\nfix: after a blank line git strips",
                            "fix: verbose\n# ------------------------ >8 ------------------------\ndiff --git a/x b/x\n+added\n"})
    void commitMsg_accepts(String message) throws Exception {
        var outcome = commitMsg(message);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"style: x", "feat:x", "Feat: x", "feat x", "feature: x", "feat(): x", "",
                            "# only a comment\n", "feat: x\n\nCo-Authored-By: Claude <noreply@anthropic.com>",
                            "feat: x\nSigned-off-by: t <t@t>", "feat: x\n\nA body explaining it.",
                            "Merge branch 'x' into main", "fix(a)(b): x", "fix(a: x"})
    void commitMsg_rejects(String message) throws Exception {
        var outcome = commitMsg(message);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("commit-msg: rejected").contains("exactly one line");
    }

    @Test
    void commitMsg_namesTheTrailer() throws Exception {
        assertThat(commitMsg("fix: a\n\nCo-Authored-By: X <x@x>").output()).contains("3 lines").contains("'Co-Authored-By: X <x@x>'");
    }

    @Test
    void preCommit_withoutJava_neverRunsMaven() throws Exception {
        Files.writeString(sandbox.main.resolve("README"), "changed\n");
        Files.writeString(sandbox.main.resolve("mod-a/notes.txt"), "in a module, not Java\n");
        sandbox.git(sandbox.main, "add", "README", "mod-a/notes.txt");
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).isEmpty();
        assertThat(mvnArgs).doesNotExist();
    }

    @Test
    void preCommit_checksOnlyTheModulesHoldingStagedJava() throws Exception {
        stage("mod-b/src/main/java/B.java");
        stage("mod-a/src/main/java/deep/A.java");
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(Files.readString(mvnArgs).strip()).isEqualTo("-B -Pacceptance -pl mod-a,mod-b jbct:format-check");
    }

    @Test
    void preCommit_unformatted_isRejected_withTheFix() throws Exception {
        var staged = stage("mod-a/src/main/java/A.java");
        var blob = sandbox.git(sandbox.main, "ls-files", "-s", "mod-a/src/main/java/A.java");
        sandbox.env.put("FAKE_MVN", "unformatted");
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("unformatted Java").contains("/x/mod-a/src/main/java/A.java")
                                    .contains("run mvn -B jbct:format");
        assertThat(sandbox.git(sandbox.main, "ls-files", "-s", "mod-a/src/main/java/A.java")).as("index untouched").isEqualTo(blob);
        assertThat(staged).hasContent("class A {}\n");
    }

    @Test
    void preCommit_mavenFailingOtherwise_isRejected_withItsOutput() throws Exception {
        stage("mod-a/src/main/java/A.java");
        sandbox.env.put("FAKE_MVN", "broken");
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("did not run cleanly").contains("Could not resolve dependencies");
    }

    /// The launcher's complaint, not its last lines: the reason stated once, so nobody pushes past it.
    @Test
    void preCommit_withoutJava_saysMavenCannotRun() throws Exception {
        stage("mod-a/src/main/java/A.java");
        sandbox.env.put("JAVA_HOME", "");
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("pre-commit: cannot run mvn (JAVA_HOME unset or no java): "
                                              + "The JAVA_HOME environment variable is not defined correctly,")
                                    .doesNotContain("did not run cleanly");
    }

    /// Only the launcher's own first line means mvn could not start; Maven mentioning JAVA_HOME later is a failure.
    @Test
    void preCommit_mavenMentioningJavaHomeLater_isAnOrdinaryFailure() throws Exception {
        stage("mod-a/src/main/java/A.java");
        sandbox.env.put("FAKE_MVN", "java-later");
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("did not run cleanly").doesNotContain("cannot run mvn");
    }

    @Test
    void preCommit_withoutMaven_saysMavenCannotRun() throws Exception {
        stage("mod-a/src/main/java/A.java");
        Files.delete(sandbox.bin.resolve("mvn"));
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("pre-commit: cannot run mvn (JAVA_HOME unset or no java): ");
    }

    /// On macOS an unset JAVA_HOME comes from java_home -v 25; elsewhere, or when JAVA_HOME is set, it is left alone.
    @ParameterizedTest
    @ValueSource(strings = {"Darwin||/jdk/25", "Linux||", "Darwin|/mine|/mine"})
    void preCommit_unsetJavaHome_onMacOs_comesFromJavaHome(String row) throws Exception {
        var field = row.split("\\|", -1);
        stage("mod-a/src/main/java/A.java");
        sandbox.fake("uname", "echo " + field[0]);
        var javaHome = sandbox.fake("java_home", "[ \"$*\" = '-v 25' ] && echo /jdk/25");
        sandbox.env.put("TEMPER_JAVA_HOME_CMD", javaHome.toString());
        if (field[1].isEmpty()) {
            sandbox.env.put("JAVA_HOME", "");
        } else {
            sandbox.env.put("JAVA_HOME", field[1]);
        }
        var outcome = preCommit();

        assertThat(dir.resolve("mvn-args.java")).as(outcome.output()).hasContent("JAVA_HOME=" + field[2]);
        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(field[2].isEmpty() ? 1 : 0);
    }

    @Test
    void preCommit_javaOutsideAnyModule_neverRunsMaven() throws Exception {
        stage("scripts/Tool.java");

        assertThat(preCommit().rc()).isZero();
        assertThat(mvnArgs).doesNotExist();
    }

    /// A fixture project with a pom.xml of its own inside a module is no reactor module: the module holding it is
    /// checked (a `-pl` naming the fixture would fail the commit for nothing).
    @Test
    void preCommit_aFixtureProjectInsideAModule_checksTheModule() throws Exception {
        Files.writeString(Files.createDirectories(sandbox.main.resolve("mod-a/src/test/resources/fixture")).resolve("pom.xml"), "<project/>\n");
        stage("mod-a/src/test/resources/fixture/src/main/java/F.java");
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(Files.readString(mvnArgs).strip()).isEqualTo("-B -Pacceptance -pl mod-a jbct:format-check");
    }

    @Test
    void preCommit_aModuleOfAProfile_isChecked() throws Exception {
        stage("mod-p/src/main/java/P.java");

        assertThat(preCommit().rc()).isZero();
        assertThat(Files.readString(mvnArgs).strip()).isEqualTo("-B -Pacceptance -pl mod-p jbct:format-check");
    }

    @Test
    void preCommit_javaInADirectoryNoPomDeclares_neverRunsMaven() throws Exception {
        Files.writeString(Files.createDirectories(sandbox.main.resolve("tools")).resolve("pom.xml"), "<project/>\n");
        stage("tools/src/main/java/T.java");
        stage("mod-ab/src/main/java/Near.java");  // a prefix of no declared module, though mod-a is one of its

        assertThat(preCommit().rc()).isZero();
        assertThat(mvnArgs).doesNotExist();
    }

    @Test
    void preCommit_aFileWithASpace_isNamed() throws Exception {
        var staged = stage("mod-a/src/main/java/With Space.java");
        Files.writeString(staged, "class WithSpace { int later; }\n");
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("also have unstaged changes").contains("mod-a/src/main/java/With Space.java");
        assertThat(Files.readString(mvnArgs).strip()).isEqualTo("-B -Pacceptance -pl mod-a jbct:format-check");
    }

    /// What commit-msg cannot see: `-m "# ..."` keeps a `#` line, --cleanup=verbatim keeps blank lines, --no-verify
    /// skips the hook. pre-push reads the stored messages and refuses the push.
    @ParameterizedTest
    @ValueSource(strings = {"--no-verify|feat: x\n\nA body.", "-m|# Co-Authored-By: X <x@x>",
                            "--cleanup=verbatim|feat: x\n\n", "--no-verify|fix(a)(b): x", "--no-verify|style: x",
                            "--no-verify|Merge branch 'x'", "--cleanup=verbatim|feat: carriage\r"})
    void prePush_rejectsStoredMessagesThatBreakTheRule(String commit) throws Exception {
        var how = commit.split("\\|", 2);
        sandbox.git(sandbox.main, "config", "core.hooksPath", "ops/dev/hooks");
        sandbox.git(sandbox.main, "commit", "-q", "--allow-empty", "-m", "feat: fine");
        var argv = new java.util.ArrayList<>(List.of("git", "commit", "-q", "--allow-empty"));
        argv.addAll(how[0].equals("-m") ? List.of("-m", "feat: x", "-m", how[1]) : List.of(how[0], "-m", how[1]));
        var made = sandbox.ran(argv, sandbox.main);
        assertThat(made.rc()).as("commit-msg let it through: " + made.output()).isZero();
        var outcome = sandbox.ran(List.of("git", "push", "-q", "origin", "HEAD:refs/heads/topic"), sandbox.main);

        assertThat(outcome.rc()).as(outcome.output()).isNotZero();
        assertThat(outcome.output()).contains("pre-push: rejected").contains(sandbox.git(sandbox.main, "rev-parse", "--short", "HEAD"))
                                    .doesNotContain(sandbox.git(sandbox.main, "rev-parse", "--short", "HEAD~1") + " ");
        assertThat(sandbox.ran(List.of("git", "ls-remote", "--exit-code", "origin", "refs/heads/topic"), sandbox.main).rc()).isNotZero();
    }

    /// A commit that some remote branch already holds was judged (or let through) when it was pushed: not again. A
    /// branch deletion pushes nothing. One-line commits on top go.
    @Test
    void prePush_judgesOnlyCommitsNoRemoteHolds() throws Exception {
        sandbox.git(sandbox.main, "config", "core.hooksPath", "ops/dev/hooks");
        sandbox.git(sandbox.main, "commit", "-q", "--allow-empty", "--no-verify", "-m", "old: from before the hook\n\nbody");
        sandbox.git(sandbox.main, "push", "-q", "--no-verify", "origin", "HEAD:refs/heads/old");
        sandbox.git(sandbox.main, "commit", "-q", "--allow-empty", "-m", "feat: fine");
        sandbox.git(sandbox.main, "commit", "-q", "--allow-empty", "--cleanup=verbatim", "-m", "fix(scope): no newline at all");
        var pushed = sandbox.ran(List.of("git", "push", "-q", "origin", "HEAD:refs/heads/topic"), sandbox.main);

        assertThat(pushed.rc()).as(pushed.output()).isZero();
        assertThat(sandbox.ran(List.of("git", "push", "-q", "origin", ":refs/heads/old"), sandbox.main).rc()).isZero();
    }

    /// "Already on the remote" is asked of the remote: a branch deleted there leaves a stale origin/<b> here, which
    /// must not hide its commits.
    @Test
    void prePush_staleTrackingRefs_doNotHideCommits() throws Exception {
        sandbox.git(sandbox.main, "config", "core.hooksPath", "ops/dev/hooks");
        sandbox.git(sandbox.main, "commit", "-q", "--allow-empty", "--no-verify", "-m", "feat: bad\n\nbody");
        var bad = sandbox.git(sandbox.main, "rev-parse", "--short", "HEAD");
        sandbox.git(sandbox.main, "push", "-q", "--no-verify", "origin", "HEAD:refs/heads/old");
        sandbox.git(sandbox.dir, "--git-dir=" + sandbox.origin, "update-ref", "-d", "refs/heads/old");
        assertThat(sandbox.git(sandbox.main, "rev-parse", "--short", "origin/old")).as("the stale ref").isEqualTo(bad);
        sandbox.git(sandbox.main, "commit", "-q", "--allow-empty", "-m", "feat: good");
        var outcome = sandbox.ran(List.of("git", "push", "-q", "origin", "HEAD:refs/heads/topic"), sandbox.main);

        assertThat(outcome.rc()).as(outcome.output()).isNotZero();
        assertThat(outcome.output()).contains("pre-push: rejected").contains(bad + " has more than one line");
    }

    /// A remote ls-remote cannot reach: the local remote-tracking refs stand in, with a warning, and the rule holds.
    @Test
    void prePush_withoutLsRemote_fallsBackToTrackingRefs_warning() throws Exception {
        sandbox.git(sandbox.main, "commit", "-q", "--allow-empty", "-m", "feat: good");
        var head = sandbox.git(sandbox.main, "rev-parse", "HEAD");
        var hook = sandbox.main.resolve("ops/dev/hooks/pre-push");
        var outcome = sandbox.ran(List.of("sh", "-c", "echo 'refs/heads/x " + head + " refs/heads/x " + "0".repeat(40)
                                                      + "' | bash '" + hook + "' nowhere /nowhere.git"), sandbox.main);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("pre-push: warning: git ls-remote nowhere failed");

        sandbox.git(sandbox.main, "commit", "-q", "--allow-empty", "--no-verify", "-m", "feat: bad\n\nbody");
        var bad = sandbox.git(sandbox.main, "rev-parse", "HEAD");
        var refused = sandbox.ran(List.of("sh", "-c", "echo 'refs/heads/x " + bad + " refs/heads/x " + "0".repeat(40)
                                                      + "' | bash '" + hook + "' nowhere /nowhere.git"), sandbox.main);

        assertThat(refused.rc()).as(refused.output()).isEqualTo(1);
    }

    /// Merging main into a work branch (and land-pr's merges) is a merge commit with a one-line conventional message:
    /// pushed. Main's own history, default `Merge branch` merges included, is on the remote and not judged again;
    /// git's default message on a new merge is refused.
    @Test
    void prePush_acceptsAConventionalMergeCommit_refusesTheDefaultMessage() throws Exception {
        var main = sandbox.main;
        sandbox.git(main, "config", "core.hooksPath", "ops/dev/hooks");
        sandbox.git(main, "checkout", "-q", "-b", "legacy");
        sandbox.git(main, "commit", "-q", "--allow-empty", "-m", "feat: legacy");
        sandbox.git(main, "checkout", "-q", "main");
        sandbox.git(main, "merge", "-q", "--no-ff", "--no-verify", "--no-edit", "legacy");  // "Merge branch 'legacy'"
        sandbox.git(main, "push", "-q", "--no-verify", "origin", "HEAD:main");
        sandbox.git(main, "checkout", "-q", "-b", "topic", "HEAD~1");
        sandbox.git(main, "commit", "-q", "--allow-empty", "-m", "feat: work");
        sandbox.git(main, "merge", "-q", "--no-ff", "-m", "chore: merge main into topic", "origin/main");
        assertThat(sandbox.git(main, "rev-list", "--parents", "-n", "1", "HEAD").split(" ")).as("a merge commit").hasSize(3);
        var pushed = sandbox.ran(List.of("git", "push", "-q", "origin", "HEAD:refs/heads/topic"), main);

        assertThat(pushed.rc()).as(pushed.output()).isZero();

        sandbox.git(main, "commit", "-q", "--allow-empty", "-m", "feat: more");
        sandbox.git(main, "checkout", "-q", "-b", "side", "HEAD~1");
        sandbox.git(main, "commit", "-q", "--allow-empty", "-m", "feat: side");
        sandbox.git(main, "merge", "-q", "--no-ff", "--no-verify", "--no-edit", "topic");
        var refused = sandbox.ran(List.of("git", "push", "-q", "origin", "HEAD:refs/heads/side"), main);

        assertThat(refused.rc()).as(refused.output()).isNotZero();
        assertThat(refused.output()).contains("pre-push: rejected").contains("Merge branch 'topic'");
    }

    @Test
    void preCommit_namesAStagedFileWithUnstagedChanges() throws Exception {
        var staged = stage("mod-a/src/main/java/A.java");
        Files.writeString(staged, "class A { int later; }\n");
        var outcome = preCommit();

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("also have unstaged changes").contains("mod-a/src/main/java/A.java");
    }

    /// The hooks wired as wt-new wires them: git itself runs them.
    @Test
    void gitRunsTheHooks_throughCoreHooksPath() throws Exception {
        sandbox.git(sandbox.main, "config", "core.hooksPath", "ops/dev/hooks");
        stage("mod-a/src/main/java/A.java");
        sandbox.env.put("FAKE_MVN", "unformatted");

        assertThat(sandbox.ran(List.of("git", "commit", "-q", "-m", "feat: a"), sandbox.main).output()).contains("unformatted Java");
        sandbox.env.remove("FAKE_MVN");
        assertThat(sandbox.ran(List.of("git", "commit", "-q", "-m", "feat: a\n\nCo-Authored-By: X <x@x>"), sandbox.main).rc()).isEqualTo(1);
        assertThat(sandbox.ran(List.of("git", "commit", "-q", "-m", "feat: a"), sandbox.main).rc()).isZero();
    }

    private Path stage(String file) throws Exception {
        var path = sandbox.main.resolve(file);
        Files.writeString(Files.createDirectories(path.getParent()).resolve(path.getFileName()),
                          "class " + path.getFileName().toString().replace(".java", "") + " {}\n");
        sandbox.git(sandbox.main, "add", file);
        return path;
    }

    private DevSandbox.Outcome commitMsg(String message) throws Exception {
        var file = Files.writeString(dir.resolve("COMMIT_EDITMSG"), message);
        return sandbox.ran(List.of("bash", sandbox.main.resolve("ops/dev/hooks/commit-msg").toString(), file.toString()), sandbox.main);
    }

    private DevSandbox.Outcome preCommit() throws Exception {
        return sandbox.ran(List.of("bash", sandbox.main.resolve("ops/dev/hooks/pre-commit").toString()), sandbox.main);
    }
}
