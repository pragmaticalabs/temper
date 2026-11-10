package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/bb/bb-temper` automatic host choice and the per-checkout host binding (owner 2026-11-05) against `ssh`/`scp`
/// stubs first on `PATH`. The hosts reply is injected through the test-only `BB_HOSTS_CMD` seam. Baseline scores:
/// cloudbb-4 0.00, cloudbb-3 0.25, bigboy 0.50; each skip row breaks cloudbb-4 (the best) in one way, so the pick
/// moves to cloudbb-3, which is also where a skip that was NOT applied would show up.
class BbTemperHostIT {
    private static final String BIGBOY = TestHosts.BIGBOY;
    private static final String D1 = TestHosts.D1;
    private static final String C4 = TestHosts.C4;
    private static final String C3 = TestHosts.C3;
    private static final long GIB_KB = 1024L * 1024L;
    private static final long GIB = 1024L * 1024L * 1024L;

    /// One host entry of the controller reply; the defaults are a healthy idle host.
    private record H(String name, String state, String link, boolean retired, String retireAt, double load5, int cores, int running,
                      int waiting, int active, long memKb, long disk) {
        static H of(String name, double load5, int cores) {
            return new H(name, "UP", "UP", false, null, load5, cores, 0, 0, 0, 16 * GIB_KB, 100 * GIB);
        }

        H withState(String v) { return new H(name, v, link, retired, retireAt, load5, cores, running, waiting, active, memKb, disk); }
        H asRetired() { return new H(name, state, link, true, retireAt, load5, cores, running, waiting, active, memKb, disk); }
        H retiringAt(Instant v) { return new H(name, state, link, retired, v.toString(), load5, cores, running, waiting, active, memKb, disk); }
        H withMem(long v) { return new H(name, state, link, retired, retireAt, load5, cores, running, waiting, active, v, disk); }
        H withDisk(long v) { return new H(name, state, link, retired, retireAt, load5, cores, running, waiting, active, memKb, v); }
        H queue(int r, int w, int a) { return new H(name, state, link, retired, retireAt, load5, cores, r, w, a, memKb, disk); }

        String json() {
            return "{\"name\":\"" + name + "\",\"state\":\"" + state + "\",\"link\":\"" + link + "\",\"active\":" + active
                   + ",\"facts\":{\"load1\":" + load5 + ",\"load5\":" + load5 + ",\"cores\":" + cores + ",\"bbRunning\":" + running
                   + ",\"bbWaiting\":" + waiting + ",\"memAvailableKb\":" + memKb + ",\"freeWorkspaceBytes\":" + disk
                   + "},\"sharedCpu\":false,\"retireAt\":" + (retireAt == null ? "null" : "\"" + retireAt + "\"") + ",\"retired\":" + retired + "}";
        }
    }

    @TempDir
    Path dir;

    private Path home;
    private Path bin;

    @BeforeEach
    void stubs() throws Exception {
        home = Files.createDirectories(dir.resolve("home"));
        bin = Files.createDirectories(dir.resolve("bin"));
        TestHosts.write(home);
        MacScripts.executable(bin.resolve("ssh"), "for a in \"$@\"; do printf '%s\\n' \"$a\" >> \"$STUBS/ssh.args\"; done\necho -- >> \"$STUBS/ssh.args\"\n"
                                                   + "exit \"$(cat \"$STUBS/ssh.rc\" 2> /dev/null || echo 0)\"\n");
        MacScripts.executable(bin.resolve("scp"), "exit 0\n");
        var gitInit = HermeticProcess.builder("git", "-c", "user.email=t@t", "-c", "user.name=t", "init", "-q").directory(dir.toFile()).inheritIO();
        var git = gitInit.start();
        assertThat(git.waitFor()).isZero();
        var gitCommit = HermeticProcess.builder("git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "i")
                .directory(dir.toFile()).inheritIO();
        var commit = gitCommit.start();
        assertThat(commit.waitFor()).isZero();
    }

    private Map<String, String> env(String... more) {
        var env = new LinkedHashMap<>(MacScripts.env(Map.of("HOME", home.toString(), "PATH", bin + ":/usr/bin:/bin", "STUBS", dir.toString(), "BB_OWNER", "t3")));
        for (var i = 0; i < more.length; i += 2) {
            env.put(more[i], more[i + 1]);
        }
        return env;
    }

    private String hosts(H... hosts) throws IOException {
        var body = new ArrayList<String>();
        for (var h : hosts) {
            body.add(h.json());
        }
        var file = Files.writeString(dir.resolve("hosts.json"), "[" + String.join(",", body) + "]");
        return "cat " + file;
    }

    private H bigboy() { return H.of("bigboy", 8, 16); }
    private H c4() { return H.of("cloudbb-4", 0, 8); }
    private H c3() { return H.of("cloudbb-3", 2, 8); }

    private MacScripts.Result bb(Map<String, String> env, String... args) throws Exception {
        var command = new ArrayList<>(List.of("bash", MacScripts.BB_TEMPER.toString()));
        command.addAll(List.of(args));
        return MacScripts.run(command, env, dir);
    }

    private MacScripts.Result checkout(String hostsCmd, String... more) throws Exception {
        var env = env(more);
        env.put("BB_HOSTS_CMD", hostsCmd);
        return bb(env, "checkout", "t3-x");
    }

    private Path binding() { return home.resolve(".temper/bb-bind/t3-x"); }

    private String bound() throws IOException { return Files.readString(binding()).strip(); }

    private List<List<String>> sshCalls() throws IOException {
        var file = dir.resolve("ssh.args");
        var calls = new ArrayList<List<String>>();
        var current = new ArrayList<String>();
        for (var line : Files.exists(file) ? Files.readAllLines(file) : List.<String>of()) {
            if (line.equals("--")) {
                calls.add(current);
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
        return calls;
    }

    private void assertPicked(MacScripts.Result run, String key, String address) throws Exception {
        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("bb-temper: host " + key + " (score ");
        assertThat(bound()).isEqualTo(key);
        assertThat(sshCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).contains(address));
    }

    // ---- the pick ----------------------------------------------------------------------------------------------
    @Test
    void auto_picksTheLowestScore_andNamesTheOthers() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), c4()));

        assertPicked(run, "cloudbb-4", C4);
        assertThat(run.err()).contains("bb-temper: host cloudbb-4 (score 0.00; ").contains("cloudbb-3 0.25").contains("bigboy 0.50");
    }

    /// Score = (load5 + 4 * (running + waiting + active)) / cores: queued work outweighs load.
    @Test
    void score_countsRunningWaitingAndActiveBuilds_atFourCoresEach() throws Exception {
        // d1: (0 + 4*(1+1+1)) / 8 = 1.50, so cloudbb-3 (0.25) wins although d1 has no load
        var run = checkout(hosts(bigboy(), c3(), c4().queue(1, 1, 1)));

        assertPicked(run, "cloudbb-3", C3);
        assertThat(run.err()).contains("cloudbb-4 1.50");
    }

    @Test
    void auto_skipsAHostThatIsDown() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), c4().withState("DOWN")));

        assertPicked(run, "cloudbb-3", C3);
        assertThat(run.err()).contains("cloudbb-4 skip: state DOWN");
    }

    @Test
    void auto_skipsAHostWhoseLinkIsDown() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), new H("cloudbb-4", "UP", "DOWN", false, null, 0, 8, 0, 0, 0, 16 * GIB_KB, 100 * GIB)));

        assertPicked(run, "cloudbb-3", C3);
        assertThat(run.err()).contains("cloudbb-4 skip: link DOWN");
    }

    @Test
    void auto_skipsARetiredHost() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), c4().asRetired()));

        assertPicked(run, "cloudbb-3", C3);
        assertThat(run.err()).contains("cloudbb-4 skip: retired");
    }

    @Test
    void auto_skipsAHostRetiringWithinTwoHours() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), c4().retiringAt(Instant.now().plus(1, ChronoUnit.HOURS))));

        assertPicked(run, "cloudbb-3", C3);
        assertThat(run.err()).contains("cloudbb-4 skip: retires within 2 h");
    }

    /// A retirement more than two hours away does not exclude the host (nanosecond instants parse).
    @Test
    void auto_keepsAHostRetiringLater() throws Exception {
        var later = Instant.now().plus(3, ChronoUnit.HOURS).plusNanos(123456789);

        var run = checkout(hosts(bigboy(), c3(), c4().retiringAt(later)));

        assertPicked(run, "cloudbb-4", C4);
    }

    @Test
    void auto_skipsAHostWithLowFreeDisk() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), c4().withDisk(20 * GIB - 1)));

        assertPicked(run, "cloudbb-3", C3);
        assertThat(run.err()).contains("cloudbb-4 skip: disk low");
    }

    @Test
    void auto_skipsAHostWithLowAvailableMemory() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), c4().withMem(6 * GIB_KB - 1)));

        assertPicked(run, "cloudbb-3", C3);
        assertThat(run.err()).contains("cloudbb-4 skip: memory low");
    }

    /// Exactly the limits qualify (20 GiB, 6 GiB).
    @Test
    void auto_theLimitsThemselvesQualify() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), c4().withDisk(20 * GIB).withMem(6 * GIB_KB)));

        assertPicked(run, "cloudbb-4", C4);
    }

    /// Equal scores: bigboy, then cloudbb-3, then cloudbb-4 (the order of the candidate list).
    @Test
    void auto_aTieGoesBigboyThenC3ThenC4() throws Exception {
        var all = checkout(hosts(H.of("cloudbb-3", 1, 8), H.of("cloudbb-4", 1, 8), H.of("bigboy", 1, 8)));
        assertPicked(all, "bigboy", BIGBOY);
        Files.delete(binding());
        Files.deleteIfExists(dir.resolve("ssh.args"));

        var noBigboy = checkout(hosts(H.of("cloudbb-3", 1, 8), H.of("cloudbb-4", 1, 8), H.of("bigboy", 1, 8).withState("DOWN")));
        assertPicked(noBigboy, "cloudbb-3", C3);
    }

    private H d1() { return H.of("cloudbb-d1", 0, 64); }

    /// cloudbb-d1 is reserved for the maintainer's timing runs (2026-11-07): UP, idle and with the most cores it would score best (0.00),
    /// yet it is neither picked nor listed as a candidate.
    @Test
    void auto_neverPicksCloudbbD1_evenWhenItIsUpIdleAndScoresBest() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), c4().queue(1, 1, 1), d1()));

        assertPicked(run, "cloudbb-3", C3);
        assertThat(run.err()).doesNotContain("cloudbb-d1");
        assertThat(sshCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).doesNotContain(D1));
    }

    /// d1 the only host in the reply: no candidate, so the bigboy fallback, never d1.
    @Test
    void auto_cloudbbD1Alone_isNotACandidate_soTheFallbackApplies() throws Exception {
        var run = checkout(hosts(d1()));

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(bound()).isEqualTo("bigboy");
        assertThat(run.err()).contains("bb-temper: host bigboy (fallback: no host qualifies: ").doesNotContain("cloudbb-d1");
    }

    @Test
    void explicitCloudbbD1_isStillAccepted_andBound() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), d1()), "BB_HOST", "cloudbb-d1");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(bound()).isEqualTo("cloudbb-d1");
        assertThat(sshCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).contains(D1));
    }

    @Test
    void auto_ignoresHostsOutsideItsOwnTable() throws Exception {
        var run = checkout(hosts(H.of("elsewhere", 0, 64), bigboy()));

        assertPicked(run, "bigboy", BIGBOY);
    }

    // ---- fallback ----------------------------------------------------------------------------------------------
    @Test
    void auto_anUnreachableController_fallsBackToBigboy() throws Exception {
        var run = checkout("exit 1");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(bound()).isEqualTo("bigboy");
        assertThat(sshCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).contains(BIGBOY));
        assertThat(run.err()).contains("bb-temper: host bigboy (fallback: controller unreachable)");
    }

    @Test
    void auto_badJson_fallsBackToBigboy() throws Exception {
        var run = checkout("echo '<html>not json'");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("bb-temper: host bigboy (fallback: controller reply is not valid host JSON)");
        assertThat(bound()).isEqualTo("bigboy");
    }

    @Test
    void auto_noQualifyingHost_fallsBackToBigboy() throws Exception {
        var run = checkout(hosts(bigboy().withState("DOWN"), c3().asRetired(), c4().withDisk(0)));

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("bb-temper: host bigboy (fallback: no host qualifies: ").contains("bigboy skip: state DOWN");
        assertThat(bound()).isEqualTo("bigboy");
    }

    /// Without the seam the fetch is one ssh to bigboy that curls the controller with the read token.
    @Test
    void auto_theRealFetch_isOneSshToBigboy_curlingTheHostsEndpoint() throws Exception {
        var run = bb(env(), "checkout", "t3-x");

        assertThat(run.rc()).as(run.err()).isZero();
        var first = sshCalls().getFirst();
        assertThat(first).contains(BIGBOY);
        assertThat(first.getLast()).isEqualTo("curl -sf -m 5 -H \"Authorization: Bearer $(cat ~/.config/temper/read-token)\" http://localhost:7070/api/v1/hosts");
        assertThat(run.err()).contains("fallback: controller unreachable");
    }

    // ---- explicit BB_HOST ------------------------------------------------------------------------------------------
    @Test
    void explicitHost_makesNoControllerCall_andIsBound() throws Exception {
        var marker = dir.resolve("called");

        var run = checkout("touch " + marker + "; " + hosts(c4()), "BB_HOST", "cloudbb-3");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(marker).doesNotExist();
        assertThat(run.err()).doesNotContain("bb-temper: host");
        assertThat(sshCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).contains(C3).doesNotContain("http://localhost:7070/api/v1/hosts"));
        assertThat(bound()).isEqualTo("cloudbb-3");
    }

    @Test
    void explicitHost_theWordAuto_isAutomatic() throws Exception {
        var run = checkout(hosts(bigboy(), c3(), c4()), "BB_HOST", "auto");

        assertPicked(run, "cloudbb-4", C4);
    }

    @Test
    void anUnknownBbHost_isRefusedWithUsageExit() throws Exception {
        var run = checkout(hosts(c4()), "BB_HOST", "nowhere");

        assertThat(run.rc()).isEqualTo(2);
        assertThat(sshCalls()).isEmpty();
    }

    // ---- the host config ------------------------------------------------------------------------------------------
    /// The host facts live in ~/.config/bb-temper/hosts, never in the script: without the file nothing runs and nothing is guessed.
    @Test
    void noHostConfig_isExit78_beforeAnySsh() throws Exception {
        Files.delete(home.resolve(".config/bb-temper/hosts"));

        var run = checkout(hosts(bigboy(), c3(), c4()), "BB_HOST", "bigboy");

        assertThat(run.rc()).isEqualTo(78);
        assertThat(run.err()).contains("no host config");
        assertThat(sshCalls()).isEmpty();
        assertThat(binding()).doesNotExist();
    }

    /// Names, addresses and the auto set all come from the config: a host the script has never heard of is reachable by name,
    /// the first auto line is the fallback (home) host, and an explicit-only line is never picked.
    @Test
    void theConfig_definesTheHosts_theHomeHost_andTheAutoSet() throws Exception {
        Files.writeString(home.resolve(".config/bb-temper/hosts"),
                          "key ~/.ssh/k\nhost lab builder@192.0.2.20 - - auto\nhost cloudbb-4 " + C4 + " - - explicit\n");

        var explicit = checkout(hosts(c4()), "BB_HOST", "lab");

        assertThat(explicit.rc()).as(explicit.err()).isZero();
        assertThat(sshCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).contains("builder@192.0.2.20", "-i", home + "/.ssh/k"));
        Files.delete(binding());
        Files.delete(dir.resolve("ssh.args"));

        var auto = checkout(hosts(H.of("lab", 8, 16), c4()));

        assertPicked(auto, "lab", "builder@192.0.2.20");
        Files.delete(binding());

        var fallback = checkout("false");

        assertThat(fallback.rc()).as(fallback.err()).isZero();
        assertThat(fallback.err()).contains("bb-temper: host lab (fallback: controller unreachable)");
        assertThat(bound()).isEqualTo("lab");
    }

    @Test
    void aMalformedHostLine_isExit78_naming_theLine() throws Exception {
        Files.writeString(home.resolve(".config/bb-temper/hosts"), "key ~/.ssh/k\nhost bigboy " + BIGBOY + " " + BIGBOY + " - auto\n");

        var run = checkout(hosts(bigboy()), "BB_HOST", "bigboy");

        assertThat(run.rc()).isEqualTo(78);
        assertThat(run.err()).contains("line 2: a jump and a known_hosts file go together");
        assertThat(sshCalls()).isEmpty();
    }

    /// A hosts file saved with CRLF line ends parses exactly like the LF one: the \r is never part of a field.
    @Test
    void aCrlfHostConfig_parsesLikeTheLfOne() throws Exception {
        Files.writeString(home.resolve(".config/bb-temper/hosts"), "key ~/.ssh/k\r\nhost lab builder@192.0.2.20 - - auto\r\n");

        var run = checkout(hosts(bigboy()), "BB_HOST", "lab");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(sshCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).contains("builder@192.0.2.20", "-i", home + "/.ssh/k"));
    }

    /// Two `key` lines are malformed even when they name the same path: exit 78 naming the second line.
    @Test
    void aRepeatedKeyLine_isExit78_naming_theLine() throws Exception {
        Files.writeString(home.resolve(".config/bb-temper/hosts"),
                          "key ~/.ssh/k\nkey ~/.ssh/k\nhost bigboy " + BIGBOY + " - - auto\n");

        var run = checkout(hosts(bigboy()), "BB_HOST", "bigboy");

        assertThat(run.rc()).isEqualTo(78);
        assertThat(run.err()).contains("line 2: key is listed twice");
        assertThat(sshCalls()).isEmpty();
    }

    @Test
    void aFailedCheckout_leavesNoBinding() throws Exception {
        Files.writeString(dir.resolve("ssh.rc"), "1\n");

        var run = checkout(hosts(bigboy(), c3(), c4()));

        assertThat(run.rc()).isEqualTo(3);
        assertThat(binding()).doesNotExist();
    }

    // ---- binding -------------------------------------------------------------------------------------------------
    private void bind(String key) throws IOException {
        Files.createDirectories(binding().getParent());
        Files.writeString(binding(), key + "\n");
    }

    @Test
    void laterVerbs_followTheBinding() throws Exception {
        assertPicked(checkout(hosts(bigboy(), c3(), c4())), "cloudbb-4", C4);
        Files.deleteIfExists(dir.resolve("ssh.args"));
        var fake = MacScripts.executable(bin.resolve("fake-submit"), "printf '%s\\n' \"$1\" > \"$STUBS/submit.host\"\n");

        var build = bb(env("BB_SUBMIT", fake.toString()), "build", "t3-x");
        var report = bb(env(), "report", "t3-x");

        assertThat(build.rc()).as(build.err()).isZero();
        assertThat(Files.readString(dir.resolve("submit.host")).strip()).isEqualTo(C4);
        assertThat(report.rc()).as(report.err()).isZero();
        assertThat(sshCalls()).singleElement().satisfies(call -> assertThat(call).contains(C4));
    }

    @Test
    void laterVerbs_withoutABinding_areRefused() throws Exception {
        for (var args : List.of(List.of("build", "t3-x"), List.of("wait", "t3-x"), List.of("report", "t3-x"),
                                List.of("fetch", "t3-x", dir.resolve("out").toString()), List.of("rm", "t3-x"))) {
            var run = bb(env("BB_SUBMIT", "/bin/true"), args.toArray(String[]::new));

            assertThat(run.rc()).as(args.toString()).isEqualTo(3);
            assertThat(run.err()).as(args.toString()).contains("bb-temper: no host binding for t3-x; set BB_HOST");
        }
        assertThat(sshCalls()).isEmpty();
    }

    @Test
    void laterVerbs_withAConflictingBbHost_areRefused() throws Exception {
        bind("cloudbb-d1");
        for (var args : List.of(List.of("build", "t3-x"), List.of("wait", "t3-x"), List.of("report", "t3-x"),
                                List.of("fetch", "t3-x", dir.resolve("out").toString()), List.of("rm", "t3-x"))) {
            var run = bb(env("BB_SUBMIT", "/bin/true", "BB_HOST", "bigboy"), args.toArray(String[]::new));

            assertThat(run.rc()).as(args.toString()).isEqualTo(3);
            assertThat(run.err()).as(args.toString()).contains("t3-x is bound to cloudbb-d1, not bigboy");
        }
        assertThat(sshCalls()).isEmpty();
        assertThat(bound()).isEqualTo("cloudbb-d1");
    }

    @Test
    void laterVerbs_withTheSameExplicitHost_orNoBinding_runAsBefore() throws Exception {
        bind("cloudbb-d1");

        var same = bb(env("BB_HOST", "cloudbb-d1"), "report", "t3-x");
        Files.delete(binding());
        var legacy = bb(env("BB_HOST", "bigboy"), "report", "t3-x");

        assertThat(same.rc()).as(same.err()).isZero();
        assertThat(legacy.rc()).as(legacy.err()).isZero();
        assertThat(sshCalls()).hasSize(2);
        assertThat(sshCalls().get(0)).contains(D1);
        assertThat(sshCalls().get(1)).contains(BIGBOY);
    }

    @Test
    void rm_removesTheBinding_afterTheRemoteRm() throws Exception {
        bind("cloudbb-d1");

        var run = bb(env(), "rm", "t3-x");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(sshCalls()).hasSize(2);
        assertThat(sshCalls().get(0)).contains(D1, "BB_OWNER=t3 ~/tools/bb-queue release ~/work/co/t3-x");
        assertThat(sshCalls().get(0).stream().anyMatch(a -> a.contains("--kill"))).isFalse();
        assertThat(sshCalls().get(1)).contains(D1, "rm -rf ~/work/co/t3-x ~/work/co/t3-x.bundle");
        assertThat(binding()).doesNotExist();
    }

    /// The queue's refusal (a RUNNING ticket: exit 3) stops the rm: no delete is sent and the binding stays.
    @Test
    void rm_whenTheQueueRefuses_deletesNothing_andPassesItsExitCode() throws Exception {
        bind("cloudbb-d1");
        Files.writeString(dir.resolve("ssh.rc"), "3\n");

        var run = bb(env(), "rm", "t3-x");

        assertThat(run.rc()).isEqualTo(3);
        assertThat(sshCalls()).singleElement().satisfies(call -> assertThat(call).contains("BB_OWNER=t3 ~/tools/bb-queue release ~/work/co/t3-x"));
        assertThat(sshCalls().stream().flatMap(List::stream).anyMatch(a -> a.contains("rm -rf"))).isFalse();
        assertThat(binding()).exists();
    }

    @Test
    void rmKill_passesKillToTheRelease_thenDeletes() throws Exception {
        bind("cloudbb-d1");

        var run = bb(env(), "rm", "--kill", "t3-x");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(sshCalls()).hasSize(2);
        assertThat(sshCalls().get(0)).contains("BB_OWNER=t3 ~/tools/bb-queue release ~/work/co/t3-x --kill");
        assertThat(sshCalls().get(1)).contains("rm -rf ~/work/co/t3-x ~/work/co/t3-x.bundle");
        assertThat(binding()).doesNotExist();
    }

    @Test
    void rm_whenTheRemoteRmFails_keepsTheBinding() throws Exception {
        bind("cloudbb-d1");
        Files.writeString(dir.resolve("ssh.rc"), "1\n");

        var run = bb(env(), "rm", "t3-x");

        assertThat(run.rc()).isEqualTo(1);
        assertThat(binding()).exists();
    }

    /// A live binding makes a second `checkout` look on the bound host, so the existing checkout is refused there.
    @Test
    void checkout_withALiveBinding_looksOnTheBoundHost_notAFreshPick() throws Exception {
        bind("cloudbb-3");
        Files.writeString(dir.resolve("ssh.rc"), "1\n");

        var run = checkout(hosts(bigboy(), c3(), c4()));

        assertThat(run.rc()).isEqualTo(3);
        assertThat(run.err()).contains("exists on " + C3);
        assertThat(bound()).isEqualTo("cloudbb-3");
    }

    // ---- HOME with spaces, ssh failure, stale/live binding, lock ----------------------------------------------------------
    /// The key and known-hosts paths sit inside ssh -o values (ProxyCommand is re-read by a shell): a space in HOME must not split them.
    @Test
    void cloudHost_withASpaceInHome_quotesThePathsInsideTheSshOptions() throws Exception {
        var spaced = Files.createDirectories(dir.resolve("ho me"));
        TestHosts.write(spaced);

        var run = checkout(hosts(c4()), "HOME", spaced.toString(), "BB_HOST", "cloudbb-3");

        assertThat(run.rc()).as(run.err()).isZero();
        var args = sshCalls().get(0);
        assertThat(args).contains("UserKnownHostsFile=\"" + spaced + "/" + TestHosts.KNOWN + "\"");
        assertThat(args).anySatisfy(a -> assertThat(a).startsWith("ProxyCommand=ssh -i \"" + spaced + "/" + TestHosts.KEY + "\" "));
        assertThat(Files.readString(spaced.resolve(".temper/bb-bind/t3-x")).strip()).isEqualTo("cloudbb-3");
    }

    /// ssh's own failure (255) is reported as such, not as "exists".
    @Test
    void checkout_whenSshItselfFails_reportsTheConnectionFailure_notExists() throws Exception {
        Files.writeString(dir.resolve("ssh.rc"), "255\n");

        var run = checkout(hosts(bigboy(), c3(), c4()), "BB_HOST", "bigboy");

        assertThat(run.rc()).isEqualTo(255);
        assertThat(run.err()).contains("ssh to " + BIGBOY + " failed").doesNotContain("exists");
        assertThat(binding()).doesNotExist();
    }

    /// An explicit BB_HOST does not bypass the binding: the checkout is looked for on the BOUND host, and found there it is refused.
    @Test
    void checkout_explicitHost_withALiveBindingElsewhere_isRefused() throws Exception {
        bind("bigboy");
        Files.writeString(dir.resolve("ssh.rc"), "1\n");

        var run = checkout(hosts(c4()), "BB_HOST", "cloudbb-3");

        assertThat(run.rc()).isEqualTo(3);
        assertThat(run.err()).contains("t3-x exists on " + BIGBOY + "; rm it first");
        assertThat(sshCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).contains(BIGBOY));
        assertThat(bound()).isEqualTo("bigboy");
    }

    /// Absent on the bound host: the binding is stale, overwritten with a note.
    @Test
    void checkout_explicitHost_withAStaleBinding_overwritesItWithANote() throws Exception {
        bind("bigboy");

        var run = checkout(hosts(c4()), "BB_HOST", "cloudbb-3");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.err()).contains("stale binding t3-x -> bigboy");
        assertThat(sshCalls().get(0)).contains(BIGBOY);
        assertThat(bound()).isEqualTo("cloudbb-3");
    }

    /// A held lock means another checkout of the name is mid-pick: refused, nothing run, the other's lock left alone.
    @Test
    void checkout_withTheLockHeld_isRefused_andLeavesTheLock() throws Exception {
        var lock = Files.createDirectories(home.resolve(".temper/bb-bind/t3-x.lock"));

        var run = checkout(hosts(bigboy(), c3(), c4()));

        assertThat(run.rc()).isEqualTo(3);
        assertThat(run.err()).contains("another checkout of t3-x is running");
        assertThat(sshCalls()).isEmpty();
        assertThat(lock).isDirectory();
        assertThat(binding()).doesNotExist();
    }

    /// The lock is released when the checkout ends, on success and on refusal.
    @Test
    void checkout_releasesTheLock_onSuccessAndOnRefusal() throws Exception {
        var lock = home.resolve(".temper/bb-bind/t3-x.lock");

        assertThat(checkout(hosts(bigboy(), c3(), c4())).rc()).isZero();
        assertThat(lock).doesNotExist();

        Files.writeString(dir.resolve("ssh.rc"), "1\n");
        assertThat(checkout(hosts(bigboy(), c3(), c4())).rc()).isEqualTo(3);
        assertThat(lock).doesNotExist();
    }
}
