package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// `ops/bb/bb-co` (#135): a pragmatica checkout on the auto host with the fewest WAITING tickets, against an `ssh` stub first on `PATH`.
/// Every host is a directory under `hosts/<name>` that is that host's HOME: a fake queue (`work/queue/waiting|running`), `work/max-reactors`,
/// a `loadavg` file and a stub `tools/bb-checkout`. The stub runs the remote command locally with that HOME, so the REAL remote probe code
/// reads the fake queue. A host listed in `down.<name>` fails every ssh with 255; one in `hang.<name>` never answers.
/// The config is bigboy, cloudbb-9, cloudbb-10 (auto) and cloudbb-d1 (explicit), all behind documentation addresses.
///
/// Probe: replace the pick with "first configured host" -> [#aSeededQueue_landsOnTheHostWithFewestWaiting_notOnTheBestLoadedOne] and every
/// other pick row red; drop `waiting` from the sort key -> the same row red (bigboy has the worse running/cap and load there on purpose).
class BbCoIT {
    private static final String BB_ADDR = "builder@192.0.2.10";
    private static final String C9_ADDR = "builder@198.51.100.9";
    private static final String C10_ADDR = "builder@198.51.100.10";
    private static final String D1_ADDR = "builder@198.51.100.11";
    private static final List<String> NAMES = List.of("bigboy", "cloudbb-9", "cloudbb-10", "cloudbb-d1");
    private static final String CONFIG = """
            key ~/.ssh/id_test
            host bigboy     %s  -  -  auto
            host cloudbb-9  %s  %s  ~/.config/bb-temper/known_hosts  auto
            host cloudbb-10 %s  %s  ~/.config/bb-temper/known_hosts  auto
            host cloudbb-d1 %s  %s  ~/.config/bb-temper/known_hosts  explicit
            """.formatted(BB_ADDR, C9_ADDR, BB_ADDR, C10_ADDR, BB_ADDR, D1_ADDR, BB_ADDR);

    @TempDir
    Path dir;

    private Path home;
    private Path bin;

    @BeforeEach
    void world() throws IOException {
        home = Files.createDirectories(dir.resolve("home"));
        bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(Files.createDirectories(home.resolve(".config/bb-temper")).resolve("hosts"), CONFIG);
        // the host is the argument before the command; calls log "<host>: <command>" (path probes `true` are counted apart)
        MacScripts.executable(bin.resolve("ssh"),
                              """
                              n=$#; i=0; host=
                              for a in "$@"; do i=$((i + 1)); [ "$i" -eq $((n - 1)) ] && host=$a; done
                              case $host in
                                *@192.0.2.10) h=bigboy ;; *@198.51.100.9) h=cloudbb-9 ;; *@198.51.100.10) h=cloudbb-10 ;; *@198.51.100.11) h=cloudbb-d1 ;;
                                *) echo "stub: unknown host $host" >&2; exit 99 ;;
                              esac
                              [ ! -e "$STUBS/down.$h" ] || exit 255
                              [ "${@: -1}" = true ] && { echo "$h" >> "$STUBS/path-probe.log"; exit 0; }
                              printf '%s: %s\\n' "$h" "${@: -1}" >> "$STUBS/ssh.log"
                              [ ! -e "$STUBS/hang.$h" ] || exec sleep 30
                              HOME=$STUBS/hosts/$h BB_TEST_LOADAVG=$STUBS/hosts/$h/loadavg BB_TEST_NPROC=$(cat "$STUBS/hosts/$h/cores") exec bash -c "${@: -1}"
                              """);
        for (var n : NAMES) {
            host(n).cores(8).load(0.1);
            var tool = MacScripts.executable(dir.resolve("hosts/" + n + "/tools/bb-checkout"),
                                             "echo \"bb-checkout on " + n + ": $* owner=$BB_OWNER\"\n"
                                             + "[ ! -f \"$STUBS/co.rc\" ] || exit \"$(cat \"$STUBS/co.rc\")\"\n"
                                             + "mkdir -p \"$HOME/work/co/$1\"\n");

            assertThat(tool).isExecutable();
        }
    }

