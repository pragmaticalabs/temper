package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// `ops/bb/bb-install-hold.sh` (issue #93) run for real against three fake hosts h1, h2, h3. `BB_HOLD_SSH` is a shim that logs each
/// call with its options and runs the remote command with `HOME` set to the host's directory here, so every holder takes a real flock
/// on a real `~/work/queue/.lock`. The shim stalls one host's calls of one kind (`exec sleep`), the way a hung ssh does. The install
/// command is a fake that samples every host's HOLDF once a second while it runs. Nothing here touches a real host.
///
/// Probes (lane93, 2026-11-22, each on a throwaway branch, never pushed): the whole class run against a bash port of the zsh
/// mechanism it replaces (owner-cmds install-bb-91: one sequential heartbeat loop, unbounded ssh, no pre-install check) ->
/// [#aStalledHeartbeatToOneHost_keepsEveryOtherHostHeld_andTheLapseIsReported],
/// [#aFailedHeartbeatCall_doesNotEndTheLoop_andNoHoldEnds] and
/// [#aHoldGoneBeforeTheInstall_runsNothing_andReleasesEveryHold] red; drop the per-call wall limit ->
/// [#aStalledCall_isCutAtTheWallLimit_soTheRefusalComesInBoundedTime] red; drop the pre-install HOLDF check ->
/// [#aHoldGoneBeforeTheInstall_runsNothing_andReleasesEveryHold] red.
///
/// Rows added for verifier v93a's findings on #96 (lane93c, 2026-11-22; red at 99bf5b782, green at the fix; mutants on throwaway
/// branches): held() proves the lock is held, not that HOLDF exists; no call rides a ControlMaster; the heartbeat bound is
/// HB_EVERY + 2*SSH_WALL + 1 < HB_STALE; the install's nonzero status is exit 11; `touch -c` for an in-flight heartbeat.
class BbInstallHoldIT {
    private static final Path SCRIPT = BbFixtures.OPS_BB.resolve("bb-install-hold.sh");
    private static final List<String> HOSTS = List.of("h1", "h2", "h3");
    private static final String HB_CALL = "*&& touch*";

    @TempDir
    Path dir;

    private Path log;
    private Path shim;
    private Path install;
    private final List<Process> lockers = new ArrayList<>();

    @BeforeEach
    void hosts() throws IOException {
        assumeTrue(BbFixtures.linux(), "the holder needs flock and GNU stat");
        log = dir.resolve("ssh.log");
        for (var h : HOSTS) {
            Files.createDirectories(home(h).resolve("work/queue/running"));
            Files.createDirectories(home(h).resolve("work/queue/waiting"));
        }

        shim = BbFixtures.executable(dir.resolve("shim"),
                                     """
            #!/bin/bash
            # fake ssh: [-n] [-o <opt>]... <target> <remote command>; logs the call, then runs it with HOME = the target's fake home
            opts=
            while [ $# -gt 0 ]; do case $1 in -n) opts="$opts -n"; shift;; -o) opts="$opts $2"; shift 2;; *) break;; esac; done
            target=$1; shift; cmd="$*"; h=${target#*@}
            printf '%s|%s|%s\\n' "$target" "${opts# }" "$cmd" >> "@LOG@"
            if [ "${SHIM_STALL_HOST:-}" = "$h" ] && [[ $cmd == $SHIM_STALL_MATCH ]] && { [ -z "${SHIM_STALL_FLAG:-}" ] || [ -e "$SHIM_STALL_FLAG" ]; }; then
              exec sleep "${SHIM_STALL_SECS:-60}"
            fi
            if [ "${SHIM_FAIL_HOST:-}" = "$h" ] && [[ $cmd == $SHIM_FAIL_MATCH ]]; then
              # the first SHIM_FAIL_COUNT matching calls fail at once (rc 255), the way an ssh does when its connection drops
              n=$(cat "$HOME_BASE/$h.fails" 2> /dev/null || echo 0)
              if [ "$n" -lt "${SHIM_FAIL_COUNT:-1}" ]; then echo $((n + 1)) > "$HOME_BASE/$h.fails"; exit 255; fi
            fi
            if [ "${SHIM_TICKET_HOST:-}" = "$h" ] && [[ $cmd == *nohup* ]] && [ ! -e "$HOME_BASE/$h.ticketed" ]; then
              # a ticket enqueued while the holder starts: present for 3 s, then it finishes on its own
              touch "$HOME_BASE/$h.ticketed" "$HOME_BASE/$h/work/queue/running/late"
              (sleep 3; rm -f "$HOME_BASE/$h/work/queue/running/late") > /dev/null 2>&1 < /dev/null &
            fi
            if [ "${SHIM_DELAY_HOST:-}" = "$h" ] && [[ $cmd == $SHIM_DELAY_MATCH ]] && [ -e "${SHIM_DELAY_FLAG:-/nonexistent}" ]; then
              # an armed, in-flight call: runs its test, then waits SHIM_DELAY_SECS before the touch (no ampersand in a replacement)
              touch "$HOME_BASE/$h.inflight"; cmd="${cmd%%&& touch*}&& sleep ${SHIM_DELAY_SECS:-3} && touch${cmd#*&& touch}"
            fi
            HOME="$HOME_BASE/$h" exec bash -c "$cmd"
            """.replace("@LOG@", log.toString()));
        install = BbFixtures.executable(dir.resolve("install"),
                                        """
            #!/bin/bash
            # fake install command: records its args and pid, arms the shim's stall if asked, samples every HOLDF once a second
            echo "$*" > "@DIR@/install.args"; echo $$ > "@DIR@/install.pid"
            [ -z "${ARM_STALL:-}" ] || touch "$ARM_STALL"
            i=0
            while [ $i -lt "${SAMPLE_SECS:-1}" ]; do
              line=; for h in h1 h2 h3; do if [ -s "$HOME_BASE/$h/work/.install-hold" ]; then line="$line $h=held"; else line="$line $h=gone"; fi; done
              echo "${line# }" >> "@DIR@/samples"; i=$((i + 1)); sleep 1
            done
            """.replace("@DIR@", dir.toString()));
    }

