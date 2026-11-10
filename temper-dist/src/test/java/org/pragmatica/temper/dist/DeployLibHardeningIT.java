package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.dist.DeploySandbox.Outcome;

import static org.assertj.core.api.Assertions.assertThat;

/// Hardening of `ops/deploy/lib.sh` found by the WP-1a verification: signals and a dropped session (contract
/// D3.7a), the `stage-local` root guard, the staged-copy boundary, the quoted lock message and the schema read after
/// the stop (D4). DL_SETTLE is widened so that a signal sent once the settle line shows lands inside the window.
class DeployLibHardeningIT {
    private static final String COMMIT = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
    private static final String OLD_CONTENT = "toy build zero\n";
    private static final String NEW_CONTENT = "toy build one\n";
    private static final Pattern RECORD = Pattern.compile(
        "^[0-9T:Z-]+ event=install unit=toy commit=[0-9a-f]{40} sha256=[0-9a-f]{64} outcome=(installed|partial) rollback=[0-9a-f]{64} schema=\\S+ dir=\\S+$");

    @TempDir
    Path dir;
    private DeploySandbox sb;
    private String oldSha;
    private String newSha;

    @BeforeEach
    void rig() throws Exception {
        sb = new DeploySandbox(dir);
        oldSha = sb.live(OLD_CONTENT);
        newSha = sb.stage(COMMIT, NEW_CONTENT);
        sb.env.put("DL_SETTLE", "6");
    }

    @AfterEach
    void close() {
        sb.close();
    }

    private Path script() {
        return sb.stageDir(COMMIT).resolve("deploy-toy.sh");
    }

    // ------------------------------------------------------------------------------------------ the launcher

    /// An install started with SIGINT, SIGHUP and SIGPIPE at their defaults (a JVM under a nohup'd build inherits them ignored,
    /// which would hide the HUP mutation).
    /// `tail` is a shell fragment appended to the pipeline: empty = output to files.
    private Process launch(String plan, String pipeline) throws Exception {
        return launch(plan, pipeline, "");
    }

    /// `own` = `setpgrp(0,0);`: the install leads its own process group, so a group signal reaches its pumps too.
    private Process launch(String plan, String pipeline, String own) throws Exception {
        return launch(plan, pipeline, own, oldSha);
    }

    private Process launch(String plan, String pipeline, String own, String current) throws Exception {
        var args = "install --commit " + COMMIT + " --expect-current " + current + " --expect-new " + newSha + " --expect-plan " + plan;
        var shell = "perl -e '" + own + "$SIG{INT}=$SIG{HUP}=$SIG{PIPE}=\"DEFAULT\"; exec @ARGV' bash " + script() + " " + args + " " + pipeline;
        var builder = HermeticProcess.builder("bash", "-c", shell).directory(sb.root.toFile());
        return sb.prepared(builder).start();
    }

    private String plan() throws Exception {
        var dry = sb.dryRun(COMMIT, oldSha, newSha);
        assertThat(dry.rc()).as(dry.err()).isZero();
        return DeploySandbox.planSha(dry);
    }