    /// One fake host: its queue, cap file, load and core count.
    private final class Host {
        private final Path root;
        private final String name;

        Host(String name) {
            this.name = name;
            this.root = dir.resolve("hosts/" + name);
        }

        Host waiting(int n) throws IOException {
            return tickets("waiting", n);
        }

        Host running(int n) throws IOException {
            return tickets("running", n);
        }

        private Host tickets(String state, int n) throws IOException {
            var d = Files.createDirectories(root.resolve("work/queue/" + state));

            try (var old = Files.list(d)) {
                for (var f : (Iterable<Path>) old::iterator) {
                    Files.delete(f);
                }
            }
            for (var i = 1; i <= n; i++) {
                Files.writeString(d.resolve(Integer.toString(1000 + i)), "co=/x\n");
            }
            return this;
        }

        Host cap(int hostCap) throws IOException {
            Files.writeString(Files.createDirectories(root.resolve("work")).resolve("max-reactors"), hostCap + "\n");
            return this;
        }

        Host load(double load5) throws IOException {
            Files.writeString(Files.createDirectories(root).resolve("loadavg"), "9.9 " + load5 + " 9.9 1/1 1\n");
            return this;
        }

        Host cores(int n) throws IOException {
            Files.writeString(Files.createDirectories(root).resolve("cores"), n + "\n");
            return this;
        }

        Host down() throws IOException {
            Files.writeString(dir.resolve("down." + name), "");
            return this;
        }

        Host hangs() throws IOException {
            Files.writeString(dir.resolve("hang." + name), "");
            return this;
        }

        Host withCheckout(String co) throws IOException {
            Files.createDirectories(root.resolve("work/co/" + co));
            return this;
        }

        Path co(String co) {
            return root.resolve("work/co/" + co);
        }
    }

    private Host host(String name) {
        return new Host(name);
    }

    private Map<String, String> env(String... more) {
        var env = new LinkedHashMap<>(MacScripts.env(Map.of("HOME", home.toString(), "PATH", bin + ":/usr/bin:/bin", "STUBS", dir.toString(), "BB_OWNER", "t3")));

        for (var i = 0; i < more.length; i += 2) {
            env.put(more[i], more[i + 1]);
        }
        return env;
    }

    private MacScripts.Result co(Map<String, String> env, String... args) throws Exception {
        var command = new ArrayList<>(List.of("bash", Path.of("..", "ops", "bb", "bb-co").toAbsolutePath().normalize().toString()));

        command.addAll(List.of(args));
        return MacScripts.run(command, env, dir);
    }

    private MacScripts.Result co(String... args) throws Exception {
        return co(env(), args);
    }

    private List<String> sshCalls() throws IOException {
        var f = dir.resolve("ssh.log");

        return Files.exists(f) ? Files.readAllLines(f).stream().filter(l -> l.matches("^[a-z0-9-]+: .*")).toList() : List.of();
    }

    /// The remote calls that are bb-checkout runs (a probe is a multi-line command, a checkout is one line).
    private List<String> checkoutCalls() throws IOException {
        return sshCalls().stream().filter(l -> l.contains("tools/bb-checkout t3-")).toList();
    }

    private boolean anySsh() {
        return Files.exists(dir.resolve("ssh.log")) || Files.exists(dir.resolve("path-probe.log"));
    }

