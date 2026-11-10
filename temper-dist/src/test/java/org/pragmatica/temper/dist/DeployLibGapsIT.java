package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.dist.DeploySandbox.Outcome;

import static org.assertj.core.api.Assertions.assertThat;

/// Gaps that single mutations of `ops/deploy/lib.sh` survived (WP-1a verifier, F2/F3): each test names its gap and the
/// mutation that must turn it red. The seams are shims written into the sandbox's `bin` in front of the real tools:
/// a `mv` that logs its destinations and can corrupt the Nth result, a `sleep` that marks that time has passed, and a
/// `systemctl` in front of the sandbox's own shim that can freeze, fail the timer, crash late and record modes.
class DeployLibGapsIT {
    private static final String COMMIT = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
    private static final String OLD_CONTENT = "toy build zero\n";
    private static final String NEW_CONTENT = "toy build one\n";
    private static final String DRWX = "drwx------";

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
    }

    @AfterEach
    void close() {
        sb.close();
    }

    // ------------------------------------------------------------------------------------------------ seams

    private void exe(Path file, String text) throws Exception {
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private void marker(String name, String content) throws Exception {
        Files.writeString(sb.home.resolve("shim").resolve(name), content);
    }

    /// `mv` logs its destination to `shim/mv.log`; `shim/mv-corrupt` holding N appends junk to the result of the Nth `mv`.
    private void mvShim() throws Exception {
        exe(sb.bin.resolve("mv"), """
            #!/bin/sh
            for a; do last=$a; done
            echo "$last" >> "$HOME/shim/mv.log"
            n=$(wc -l < "$HOME/shim/mv.log" | tr -d ' ')
            /usr/bin/env PATH=/usr/bin:/bin mv "$@" || exit $?
            if [ -f "$HOME/shim/mv-corrupt" ] && [ "$n" = "$(cat "$HOME/shim/mv-corrupt")" ]; then echo corrupted >> "$last"; fi
            """);
    }

    /// `sleep` leaves `shim/slept` when `shim/late.crash` is armed: time has passed since the restart.
    private void sleepShim() throws Exception {
        exe(sb.bin.resolve("sleep"), """
            #!/bin/sh
            [ -f "$HOME/shim/late.crash" ] && : > "$HOME/shim/slept"
            exec /usr/bin/env PATH=/usr/bin:/bin sleep "$@"
            """);
    }

    /// A `systemctl` in front of the sandbox's: records the modes of ~/.deploy and the rollback dir at `stop` (shim/modes);
    /// `frozen` ignores restart and stop; `fail-timer` fails `start toy-timer.timer`; `late.crash` makes the first `show`
    /// after a `sleep` that followed a restart see a new MainPID.
    private void systemctlShim() throws Exception {
        Files.move(sb.bin.resolve("systemctl"), sb.home.resolve("shim/systemctl.real"), StandardCopyOption.REPLACE_EXISTING);
        exe(sb.bin.resolve("systemctl"), """
            #!/bin/sh
            S="$HOME/shim"
            all=" $* "
            case "$all" in *" restart "*) rm -f "$S/slept" ;; esac
            case "$all" in
              *" stop "*)
                { echo "deploy $(ls -ld "$HOME/.deploy" | cut -c1-10)"
                  for d in "$HOME"/.deploy/rollback/toy-*; do echo "rollback $(ls -ld "$d" | cut -c1-10)"; done; } >> "$S/modes" 2> /dev/null ;;
            esac
            if [ -f "$S/frozen" ]; then
              case "$all" in *" restart "*|*" stop "*) echo "systemctl $*" >> "$HOME/shim.log"; exit 0 ;; esac
            fi
            if [ -f "$S/fail-timer" ]; then
              case "$all" in *" start toy-timer.timer "*) echo "systemctl $*" >> "$HOME/shim.log"; exit 1 ;; esac
            fi
            if [ -f "$S/late.crash" ] && [ -f "$S/slept" ]; then
              case "$all" in *" show "*) rm -f "$S/late.crash"; echo 1 > "$S/toy.crash" ;; esac
            fi
            exec "$S/systemctl.real" "$@"
            """);
    }

    private List<String> mvLog() throws Exception {
        return Files.readAllLines(sb.home.resolve("shim/mv.log"));
    }

    private Outcome install() throws Exception {
        return sb.install(COMMIT, oldSha, newSha);
    }

    private String liveSha() throws Exception {
        return DeploySandbox.sha256(Files.readAllBytes(sb.liveFile()));
    }

    // ------------------------------------------------------------------------------------------- c: verify

    /// c, swap: a file corrupted between `rename` and `verify` is a failed swap (D3.7 step 10: the target's sha256 must equal
    /// the MANIFEST's, else `partial`, exit 8) and the service is never restarted. Mutation: the plan's `verify` made a no-op.
    @Test
    void c_aSwapCorruptedBetweenRenameAndVerify_isPartial_andNothingIsRestarted() throws Exception {
        mvShim();
        marker("mv-corrupt", "1\n");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(8);
        assertThat(out.out()).containsPattern("FAILED 0[0-9]{2} verify " + Pattern.quote(sb.liveFile().toString()));
        assertThat(out.err()).contains("partial:");
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).contains("event=install ").contains("outcome=partial");
        assertThat(sb.shimLog()).doesNotContain("systemctl --user restart");
    }

    /// c, rollback: a restored file corrupted before its `verify` fails the rollback at the verify (D3.7 step 13: each
    /// restored target's sha256 must equal MANIFEST.old's), exit 6, not at some later probe. Mutation: `verify` a no-op.
    @Test
    void c_aRestoreCorruptedBeforeItsVerify_exits6_failedAtVerify() throws Exception {
        mvShim();
        marker("mv-corrupt", "2\n");
        sb.behave(newSha, "500");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(6);
        assertThat(out.err()).contains("failed at: verify " + sb.liveFile());
        assertThat(sb.deploysLog().get(sb.deploysLog().size() - 1)).contains("event=rollback ").contains("outcome=rollback-failed");
    }

    // -------------------------------------------------------------------------------------------- d: backup

    /// d: D3.7 step 9 keeps an existing `.bak-<sha>` only "with that digest"; one whose bytes differ is replaced, so the
    /// plan says `backup`, not `backup-kept`, and the rollback restores the real old file. Mutation: trust the existing
    /// file without its digest (the plan says `backup-kept`, the rollback restores the wrong bytes and fails at verify).
    @Test
    void d_anExistingBackupWithWrongBytes_isReplaced_notRestoredFrom() throws Exception {
        var bak = sb.liveFile().resolveSibling("toy.bin.bak-" + oldSha);
        Files.writeString(bak, "not the old file\n");
        var dry = sb.dryRun(COMMIT, oldSha, newSha);
        assertThat(dry.rc()).as(dry.err()).isZero();
        assertThat(dry.out()).containsPattern("plan 0[0-9]{2} backup backup ").doesNotContain("backup-kept");
        sb.behave(newSha, "500");
        var out = sb.installWithPlan(COMMIT, oldSha, newSha, DeploySandbox.planSha(dry));
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(5);
        assertThat(liveSha()).isEqualTo(oldSha);
        assertThat(bak).hasContent(OLD_CONTENT.strip());
    }

    // ------------------------------------------------------------------------------------- h: restore order

    private String stage2(String commit, String toy, String second) throws Exception {
        var into = Files.createDirectories(sb.stageDir(commit));
        Files.copy(DeploySandbox.OPS_DEPLOY.resolve("lib.sh"), into.resolve("lib.sh"), StandardCopyOption.REPLACE_EXISTING);
        exe(into.resolve("deploy-toy.sh"), Files.readString(DeploySandbox.RESOURCES.resolve("deploy-toy2.sh")));
        var src = Files.createDirectories(sb.root.resolve("src2-" + commit));
        Files.writeString(src.resolve("toy.bin"), toy);
        Files.writeString(src.resolve("second.bin"), second);
        var out = sb.run(into.resolve("deploy-toy.sh"), sb.root, "stage-local", "--commit", commit, "--src", src.toString(), "--into", into.toString());
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        return Files.readAllLines(into.resolve("STAGE")).stream().filter(l -> l.startsWith("set=")).findFirst().orElseThrow().substring(4);
    }

    /// h: D3.7 step 13 restores "in reverse order" of the swap. Two files make it visible in the `mv` log: the swap
    /// renames toy.bin then second.bin, the rollback second.bin then toy.bin. Mutation: the rollback walks the swap order.
    @Test
    void h_rollbackRestoresInReverseSwapOrder() throws Exception {
        var commit2 = "2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e";
        sb.live("toy zero\n");
        Files.writeString(sb.liveFile().resolveSibling("second.bin"), "second zero\n");
        var newSet = stage2(commit2, "toy one\n", "second one\n");
        var live = sb.run(sb.stageDir(commit2).resolve("deploy-toy.sh"), sb.root, "live");
        var oldSet = live.out().lines().findFirst().orElseThrow().substring("live ".length());
        mvShim();
        sb.behave(DeploySandbox.sha256("toy one\n"), "500");
        var out = sb.install(commit2, oldSet, newSet);
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(5);
        assertThat(mvLog().stream().map(p -> Path.of(p).getFileName().toString()).toList())
            .containsExactly("toy.bin", "second.bin", "second.bin", "toy.bin");
        assertThat(sb.liveFile()).hasContent("toy zero");
        assertThat(sb.liveFile().resolveSibling("second.bin")).hasContent("second zero");
    }

    // ------------------------------------------------------------------------------------------ i: settle

    /// i: a crash that shows only after time has passed (`late.crash`, armed by a `sleep` after the restart) is caught by
    /// the settle check (D3.7 step 11: sleep DL_SETTLE, then `dl_settled`): rollback, exit 5. Mutation: the settle `sleep`
    /// removed, so no time passes, the crash never shows and the install reports `installed`.
    @Test
    void i_aCrashThatShowsOnlyAfterTheSettleSleep_isDetected() throws Exception {
        sleepShim();
        systemctlShim();
        marker("late.crash", "");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(5);
        assertThat(out.out()).contains("settle");
        assertThat(sb.deploysLog().get(0)).contains("outcome=health-failed");
        assertThat(liveSha()).isEqualTo(oldSha);
    }

    // ------------------------------------------------------------------------------------ j: fresh-active

    /// j: `fresh-active` means a MainPID other than the one before the restart. A service that keeps its pid (here the
    /// restart did nothing) is not freshly active, so the install never reports `installed`. Mutation: `dl_fresh_active`
    /// ignores the pid from before the restart.
    @Test
    void j_aServiceThatKeepsItsMainPid_isNotFreshlyActive() throws Exception {
        systemctlShim();
        var state = Files.createDirectories(sb.home.resolve("shim/state"));
        Files.writeString(state.resolve("toy.active"), "active\n");
        Files.writeString(state.resolve("toy.pid"), "77\n");
        marker("frozen", "");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(6);
        assertThat(out.out()).contains("probe toy not freshly active");
        assertThat(String.join("\n", sb.deploysLog())).doesNotContain("outcome=installed").contains("outcome=health-failed");
    }

    // ------------------------------------------------------------------------------------ k: after is soft

    /// k: a failure in the `after` phase is a warning only (the unit is healthy; D3.7 step 11 runs `unit_after_health` after
    /// the record decision). Mutation: make the `after` phase hard (the install ends `partial`, exit 8).
    @Test
    void k_aFailedAfterAction_isAWarning_theOutcomeStaysInstalled() throws Exception {
        systemctlShim();
        marker("fail-timer", "");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(out.err()).containsPattern("WARNING: 0[0-9]{2} start failed \\(the outcome is unchanged\\)");
        assertThat(out.out()).contains("start toy-timer.timer").containsPattern("done 025");
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).contains("outcome=installed");
    }

    /// k, rollback: the same action failing after a rollback's health re-check stays a warning, the rollback stays `rolled-back`.
    @Test
    void k_aFailedAfterActionInTheRollback_isAWarning_theRollbackStaysRolledBack() throws Exception {
        systemctlShim();
        marker("fail-timer", "");
        sb.behave(newSha, "500");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(5);
        assertThat(out.err()).containsPattern("WARNING: 0[0-9]{2} start failed \\(the outcome is unchanged\\)");
        assertThat(sb.deploysLog().get(1)).contains("outcome=rolled-back");
    }

    // --------------------------------------------------------------------------------------- b, t: modes

    private List<String> rollbackDirs() throws Exception {
        try (Stream<Path> files = Files.list(sb.home.resolve(".deploy/rollback"))) {
            return files.map(p -> PosixFilePermissions.toString(permissions(p))).toList();
        }
    }

    private static java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions(Path p) {
        try {
            return Files.getPosixFilePermissions(p);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /// b: D5 puts `~/.deploy` at 0700. The install's own `chmod 700` must have run before the first service action
    /// (`stop`), because a later write of `deploys.log` also sets it and would hide a missing one. Mutation: remove the
    /// `chmod 700 "$HOME/.deploy"` of the plan's `mkdir`.
    @Test
    void b_dotDeploy_is0700_beforeTheServiceIsStopped_andAfter() throws Exception {
        systemctlShim();
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(Files.readAllLines(sb.home.resolve("shim/modes"))).contains("deploy " + DRWX);
        assertThat(PosixFilePermissions.toString(permissions(sb.home.resolve(".deploy")))).isEqualTo("rwx------");
    }

    /// t: D5 puts `~/.deploy/rollback/<u>-<stamp>/` at 0700, from the moment it exists. Mutation: remove the `-m 0700`
    /// of the plan's `mkdir`.
    @Test
    void t_theRollbackDir_is0700_beforeTheServiceIsStopped_andAfter() throws Exception {
        systemctlShim();
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(Files.readAllLines(sb.home.resolve("shim/modes"))).contains("rollback " + DRWX);
        assertThat(rollbackDirs()).containsExactly("rwx------");
    }
}
