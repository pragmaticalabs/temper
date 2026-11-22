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
/// [#aStalledHeartbeatToOneHost_keepsEveryOtherHostHeld_andTheLapseIsReported] and
/// [#aHoldGoneBeforeTheInstall_runsNothing_andReleasesEveryHold] red; drop the per-call wall limit ->
/// [#aStalledCall_isCutAtTheWallLimit_soTheRefusalComesInBoundedTime] red; drop the pre-install HOLDF check ->
/// [#aHoldGoneBeforeTheInstall_runsNothing_andReleasesEveryHold] red.
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
            if [ "${SHIM_TICKET_HOST:-}" = "$h" ] && [[ $cmd == *nohup* ]] && [ ! -e "$HOME_BASE/$h.ticketed" ]; then
              # a ticket enqueued while the holder starts: present for 3 s, then it finishes on its own
              touch "$HOME_BASE/$h.ticketed" "$HOME_BASE/$h/work/queue/running/late"
              (sleep 3; rm -f "$HOME_BASE/$h/work/queue/running/late") > /dev/null 2>&1 < /dev/null &
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
        env.put("HB_STALE", "6");
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
                  .allSatisfy(call -> assertThat(call.split("\\|") [1]).isEqualTo("-n BatchMode=yes ConnectTimeout=2 ServerAliveInterval=5 ServerAliveCountMax=3"));
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
        assertThat(tooSlow.stderr()).contains("HB_EVERY + SSH_WALL must be below HB_STALE");
        assertThat(sshCalls()).isEmpty();
        assertThat(installRan()).isFalse();
    }
}