    @Test
    void aSeededQueue_landsOnTheHostWithFewestWaiting_notOnTheBestLoadedOne() throws Exception {
        // cloudbb-9: 9 waiting but idle and cold; bigboy: 0 waiting but busy (running 2/3) and hot. Ranking by waiting alone sends it to bigboy.
        host("cloudbb-9").waiting(9).running(0).load(0.0);
        host("bigboy").waiting(0).running(2).load(8.0);
        host("cloudbb-10").waiting(4).running(0).load(0.0);

        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).as("stdout is exactly the HOST line").isEqualTo("HOST=bigboy\n");
        assertThat(checkoutCalls()).hasSize(1).first().asString().isEqualTo("bigboy: BB_OWNER=t3 ~/tools/bb-checkout t3-x main");
        assertThat(host("bigboy").co("t3-x")).isDirectory();
        assertThat(host("cloudbb-9").co("t3-x")).doesNotExist();
        assertThat(run.err()).contains("picked bigboy").contains("cloudbb-9 waiting=9");
    }

    @Test
    void theFirstConfiguredHostIsNotTheDefault_theLeastWaitingOneWinsWhereverItIsInTheConfig() throws Exception {
        host("bigboy").waiting(3);
        host("cloudbb-9").waiting(2);
        host("cloudbb-10").waiting(1);

        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=cloudbb-10\n");
    }

    @Test
    void equalWaiting_tieGoesByRunningOverCap_notByRawRunning() throws Exception {
        // bigboy 2/3 = .67; cloudbb-9 2/5 = .40 (more tickets running, more room); cloudbb-10 1/2 = .50. Raw running would pick cloudbb-10.
        host("bigboy").waiting(2).running(2).cap(3);
        host("cloudbb-9").waiting(2).running(2).cap(5);
        host("cloudbb-10").waiting(2).running(1).cap(2);

        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=cloudbb-9\n");
    }

    @Test
    void equalWaitingAndRunningOverCap_tieGoesByLoadPerCore_notByRawLoad() throws Exception {
        // cloudbb-9: load 3 on 4 cores = .75; cloudbb-10: load 4 on 8 cores = .50 (the higher raw load); bigboy .90.
        host("bigboy").waiting(1).running(1).load(7.2).cores(8);
        host("cloudbb-9").waiting(1).running(1).load(3.0).cores(4);
        host("cloudbb-10").waiting(1).running(1).load(4.0).cores(8);

        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=cloudbb-10\n");
    }

    @Test
    void anAbsoluteTie_goesByConfigOrder() throws Exception {
        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=bigboy\n");
    }

    @Test
    void anExplicitHost_isNeverAutoPicked_evenWhenItIsTheEmptiest() throws Exception {
        host("bigboy").waiting(5);
        host("cloudbb-9").waiting(5);
        host("cloudbb-10").waiting(5);
        host("cloudbb-d1").waiting(0);

        var run = co("--host", "auto", "t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=bigboy\n");
        assertThat(sshCalls()).noneMatch(l -> l.startsWith("cloudbb-d1:"));
    }

    @Test
    void aHostThatDoesNotAnswer_isSkipped_theNextBestIsUsed() throws Exception {
        host("bigboy").waiting(0).down();
        host("cloudbb-9").waiting(3);
        host("cloudbb-10").waiting(2);

        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=cloudbb-10\n");
        assertThat(run.err()).contains("SKIP bigboy skip:");
    }

    @Test
    void aHostThatNeverAnswers_isSkippedAtTheProbeWall_notWaitedForever() throws Exception {
        host("bigboy").waiting(0).hangs();
        host("cloudbb-9").waiting(3);
        host("cloudbb-10").waiting(5);
        var t0 = System.nanoTime();

        var run = co(env("BB_CO_PROBE_WALL", "2"), "t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=cloudbb-9\n");
        assertThat((System.nanoTime() - t0) / 1_000_000_000L).as("seconds: the wall, not the hung ssh's 30").isLessThan(20);
    }

    @Test
    void aHostWithAMalformedReply_orWithoutBbCheckout_isSkipped() throws Exception {
        host("bigboy").waiting(0);
        Files.delete(dir.resolve("hosts/bigboy/tools/bb-checkout"));
        host("cloudbb-9").waiting(0);
        Files.writeString(dir.resolve("hosts/cloudbb-9/loadavg"), "garbage\n");
        host("cloudbb-10").waiting(7);

        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=cloudbb-10\n");
        assertThat(run.err()).contains("SKIP bigboy skip:", "SKIP cloudbb-9 skip:");
    }

    @Test
    void whenNoHostAnswers_exitIsNonZero_noCheckoutIsCalled_noHostLine() throws Exception {
        host("bigboy").down();
        host("cloudbb-9").down();
        host("cloudbb-10").down();

        var run = co("t3-x", "main");

        assertThat(run.rc()).isEqualTo(69);
        assertThat(run.out()).isEmpty();
        assertThat(run.err()).contains("no auto host answered");
        assertThat(checkoutCalls()).isEmpty();
        assertThat(dir.resolve("hosts")).isDirectory();
        for (var n : NAMES) {
            assertThat(host(n).co("t3-x")).doesNotExist();
        }
    }

    @Test
    void aNamedHost_makesNoQueueProbe_andChecksOutThere() throws Exception {
        host("bigboy").waiting(0);
        host("cloudbb-10").waiting(50);

        var run = co("--host", "cloudbb-10", "t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=cloudbb-10\n");
        assertThat(sshCalls()).as("the one remote call is the checkout; no queue probe").hasSize(1)
                              .first().asString().isEqualTo("cloudbb-10: BB_OWNER=t3 ~/tools/bb-checkout t3-x main");
        assertThat(host("cloudbb-10").co("t3-x")).isDirectory();
    }

    @Test
    void aNamedExplicitHost_isUsable() throws Exception {
        var run = co("--host", "cloudbb-d1", "t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=cloudbb-d1\n");
    }

    @Test
    void anUnknownNamedHost_isRefusedBeforeAnySsh() throws Exception {
        var run = co("--host", "nowhere", "t3-x", "main");

        assertThat(run.rc()).isEqualTo(2);
        assertThat(run.out()).isEmpty();
        assertThat(anySsh()).isFalse();
    }

    @Test
    void aBbCheckoutFailure_passesItsExitCodeOn_andPrintsNoHostLine() throws Exception {
        host("bigboy").waiting(0);
        host("cloudbb-9").waiting(1);
        host("cloudbb-10").waiting(1);
        Files.writeString(dir.resolve("co.rc"), "3\n");

        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isEqualTo(3);
        assertThat(run.out()).as("no HOST= line on failure").isEmpty();
        assertThat(run.err()).contains("bb-checkout on bigboy failed (rc 3)");
        assertThat(checkoutCalls()).as("never retried").hasSize(1);
    }

    @Test
    void aBbCheckoutFailure_ofAnotherCode_isPassedOnToo_underANamedHost() throws Exception {
        Files.writeString(dir.resolve("co.rc"), "5\n");

        var run = co("--host", "cloudbb-9", "t3-x", "nosuchref");

        assertThat(run.rc()).isEqualTo(5);
        assertThat(run.out()).isEmpty();
    }

    @Test
    void aNameThatExistsOnAnotherProbedHost_isRefused_oneCheckoutLivesOnOneHostOnly() throws Exception {
        host("bigboy").waiting(0);
        host("cloudbb-9").waiting(5).withCheckout("t3-x");
        host("cloudbb-10").waiting(5);

        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isEqualTo(3);
        assertThat(run.out()).isEmpty();
        assertThat(run.err()).contains("t3-x already exists on cloudbb-9");
        assertThat(checkoutCalls()).isEmpty();
        assertThat(host("bigboy").co("t3-x")).doesNotExist();
    }

    @Test
    void valuesWithShellActiveCharacters_areRefusedBeforeAnySsh() throws Exception {
        var bad = List.of(List.of("t3-x;touch-pwned", "main"),
                          List.of("t3-x$(id)", "main"),
                          List.of("t3-x`id`", "main"),
                          List.of("t3-x", "main;id"),
                          List.of("t3-x", "a b"),
                          List.of("t3-x", "$(id)"),
                          List.of("t3-x", "--upload-pack=x"),
                          List.of("t3-x", "m'ain"),
                          List.of("t3-x", "main\nid"),
                          List.of("-t3-x", "main"),
                          List.of("t3-x", "x&y"),
                          List.of("t3-x", "x|y"),
                          List.of("t3-x", "x>y"),
                          List.of("t3-x", "x*"),
                          List.of("other-x", "main"),
                          List.of("t3x", "main"));

        for (var pair : bad) {
            var run = co(pair.get(0), pair.get(1));

            assertThat(run.rc()).as(pair + " " + run.err()).isEqualTo(2);
            assertThat(run.out()).as(pair.toString()).isEmpty();
        }
        assertThat(anySsh()).as("nothing reached ssh").isFalse();
    }

    @Test
    void aMalformedOwner_andABadProbeWall_areRefusedBeforeAnySsh() throws Exception {
        assertThat(co(env("BB_OWNER", "t3;id"), "t3;id-x", "main").rc()).isEqualTo(2);
        assertThat(co(env("BB_OWNER", "t-3"), "t-3-x", "main").rc()).as("bb-checkout accepts [A-Za-z0-9_] owners only").isEqualTo(2);
        assertThat(co(env("BB_CO_PROBE_WALL", "0"), "t3-x", "main").rc()).isEqualTo(2);
        assertThat(co(env("BB_CO_PROBE_WALL", "9999"), "t3-x", "main").rc()).isEqualTo(2);
        assertThat(co("t3-x").rc()).isEqualTo(2);
        assertThat(co("--host").rc()).isEqualTo(2);
        assertThat(anySsh()).isFalse();
    }

    @Test
    void aMissingHostConfig_isExit78_beforeAnySsh() throws Exception {
        Files.delete(home.resolve(".config/bb-temper/hosts"));

        var run = co("t3-x", "main");

        assertThat(run.rc()).isEqualTo(78);
        assertThat(anySsh()).isFalse();
    }

    @Test
    void theProbeReadsTheCapFromTheHostFile_defaultThree_effectiveCapIsAtMostFive() throws Exception {
        // 3 running on a default host (cap 3, ratio 1.0) loses to 3 running on a host with host cap 9 (effective 5, ratio .6)
        host("bigboy").waiting(1).running(3);
        host("cloudbb-9").waiting(1).running(3).cap(9);
        host("cloudbb-10").waiting(1).running(3).cap(3);

        var run = co("t3-x", "main");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("HOST=cloudbb-9\n");
        assertThat(run.err()).contains("cloudbb-9 waiting=1 running=3/5").contains("bigboy waiting=1 running=3/3");
    }

    @Test
    void theProbeCountsOnlyNumericTicketFiles() throws Exception {
        host("bigboy").waiting(0);
        Files.writeString(Files.createDirectories(dir.resolve("hosts/bigboy/work/queue/waiting")).resolve("notes.tmp"), "x");
        host("cloudbb-9").waiting(1);
        host("cloudbb-10").waiting(1);

        var run = co("t3-x", "main");

        assertThat(run.out()).isEqualTo("HOST=bigboy\n");
        assertThat(run.err()).contains("bigboy waiting=0");
    }

    @Test
    void theHostConfigBlockAndThePathBlock_areCopiesOfBbTemperAndBbDrift() throws Exception {
        var ops = Path.of("..", "ops", "bb").toAbsolutePath().normalize();
        var co = Files.readString(ops.resolve("bb-co"));
        var temper = Files.readString(ops.resolve("bb-temper"));
        var drift = Files.readString(ops.resolve("bb-drift"));

        assertThat(hostBlock(co)).contains("load_hosts()").isEqualTo(hostBlock(temper)).isEqualTo(hostBlock(drift));
        assertThat(pathBlock(co)).contains("choose_path()", "setup_host()")
                                 .isEqualTo(pathBlock(temper).replace("bb-temper:", "bb-co:"));
    }

    private static String hostBlock(String script) {
        var from = script.indexOf("# ---- host config:");
        var to = script.indexOf("# ---- end of the shared host-config block");

        assertThat(from).isNotNegative();
        assertThat(to).isGreaterThan(from);
        return script.substring(from, to);
    }

    private static String pathBlock(String script) {
        var from = script.indexOf("# ---- path choice for a jumped host");
        var to = script.indexOf("\nrsh() {");

        assertThat(from).isNotNegative();
        assertThat(to).isGreaterThan(from);
        return script.substring(from, to);
    }
}