    private Path rollbackDir() throws IOException {
        var root = sb.home.resolve(".deploy/rollback");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.findFirst().orElse(null);
        }
    }

    /// Block until the install has printed its settle line to B/install.log; returns the install's pid (the lock's).
    private String awaitSettle() throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            var b = rollbackDir();
            var log = b == null ? null : b.resolve("install.log");
            var pid = sb.home.resolve(".deploy/lock/pid");
            if (log != null && Files.exists(log) && Files.readString(log).contains(" settle ") && Files.exists(pid)) {
                return Files.readString(pid).strip();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no settle line in B/install.log");
    }

    private void signal(String name, String pid) throws Exception {
        var kill = HermeticProcess.builder("kill", "-" + name, pid).start();
        assertThat(kill.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(kill.exitValue()).isZero();
    }

    private Process launchToFiles(Path out, Path err) throws Exception {
        return launch(plan(), "> " + out + " 2> " + err);
    }

    // ------------------------------------------------------------------------------- D3.7a: a dropped session

    /// Mutations: drop the HUP ignore; drop the PIPE ignore (a stdout write aborts); drop the install.log tee.
    @Test
    void sigHupAndClosedStdout_duringSettle_theInstallCompletesAsAnAttachedRun() throws Exception {
        var rcFile = sb.root.resolve("rc");
        var process = launch(plan(), "2>&1 | head -n 1 > /dev/null; echo ${PIPESTATUS[0]} > " + rcFile);
        var pid = awaitSettle();
        signal("HUP", pid);
        assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue();
        assertThat(rcFile).as("the install ended and wrote its exit code").exists();
        assertThat(Files.readString(rcFile).strip()).as("the exit code of an attached, successful run").isEqualTo("0");
        assertThat(sb.liveFile()).hasContent(NEW_CONTENT.strip());
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).matches(RECORD).contains("outcome=installed").contains("sha256=" + newSha);
        var log = Files.readString(rollbackDir().resolve("install.log"));
        assertThat(log).contains("do-header").contains(" settle ").contains("done ");
        assertThat(sb.home.resolve(".deploy/lock")).doesNotExist();
    }

    // ----------------------------------------------------------------------------------- D3.7a: a deliberate stop

    /// Mutations: drop the TERM trap; skip the partial record.
    @Test
    void sigTerm_duringSettle_isPartial_exit8_lockReleased_noFurtherAction() throws Exception {
        assertDeliberateStop("TERM");
    }

    /// Mutations: as for SIGTERM, on the INT trap.
    @Test
    void sigInt_duringSettle_isPartial_exit8_lockReleased_noFurtherAction() throws Exception {
        assertDeliberateStop("INT");
    }

    private void assertDeliberateStop(String name) throws Exception {
        var out = sb.root.resolve("stdout");
        var err = sb.root.resolve("stderr");
        var process = launchToFiles(out, err);
        var pid = awaitSettle();
        var shimBefore = sb.shimLog();
        signal(name, pid);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(err)).isEqualTo(8);
        assertThat(Files.readString(err)).contains("PARTIAL: stopped by SIG" + name + " during ");
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).matches(RECORD).contains("event=install").contains("outcome=partial").contains("schema=V4");
        assertThat(Files.readString(rollbackDir().resolve("install.log"))).contains("PARTIAL: stopped by SIG" + name);
        assertThat(sb.home.resolve(".deploy/lock")).doesNotExist();
        assertThat(sb.shimLog()).as("no restart, stop, start or probe after the signal").isEqualTo(shimBefore);
        assertThat(sb.liveFile()).as("no rollback: the operator stopped the script on purpose").hasContent(NEW_CONTENT.strip());
    }

    // ------------------------------------------------------------------- F-A: a closed stdout or stderr at start

    private void assertCompletedAsInstalled(Process process, Path err) throws Exception {
        assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.exists(err) ? Files.readString(err) : "").isZero();
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).matches(RECORD).contains("outcome=installed").contains("sha256=" + newSha);
        assertThat(sb.liveFile()).hasContent(NEW_CONTENT.strip());
        assertThat(sb.home.resolve(".deploy/lock")).as("the lock is released").doesNotExist();
    }

    /// F-A (bash 3.2 on the Mac let `{ true >&1; }` pass on a closed fd and aborted dl_cleanup; here the closed stream must
    /// never change rc, record or lock). Mutations: bare `exec 1>&5 2>&6` in dl_cleanup with the saved streams gone (the next test).
    @Test
    void closedStdoutAtStart_theInstallCompletes_rc0_recordWritten_noLock() throws Exception {
        sb.env.put("DL_SETTLE", "1");
        var err = sb.root.resolve("stderr");
        assertCompletedAsInstalled(launch(plan(), ">&- 2> " + err), err);
    }

    @Test
    void closedStderrAtStart_theInstallCompletes_rc0_recordWritten_noLock() throws Exception {
        sb.env.put("DL_SETTLE", "1");
        var out = sb.root.resolve("stdout");
        assertCompletedAsInstalled(launch(plan(), "2>&- > " + out), out);
        assertThat(Files.readString(out)).contains("do-header");
    }

    /// F-A: the saved streams (fds 5 and 6) gone at cleanup: the cleanup falls back to /dev/null, exits 0 and frees the lock.
    /// Mutation: dl_cleanup's `if [ -e /dev/fd/5 ]` guards replaced by the bare `exec 1>&5 2>&6`.
    @Test
    void savedStreamsGoneAtCleanup_stillExit0_andReleaseTheLock() throws Exception {
        sb.env.put("DL_SETTLE", "1");
        sb.env.put("DL_TOY_DROP_FD", "1");
        var out = sb.root.resolve("stdout");
        var err = sb.root.resolve("stderr");
        assertCompletedAsInstalled(launch(plan(), "> " + out + " 2> " + err), err);
    }

    // ---------------------------------------------------- F-E and the signal handlers (n7, n12, n15)

    /// F-E: a TERM after the health-failed record (here: inside the rollback's health check) writes no second install record.
    /// Mutation: dl_on_signal ignores DL_RECORDED and records `partial` anyway.
    @Test
    void sigTerm_afterTheHealthFailedRecord_writesNoSecondRecord() throws Exception {
        sb.env.put("DL_SETTLE", "1");
        var hold = sb.root.resolve("hold");
        sb.env.put("DL_TOY_HOLD", hold.toString());
        sb.behave(newSha, "500");
        var out = sb.root.resolve("stdout");
        var err = sb.root.resolve("stderr");
        var process = launchToFiles(out, err);
        var pid = awaitFile(hold, sb.home.resolve(".deploy/lock/pid"));
        signal("TERM", pid);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(err)).isEqualTo(8);
        assertThat(sb.deploysLog()).as("exactly the health-failed line").hasSize(1);
        assertThat(sb.deploysLog().get(0)).contains("event=install ").contains("outcome=health-failed");
        assertThat(Files.readString(err)).contains("after the install record was written; no second record");
        assertThat(sb.home.resolve(".deploy/lock")).doesNotExist();
    }

    /// F-E: a failed append must not count as a record. deploys.log is a directory (the append fails even for root); a TERM in the
    /// rollback then writes no "after the install record" claim: it reports RECORD NOT WRITTEN and the PARTIAL stop, exit 8.
    /// Mutation: DL_RECORDED=1 set before the append again (in dl_do_record, ahead of dl_record).
    @Test
    void sigTerm_whenTheRecordAppendFailed_doesNotClaimARecord() throws Exception {
        sb.env.put("DL_SETTLE", "1");
        Files.createDirectories(sb.home.resolve(".deploy/deploys.log"));
        var hold = sb.root.resolve("hold");
        sb.env.put("DL_TOY_HOLD", hold.toString());
        sb.behave(newSha, "500");
        var err = sb.root.resolve("stderr");
        var process = launchToFiles(sb.root.resolve("stdout"), err);
        var pid = awaitFile(hold, sb.home.resolve(".deploy/lock/pid"));
        signal("TERM", pid);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        var text = Files.readString(err);
        assertThat(process.exitValue()).as(text).isEqualTo(8);
        assertThat(text).contains("RECORD NOT WRITTEN").contains("PARTIAL: stopped by SIGTERM").doesNotContain("record was written");
        assertThat(sb.home.resolve(".deploy/lock")).doesNotExist();
    }

    /// Exit code after a record (ruling): the guard firing after an `installed` record exits 0 (the install completed); the
    /// record is the one line and the message still says so. Mutation: the guard exits 8 for every outcome.
    @Test
    void sigTerm_afterTheInstalledRecord_exit0_oneRecord() throws Exception {
        sb.env.put("DL_SETTLE", "1");
        var hold = sb.root.resolve("rechold");
        sb.env.put("DL_TOY_REC_HOLD", hold.toString());
        var err = sb.root.resolve("stderr");
        var process = launchToFiles(sb.root.resolve("stdout"), err);
        var pid = awaitFile(hold, sb.home.resolve(".deploy/lock/pid"));
        signal("TERM", pid);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(err)).isZero();
        assertThat(Files.readString(err)).contains("after the install record was written; no second record");
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).contains("outcome=installed");
        assertThat(sb.home.resolve(".deploy/lock")).doesNotExist();
    }

    /// As above for `unchanged` (live already equals the staged set). Mutation: `unchanged` dropped from the guard's exit-0 case.
    @Test
    void sigTerm_afterTheUnchangedRecord_exit0_oneRecord() throws Exception {
        sb.live(NEW_CONTENT);
        sb.env.put("DL_SETTLE", "1");
        var hold = sb.root.resolve("rechold");
        sb.env.put("DL_TOY_REC_HOLD", hold.toString());
        var err = sb.root.resolve("stderr");
        var dry = sb.dryRun(COMMIT, newSha, newSha);
        assertThat(dry.rc()).as(dry.err()).isZero();
        var process = launch(DeploySandbox.planSha(dry), "> " + sb.root.resolve("stdout") + " 2> " + err, "", newSha);
        var pid = awaitFile(hold, sb.home.resolve(".deploy/lock/pid"));
        signal("TERM", pid);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(err)).isZero();
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).contains("outcome=unchanged");
        assertThat(sb.home.resolve(".deploy/lock")).doesNotExist();
    }

    private String awaitFile(Path marker, Path pidFile) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (Files.exists(marker) && Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
                return Files.readString(pidFile).strip();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no " + marker);
    }

    private boolean alive(String pid) throws Exception {
        var ps = HermeticProcess.builder("ps", "-o", "stat=", "-p", pid).redirectErrorStream(true).start();
        var state = new String(ps.getInputStream().readAllBytes()).strip();
        ps.waitFor(10, TimeUnit.SECONDS);
        return !state.isEmpty() && !state.startsWith("Z");
    }

    /// n7: the deliberate stop kills the settle sleep (DL_BG): no orphan `sleep 6` outlives the script. A `sleep` shim records
    /// the pid of the settle sleep (argument 6). Mutation: remove the `kill "$DL_BG"` line of dl_on_signal.
    @Test
    void sigTerm_duringSettle_leavesNoOrphanSettleSleep() throws Exception {
        var shim = Files.writeString(sb.bin.resolve("sleep"), "#!/bin/sh\n[ \"$1\" = 6 ] && echo $$ > \"$HOME/settle.pid\"\nexec /bin/sleep \"$@\"\n");
        Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwxr-xr-x"));
        var process = launchToFiles(sb.root.resolve("stdout"), sb.root.resolve("stderr"));
        var pid = awaitSettle();
        var sleepPid = awaitFile(sb.home.resolve("settle.pid"), sb.home.resolve("settle.pid"));
        assertThat(alive(sleepPid)).as("the settle sleep runs before the signal").isTrue();
        signal("TERM", pid);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (alive(sleepPid) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(alive(sleepPid)).as("the settle sleep (pid " + sleepPid + ") was killed with the install").isFalse();
    }

    /// n12: once the install succeeded the stop traps are cleared, so a TERM during the unit's manual step is not a "partial
    /// stop" of an install that is done: no PARTIAL message, not exit 8, the one `installed` record, the lock released.
    /// Mutation: remove `trap - INT TERM` after the successful phases.
    @Test
    void sigTerm_afterASuccessfulInstall_duringTheManualStep_isNotAPartialStop() throws Exception {
        sb.env.put("DL_SETTLE", "1");
        var marker = sb.root.resolve("manual");
        sb.env.put("DL_TOY_MANUAL", marker.toString());
        var out = sb.root.resolve("stdout");
        var err = sb.root.resolve("stderr");
        var process = launchToFiles(out, err);
        var pid = awaitFile(marker, sb.home.resolve(".deploy/lock/pid"));
        signal("TERM", pid);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(err)).isNotEqualTo(8).isNotZero();
        assertThat(Files.readString(err) + Files.readString(out)).doesNotContain("stopped by SIG");
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).matches(RECORD).contains("outcome=installed");
        assertThat(sb.home.resolve(".deploy/lock")).doesNotExist();
    }

    /// n15: a TERM to the whole process group (what a terminal's Ctrl-C or `kill -- -pgid` sends) reaches the output pumps too;
    /// they ignore it, so the PARTIAL message still reaches stderr and B/install.log. Mutation: remove the pump's `trap '' INT TERM`.
    @Test
    void sigTerm_toTheWholeProcessGroup_stillDeliversThePartialMessage() throws Exception {
        var out = sb.root.resolve("stdout");
        var err = sb.root.resolve("stderr");
        var process = launch(plan(), "> " + out + " 2> " + err, "setpgrp(0,0);");
        var pid = awaitSettle();
        var kill = HermeticProcess.builder("bash", "-c", "kill -TERM -- -" + pid).start();
        assertThat(kill.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(kill.exitValue()).isZero();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(8);
        assertThat(Files.readString(err)).contains("PARTIAL: stopped by SIGTERM during ");
        assertThat(Files.readString(rollbackDir().resolve("install.log"))).contains("PARTIAL: stopped by SIGTERM");
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).matches(RECORD).contains("outcome=partial");
        assertThat(sb.home.resolve(".deploy/lock")).doesNotExist();
    }

    // ---------------------------------------------------------------------------------- stage-local, the guards

    /// Mutation: the root guard `"$want"?*` back to `"$want"*`.
    @Test
    void stageLocal_intoTheWholeStageRoot_refuses_andWipesNothing() throws Exception {
        var root = sb.home.resolve(".deploy/stage/toy");
        var before = sb.snapshot();
        for (var into : List.of(root.toString(), root + "/")) {
            var out = sb.run(script(), sb.root, "stage-local", "--commit", COMMIT, "--src", sb.root.resolve("src-" + COMMIT).toString(), "--into", into);
            assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
            assertThat(out.err()).contains("is not under");
        }
        assertThat(sb.snapshot()).isEqualTo(before);
    }

    /// Mutation: the staged-copy comparison back to a prefix match.
    @Test
    void install_fromASiblingWhoseNameStartsWithTheStageDir_refuses() throws Exception {
        var evil = sb.home.resolve(".deploy/stage/toy").resolve(COMMIT + "-evil");
        try (Stream<Path> files = Files.walk(sb.stageDir(COMMIT))) {
            for (var file : files.toList()) {
                var target = evil.resolve(sb.stageDir(COMMIT).relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                    Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(file));
                }
            }
        }
        var out = sb.run(evil.resolve("deploy-toy.sh"), sb.root, "install", "--commit", COMMIT, "--expect-current", oldSha, "--expect-new", newSha, "--dry-run");
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
        assertThat(out.err()).contains("install runs the staged copy");
    }

    // ------------------------------------------------------------------------------------------ the lock message

    /// Mutations (each on its own): unquote the `?` in the dry-run refusal; unquote it in the install refusal.
    @Test
    void lockRefusal_withoutAPidFile_printsAQuestionMark_notTheFilesInTheCwd() throws Exception {
        var plan = plan();
        Files.createDirectories(sb.home.resolve(".deploy/lock"));
        Files.writeString(sb.root.resolve("a"), "");
        Files.writeString(sb.root.resolve("b"), "");
        var dry = sb.dryRun(COMMIT, oldSha, newSha);
        assertThat(dry.rc()).as(dry.err()).isEqualTo(3);
        assertThat(dry.err()).contains("holds ~/.deploy/lock (pid ?)");
        var wet = sb.installWithPlan(COMMIT, oldSha, newSha, plan);
        assertThat(wet.rc()).as(wet.err()).isEqualTo(3);
        assertThat(wet.err()).contains("holds ~/.deploy/lock (pid ?)");
    }

    // ------------------------------------------------------------------------------------ SCHEMA_BEFORE after the stop

    /// Mutation: read SCHEMA_BEFORE before the stop again (D4).
    @Test
    void schemaBefore_isReadAfterTheStop_andBeforeTheRestart() throws Exception {
        var out = sb.install(COMMIT, oldSha, newSha);
        assertThat(out.rc()).as(out.err()).isZero();
        var lines = new ArrayList<>(sb.shimLog().lines().toList());
        var stop = lines.indexOf("systemctl --user stop toy");
        var restart = lines.indexOf("systemctl --user restart toy");
        assertThat(stop).isPositive();
        assertThat(restart).isGreaterThan(stop);
        assertThat(lines.subList(stop, restart)).as("a schema read between the stop and the restart").anyMatch(l -> l.contains("psql"));
        try (Stream<Path> dirs = Files.list(sb.home.resolve(".deploy/rollback"))) {
            assertThat(dirs.findFirst().orElseThrow().resolve("SCHEMA_BEFORE")).hasContent("4");
        }
    }
}