    @AfterEach
    void release() throws IOException {
        lockers.forEach(Process::destroyForcibly);
        for (var h : HOSTS) {
            // a holder left by a failed row exits within a second once WANT is gone
            Files.deleteIfExists(home(h).resolve("work/.install-want"));
        }
    }

    private Path home(String host) {
        return dir.resolve("homes")
                  .resolve(host);
    }

    private Map<String, String> env(Map<String, String> extra) {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME",
                dir.resolve("mac-home").toString());
        env.put("HOME_BASE",
                dir.resolve("homes").toString());
        env.put("BB_HOLD_SSH", shim.toString());
        env.put("DRAIN_SECS", "30");
        env.put("POLL_SECS", "1");
        env.put("TAKE_SECS", "10");
        env.put("HB_EVERY", "1");
        env.put("HB_STALE", "8");
        env.put("SSH_WALL", "2");
        env.put("CONNECT_SECS", "2");
        env.putAll(extra);

        return env;
    }

    private List<String> command(String... args) {
        var command = new ArrayList<>(List.of("bash", SCRIPT.toString()));

        command.addAll(List.of(args));

        return command;
    }

    private List<String> allHostsThenInstall() {
        return command("aether@h1", "aether@h2", "aether@h3", "--", install.toString(), "a", "b c");
    }

    private BbFixtures.Outcome hold(Map<String, String> extra) throws Exception {
        return BbFixtures.run(allHostsThenInstall(), env(extra), null, null);
    }

    private List<String> sshCalls() throws IOException {
        return Files.exists(log)
               ? Files.readAllLines(log)
               : List.of();
    }

    private List<String> samples() throws IOException {
        var file = dir.resolve("samples");

        return Files.exists(file)
               ? Files.readAllLines(file)
               : List.of();
    }

    private boolean installRan() {
        return Files.exists(dir.resolve("install.args"));
    }

    private void assertNoTokensAnywhere() {
        for (var h : HOSTS) {
            assertThat(home(h).resolve("work/.install-want")).as(h + " WANT").doesNotExist();
            assertThat(home(h).resolve("work/.install-hold")).as(h + " HOLDF").doesNotExist();
        }
    }

    /// every host's queue lock can be taken again (no holder left), within `secs`
    private void assertEveryLockFreeWithin(int secs) throws Exception {
        for (var h : HOSTS) {
            var lock = home(h).resolve("work/queue/.lock");
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(secs);
            var free = false;

            while (!free && System.nanoTime() < deadline) {
                free = BbFixtures.run(List.of("flock",
                                              "-n",
                                              lock.toString(),
                                              "true"),
                                      Map.of("PATH",
                                             System.getenv("PATH")),
                                      null,
                                      null)
                                 .rc() == 0;
                if (!free) {
                    Thread.sleep(250);
                }
            }

            assertThat(free).as(h + "'s queue lock is free within " + secs + "s").isTrue();
        }
    }

    @Test
    void everyQueueHeld_theInstallRunsOnceWithEveryHoldPresent_andEveryHoldIsReleased() throws Exception {
        var outcome = hold(Map.of());

        assertThat(outcome.rc()).as(outcome.stderr()).isZero();
        assertThat(outcome.stdout()).containsSubsequence("held aether@h1 (queue empty, lock taken)",
                                                         "held aether@h2 (queue empty, lock taken)",
                                                         "held aether@h3 (queue empty, lock taken)",
                                                         "every queue held",
                                                         "every hold lasted");
        assertThat(Files.readString(dir.resolve("install.args")).strip()).isEqualTo("a b c");
        assertThat(samples()).containsExactly("h1=held h2=held h3=held");
        assertThat(outcome.stderr()).doesNotContain("WARNING");
        assertNoTokensAnywhere();
        assertEveryLockFreeWithin(5);
    }

    @Test
    void everySshCall_isBatch_withConnectAndAliveTimeouts_andNoStdin() throws Exception {
        var outcome = hold(Map.of());

        assertThat(outcome.rc()).as(outcome.stderr()).isZero();
        assertThat(sshCalls()).isNotEmpty()
                  .allSatisfy(call -> assertThat(call.split("\\|") [1]).isEqualTo("-n BatchMode=yes ConnectTimeout=2 ServerAliveInterval=5 ServerAliveCountMax=3 ControlMaster=no ControlPath=none"));
    }

    /// The #93 scenario: h2's heartbeat calls hang once the install has started. h1 and h3 stay held throughout; h2's hold ends and
    /// is named, exit 10. The sequential loop this replaces stuck on h2 and starved h3 (and h1) as well.
    @Test
    void aStalledHeartbeatToOneHost_keepsEveryOtherHostHeld_andTheLapseIsReported() throws Exception {
        var flag = dir.resolve("stall").toString();
        var outcome = hold(Map.of("SHIM_STALL_HOST",
                                  "h2",
                                  "SHIM_STALL_MATCH",
                                  HB_CALL,
                                  "SHIM_STALL_FLAG",
                                  flag,
                                  "ARM_STALL",
                                  flag,
                                  "SHIM_STALL_SECS",
                                  "30",
                                  "SAMPLE_SECS",
                                  "14"));

        assertThat(samples()).as(outcome.stderr())
                  .hasSize(14)
                  .allSatisfy(sample -> assertThat(sample).contains("h1=held", "h3=held"));
        assertThat(samples().getLast()).contains("h2=gone");
        assertThat(outcome.rc()).as(outcome.stderr()).isEqualTo(10);
        assertThat(outcome.stderr()).contains("WARNING: aether@h2's hold ended before the install finished")
                  .doesNotContain("aether@h1's hold")
                  .doesNotContain("aether@h3's hold");
        assertNoTokensAnywhere();
    }

    /// h2's first two heartbeat calls FAIL at once (rc 255; not a hang). The loop goes on, the third call succeeds, and no hold ends:
    /// the zsh loop this replaces ran under `set -e` in a subshell and died at the first failed call, so every host went stale.
    @Test
    void aFailedHeartbeatCall_doesNotEndTheLoop_andNoHoldEnds() throws Exception {
        var outcome = hold(Map.of("SHIM_FAIL_HOST",
                                  "h2",
                                  "SHIM_FAIL_MATCH",
                                  HB_CALL,
                                  "SHIM_FAIL_COUNT",
                                  "2",
                                  "SAMPLE_SECS",
                                  "14"));

        assertThat(samples()).as(outcome.stderr())
                  .hasSize(14)
                  .allSatisfy(sample -> assertThat(sample).isEqualTo("h1=held h2=held h3=held"));
        assertThat(outcome.rc()).as(outcome.stderr()).isZero();
        assertThat(outcome.stderr()).contains("heartbeat: aether@h2: refresh failed (rc 255)").doesNotContain("WARNING");
        assertNoTokensAnywhere();
    }

    /// h1 is held first, then its heartbeat hangs while h2 drains (12 s): h1's hold ends before the install could start. Nothing is
    /// run and every hold is released, exit 9.
    @Test
    void aHoldGoneBeforeTheInstall_runsNothing_andReleasesEveryHold() throws Exception {
        var ticket = Files.writeString(home("h2").resolve("work/queue/running/t1"), "ticket\n");
        var finisher = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(12_000);
                Files.delete(ticket);
            } catch (InterruptedException | IOException e) {
                throw new IllegalStateException(e);
            }
        });
        var outcome = hold(Map.of("SHIM_STALL_HOST", "h1", "SHIM_STALL_MATCH", HB_CALL, "SHIM_STALL_SECS", "30"));

        finisher.join();
        assertThat(installRan()).as(outcome.stderr()).isFalse();
        assertThat(outcome.rc()).as(outcome.stderr()).isEqualTo(9);
        assertThat(outcome.stderr()).contains("refused: the hold on aether@h1 ended before the install started; every hold released; nothing run");
        assertThat(outcome.stdout()).contains("held aether@h2", "held aether@h3");
        assertNoTokensAnywhere();
        assertEveryLockFreeWithin(5);
    }

    /// h2's take-check calls hang for 60 s. The wall limit cuts each one at 2 s, so the refusal comes once TAKE_SECS (4) has passed,
    /// not after the hang; every spawned host is released.
    @Test
    void aStalledCall_isCutAtTheWallLimit_soTheRefusalComesInBoundedTime() throws Exception {
        var started = System.nanoTime();
        var outcome = hold(Map.of("SHIM_STALL_HOST",
                                  "h2",
                                  "SHIM_STALL_MATCH",
                                  "\\[ -s *",
                                  "SHIM_STALL_SECS",
                                  "60",
                                  "TAKE_SECS",
                                  "4"));
        var secs = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

        assertThat(secs).as(outcome.stderr()).isLessThan(25);
        assertThat(outcome.rc()).as(outcome.stderr()).isEqualTo(9);
        assertThat(outcome.stderr()).contains("refused: aether@h2: the holder did not take the queue lock in 4s; every hold released; nothing run");
        assertThat(installRan()).isFalse();
        assertNoTokensAnywhere();
        assertEveryLockFreeWithin(5);
    }

    /// The script is killed (SIGKILL) while the install runs: no heartbeat call starts after the death, so every holder exits within
    /// SSH_WALL + HB_STALE + 1 s (9 s) of it; 13 s allowed for a loaded host.
    @Test
    void aKilledScript_freesEveryHostWithinTheStaleBound() throws Exception {
        var builder = BbFixtures.process(allHostsThenInstall(), env(Map.of("SAMPLE_SECS", "120")));

        builder.redirectOutput(dir.resolve("out.txt").toFile());
        builder.redirectError(dir.resolve("err.txt").toFile());
        var script = builder.start();
        var pidFile = dir.resolve("install.pid");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }

        assertThat(pidFile).as(Files.readString(dir.resolve("err.txt"))).exists();
        Thread.sleep(1500);
        assertThat(samples()).first().isEqualTo("h1=held h2=held h3=held");
        script.destroyForcibly().waitFor();
        var killed = System.nanoTime();

        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip())).ifPresent(ProcessHandle::destroyForcibly);
        assertEveryLockFreeWithin(13);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - killed)).isLessThanOrEqualTo(13);
        for (var h : HOSTS) {
            assertThat(home(h).resolve("work/.install-hold")).as(h + " HOLDF").doesNotExist();
        }
    }

    /// h2's queue lock is held by someone else, so its holder is still blocked in flock when the take times out. After the refusal
    /// the lock is released: the holder gets it, finds WANT gone and exits without holding.
    @Test
    void aHolderStillBlockedInFlock_exitsWhenItGetsTheLock_afterTheRefusal() throws Exception {
        var lock = home("h2").resolve("work/queue/.lock");
        var locker = BbFixtures.process(List.of("bash",
                                                "-c",
                                                "exec 9> \"$1\"; flock 9; echo locked; exec sleep 60",
                                                "locker",
                                                lock.toString()),
                                        Map.of("PATH",
                                               System.getenv("PATH")))
                               .start();

        lockers.add(locker);
        var line = new String(locker.getInputStream().readNBytes(7),
                              StandardCharsets.UTF_8);

        assertThat(line).isEqualTo("locked\n");
        var outcome = hold(Map.of("TAKE_SECS", "3"));

        assertThat(outcome.rc()).as(outcome.stderr()).isEqualTo(9);
        assertThat(outcome.stderr()).contains("refused: aether@h2: the holder did not take the queue lock in 3s");
        assertThat(sshCalls()).anyMatch(call -> call.startsWith("aether@h2|") && call.contains("nohup"));
        assertThat(installRan()).isFalse();
        locker.destroyForcibly().waitFor();
        assertEveryLockFreeWithin(5);
        assertNoTokensAnywhere();
    }

    @Test
    void aQueueThatDoesNotDrain_isRefused_noTicketTouched_andNothingRun() throws Exception {
        var ticket = Files.writeString(home("h2").resolve("work/queue/running/t1"), "ticket\n");
        var mtime = Files.getLastModifiedTime(ticket);
        var outcome = hold(Map.of("DRAIN_SECS", "3"));

        assertThat(outcome.rc()).as(outcome.stderr()).isEqualTo(9);
        assertThat(outcome.stderr()).contains("refused: aether@h2 did not drain in 3s (running+waiting=1); every hold released; nothing run");
        assertThat(outcome.stdout()).contains("held aether@h1");
        assertThat(installRan()).isFalse();
        assertThat(Files.readString(ticket)).isEqualTo("ticket\n");
        assertThat(Files.getLastModifiedTime(ticket)).isEqualTo(mtime);
        assertThat(sshCalls()).noneMatch(call -> call.startsWith("aether@h3|"));
        assertNoTokensAnywhere();
        assertEveryLockFreeWithin(5);
    }

    @Test
    void aTicketArrivingBeforeTheLock_isDrainedAgain_notCancelled() throws Exception {
        var outcome = hold(Map.of("SHIM_TICKET_HOST", "h2"));

        assertThat(outcome.rc()).as(outcome.stderr()).isZero();
        assertThat(outcome.stdout()).containsSubsequence("held aether@h1",
                                                         "aether@h2: a ticket arrived before the lock (running+waiting=1); draining again",
                                                         "held aether@h2",
                                                         "held aether@h3");
        assertThat(samples()).containsExactly("h1=held h2=held h3=held");
        assertNoTokensAnywhere();
    }

    @Test
    void usage_isExit2_beforeAnySsh() throws Exception {
        var x = install.toString();

        for (var args : List.of(List.<String> of(),
                                List.of("aether@h1"),
                                List.of("aether@h1", "--"),
                                List.of("-o", "--", x),
                                List.of("aether@h1", "aether@h1", "--", x))) {
            var outcome = BbFixtures.run(command(args.toArray(String[]::new)), env(Map.of()), null, null);

            assertThat(outcome.rc()).as(args + ": " + outcome.stderr()).isEqualTo(2);
        }

        var tooSlow = BbFixtures.run(command("aether@h1", "--", x), env(Map.of("HB_EVERY", "4")), null, null);

        assertThat(tooSlow.rc()).isEqualTo(2);
        assertThat(tooSlow.stderr()).contains("HB_EVERY + 2*SSH_WALL + 1 must be below HB_STALE");
        // two successful calls can touch WANT HB_EVERY + 2*SSH_WALL apart: 1 + 8 + 1 = 10 is the edge, and the old bound (1 + 4 < 6) is refused
        for (var stale : List.of("6", "10")) {
            var edge = BbFixtures.run(command("aether@h1", "--", x),
                                      env(Map.of("HB_EVERY", "1", "SSH_WALL", "4", "HB_STALE", stale)),
                                      null,
                                      null);

            assertThat(edge.rc()).as("HB_STALE " + stale + ": " + edge.stderr()).isEqualTo(2);
            assertThat(edge.stderr()).contains("HB_EVERY + 2*SSH_WALL + 1 must be below HB_STALE");
        }

        assertThat(sshCalls()).isEmpty();
        assertThat(installRan()).isFalse();
    }

    /// v93a R1: the holder of h2 is SIGKILLed during the install. HOLDF stays and the lock is free; a hold that ends during the install
    /// is reported (exit 10).
    @Test
    void aKilledHolderMidInstall_isReportedAsALapse() throws Exception {
        var killer = BbFixtures.executable(dir.resolve("install-kill"),
                                           """
            #!/bin/bash
            r="@DIR@/lockstate"
            for h in h1 h2 h3; do if flock -n "$HOME_BASE/$h/work/queue/.lock" true; then echo "$h=free"; else echo "$h=busy"; fi; done >> "$r"
            kill -9 "$(cat "$HOME_BASE/h2/work/.install-hold")"
            sleep 2
            if flock -n "$HOME_BASE/h2/work/queue/.lock" true; then echo "after-kill h2=free"; else echo "after-kill h2=busy"; fi >> "$r"
            """.replace("@DIR@", dir.toString()));
        var outcome = BbFixtures.run(command("aether@h1", "aether@h2", "aether@h3", "--", killer.toString()),
                                     env(Map.of()),
                                     null,
                                     null);

        assertThat(Files.readAllLines(dir.resolve("lockstate"))).as("reached: every lock busy before the kill, h2's free after it")
                  .containsExactly("h1=busy", "h2=busy", "h3=busy", "after-kill h2=free");
        assertThat(outcome.rc()).as(outcome.stdout() + outcome.stderr()).isEqualTo(10);
        assertThat(outcome.stderr()).contains("WARNING: aether@h2's hold ended before the install finished")
                  .doesNotContain("aether@h1's hold")
                  .doesNotContain("aether@h3's hold");
    }

    /// The holder of h2 is SIGKILLed and a queue ticket's grant takes the freed lock, so the lock is busy but no longer ours: a busy lock
    /// alone does not prove the hold, the holder's pid must be alive too.
    @Test
    void aKilledHolderWhoseLockIsTakenByAnotherProcess_isStillALapse() throws Exception {
        var killer = BbFixtures.executable(dir.resolve("install-kill-retake"),
                                           """
            #!/bin/bash
            kill -9 "$(cat "$HOME_BASE/h2/work/.install-hold")"
            sleep 1
            flock -w 5 "$HOME_BASE/h2/work/queue/.lock" sleep 4 > /dev/null 2>&1 < /dev/null &
            sleep 1
            if flock -n "$HOME_BASE/h2/work/queue/.lock" true; then echo "retaken=no"; else echo "retaken=yes"; fi > "@DIR@/retaken"
            """.replace("@DIR@", dir.toString()));
        var outcome = BbFixtures.run(command("aether@h1", "aether@h2", "aether@h3", "--", killer.toString()),
                                     env(Map.of()),
                                     null,
                                     null);

        assertThat(Files.readString(dir.resolve("retaken")).strip()).as("reached: the lock is busy again after the kill")
                  .isEqualTo("retaken=yes");
        assertThat(outcome.rc()).as(outcome.stdout() + outcome.stderr()).isEqualTo(10);
        assertThat(outcome.stderr()).contains("WARNING: aether@h2's hold ended before the install finished");
    }

    /// HOLDF names a LIVE pid (the script, the install's parent, which outlives the install) but h2's lock is free: the holder was killed and its HOLDF's pid has been reused, say
    /// after a reboot. The pid alone does not prove the hold, the busy lock must too.
    @Test
    void aHoldFileNamingALivePidWhoseLockIsFree_isALapse() throws Exception {
        var stale = BbFixtures.executable(dir.resolve("install-stale"),
                                          """
            #!/bin/bash
            kill -9 "$(cat "$HOME_BASE/h2/work/.install-hold")"
            echo $PPID > "$HOME_BASE/h2/work/.install-hold"
            sleep 1
            if flock -n "$HOME_BASE/h2/work/queue/.lock" true; then echo "free=yes"; else echo "free=no"; fi > "@DIR@/freestate"
            """.replace("@DIR@", dir.toString()));
        var outcome = BbFixtures.run(command("aether@h1", "aether@h2", "aether@h3", "--", stale.toString()),
                                     env(Map.of()),
                                     null,
                                     null);

        assertThat(Files.readString(dir.resolve("freestate")).strip()).as("reached: the lock is free, HOLDF names a live pid")
                  .isEqualTo("free=yes");
        assertThat(outcome.rc()).as(outcome.stdout() + outcome.stderr()).isEqualTo(10);
        assertThat(outcome.stderr()).contains("WARNING: aether@h2's hold ended before the install finished");
    }

    /// h1 is held first; its holder is SIGKILLed while h2 drains (HOLDF stays, the lock is free). The pre-install check must see that h1's
    /// hold is gone: nothing is run, exit 9.
    @Test
    void aHolderKilledWhileAnotherHostDrains_runsNothing() throws Exception {
        var ticket = Files.writeString(home("h2").resolve("work/queue/running/t1"), "ticket\n");
        var killer = Thread.ofVirtual().start(() -> {
            try {
                var holdFile = home("h1").resolve("work/.install-hold");

                Thread.sleep(3_000);
                BbFixtures.run(List.of("kill",
                                       "-9",
                                       Files.readString(holdFile).strip()),
                               Map.of("PATH", System.getenv("PATH")),
                               null,
                               null);
                Thread.sleep(4_000);
                Files.delete(ticket);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        var outcome = hold(Map.of());

        killer.join();
        assertThat(installRan()).as(outcome.stderr()).isFalse();
        assertThat(outcome.rc()).as(outcome.stderr()).isEqualTo(9);
        assertThat(outcome.stderr()).contains("refused: the hold on aether@h1 ended before the install started; every hold released; nothing run");
        assertNoTokensAnywhere();
    }

    /// Two successful heartbeat calls to h2 can touch WANT HB_EVERY + 2*SSH_WALL s apart. Here every call succeeds inside SSH_WALL (4 s) and
    /// they alternate touch-then-3s and 3s-then-touch; HB_STALE 11 is the smallest value the bound HB_EVERY + 2*SSH_WALL + 1 < HB_STALE allows.
    @Test
    void successfulHeartbeatsAtTheStatedBound_keepTheHold() throws Exception {
        var slow = BbFixtures.executable(dir.resolve("slowshim"),
                                         """
            #!/bin/bash
            last=${@: -1}; tgt=${@: -2:1}
            if [ "${tgt#*@}" = h2 ] && [[ $last == *"&& touch"* ]]; then
              n=$(cat "@DIR@/slow.n" 2> /dev/null || echo 0); echo $((n + 1)) > "@DIR@/slow.n"
              if [ $((n % 2)) = 0 ]; then "@SHIM@" "$@"; rc=$?; sleep 3; exit $rc; else sleep 3; exec "@SHIM@" "$@"; fi
            fi
            exec "@SHIM@" "$@"
            """.replace("@DIR@",
                        dir.toString())
               .replace("@SHIM@",
                        shim.toString()));
        var outcome = hold(Map.of("BB_HOLD_SSH",
                                  slow.toString(),
                                  "HB_EVERY",
                                  "1",
                                  "SSH_WALL",
                                  "4",
                                  "HB_STALE",
                                  "11",
                                  "SAMPLE_SECS",
                                  "14"));

        assertThat(Files.readString(dir.resolve("slow.n")).strip()).as("reached: h2 heartbeat calls through the slow shim")
                  .isNotEqualTo("1");
        assertThat(outcome.stderr()).as("every heartbeat call succeeded").doesNotContain("refresh failed");
        assertThat(samples()).hasSize(14).allSatisfy(sample -> assertThat(sample).isEqualTo("h1=held h2=held h3=held"));
        assertThat(outcome.rc()).as(outcome.stdout() + outcome.stderr()).isZero();
    }

    /// v93a R4: an install that exits 9 is not "refused, nothing run": the script's own codes never carry the install's status.
    @Test
    void anInstallThatExits9_isExit11_notARefusal() throws Exception {
        var nine = BbFixtures.executable(dir.resolve("install9"),
                                         "#!/bin/bash\necho ran > \"" + dir.resolve("install.args") + "\"\nexit 9\n");
        var outcome = BbFixtures.run(command("aether@h1", "aether@h2", "aether@h3", "--", nine.toString()),
                                     env(Map.of()),
                                     null,
                                     null);

        assertThat(installRan()).as("reached: the install ran").isTrue();
        assertThat(outcome.rc()).as(outcome.stderr()).isEqualTo(11);
        assertThat(outcome.stderr()).contains("install command exited 9; every hold released");
        assertNoTokensAnywhere();
    }

    /// An install that exits 3 while h2's holder is killed: exit 11 names rc 3, and the lapse check still runs and names h2.
    @Test
    void aFailedInstall_isExit11WithItsStatusNamed_andStillNamesTheLapses() throws Exception {
        var failing = BbFixtures.executable(dir.resolve("install-fail"),
                                            """
            #!/bin/bash
            kill -9 "$(cat "$HOME_BASE/h2/work/.install-hold")"
            sleep 1
            exit 3
            """);
        var outcome = BbFixtures.run(command("aether@h1", "aether@h2", "aether@h3", "--", failing.toString()),
                                     env(Map.of()),
                                     null,
                                     null);

        assertThat(outcome.rc()).as(outcome.stderr()).isEqualTo(11);
        assertThat(outcome.stderr()).contains("install command exited 3; every hold released",
                                              "WARNING: aether@h2's hold ended before the install finished")
                  .doesNotContain("aether@h1's hold")
                  .doesNotContain("aether@h3's hold");
        assertNoTokensAnywhere();
    }

    /// HUP, INT and TERM to the script during the install: the install is not cut short (the trap runs when it ends), then the script exits
    /// 128+n with every hold released.
    @Test
    void aSignalDuringTheInstall_exitsWith128PlusN_afterEveryHoldIsReleased() throws Exception {
        for (var sig : List.of("HUP", "INT", "TERM")) {
            Files.deleteIfExists(dir.resolve("install.pid"));
            Files.deleteIfExists(dir.resolve("samples"));
            // the build host runs the suite under nohup (HUP ignored, and bash cannot trap a signal ignored at entry): reset the dispositions first
            var wrapped = new ArrayList<>(List.of("perl",
                                                  "-e",
                                                  "$SIG{HUP} = $SIG{INT} = $SIG{TERM} = 'DEFAULT'; exec @ARGV"));

            wrapped.addAll(allHostsThenInstall().stream().map(String::valueOf).toList());
            var builder = BbFixtures.process(wrapped, env(Map.of("SAMPLE_SECS", "4")));

            builder.redirectOutput(dir.resolve("out.txt").toFile());
            builder.redirectError(dir.resolve("err.txt").toFile());
            var script = builder.start();
            var pidFile = dir.resolve("install.pid");
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

            while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }

            assertThat(pidFile).as(sig + ": " + Files.readString(dir.resolve("err.txt"))).exists();
            Thread.sleep(1000);
            BbFixtures.run(List.of("kill",
                                   "-" + sig,
                                   Long.toString(script.pid())),
                           Map.of("PATH", System.getenv("PATH")),
                           null,
                           null);
            assertThat(script.waitFor(25, TimeUnit.SECONDS)).as(sig + ": the script ended").isTrue();
            assertThat(script.exitValue()).as(sig + ": " + Files.readString(dir.resolve("err.txt")))
                      .isEqualTo(sig.equals("HUP")
                                 ? 129
                                 : sig.equals("INT")
                                   ? 130
                                   : 143);
            assertThat(samples()).as(sig + ": every hold lasted while the install ran")
                      .hasSize(4)
                      .allSatisfy(sample -> assertThat(sample).isEqualTo("h1=held h2=held h3=held"));
            assertNoTokensAnywhere();
            // a holder still sleeping its last second would see the next iteration's WANT and keep the old hold: wait for every lock
            assertEveryLockFreeWithin(5);
        }
    }

    /// A heartbeat call to h2 is still in flight (between its WANT test and its touch) when the holds are released. Its touch must not
    /// recreate WANT, or the holder's hold would outlive "every hold released" by up to HB_STALE: `touch -c`.
    @Test
    void aHeartbeatInFlightAtRelease_doesNotRecreateWant() throws Exception {
        var flag = dir.resolve("delay.flag");
        var armed = BbFixtures.executable(dir.resolve("install-armed"),
                                          """
            #!/bin/bash
            echo "$*" > "@DIR@/install.args"
            touch "@DIR@/delay.flag"
            i=0
            while [ ! -e "$HOME_BASE/h2.inflight" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
            echo "inflight=$([ -e "$HOME_BASE/h2.inflight" ] && echo yes || echo no)" > "@DIR@/inflight"
            """.replace("@DIR@", dir.toString()));
        var outcome = BbFixtures.run(command("aether@h1", "aether@h2", "aether@h3", "--", armed.toString()),
                                     env(Map.of("SHIM_DELAY_HOST",
                                                "h2",
                                                "SHIM_DELAY_MATCH",
                                                HB_CALL,
                                                "SHIM_DELAY_FLAG",
                                                flag.toString(),
                                                "SHIM_DELAY_SECS",
                                                "3",
                                                "HB_EVERY",
                                                "1",
                                                "SSH_WALL",
                                                "5",
                                                "HB_STALE",
                                                "13")),
                                     null,
                                     null);

        assertThat(Files.readString(dir.resolve("inflight")).strip()).as("reached: a delayed heartbeat call was in flight at the release")
                  .isEqualTo("inflight=yes");
        assertThat(outcome.rc()).as(outcome.stderr()).isZero();
        Thread.sleep(5000);
        assertThat(home("h2").resolve("work/.install-want")).as("h2 WANT after the in-flight call's touch")
                  .doesNotExist();
    }
}
