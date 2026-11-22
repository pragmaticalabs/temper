package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// The memory floor of `ops/bb/bb-queue-lib.sh` counts off-queue JVM headroom (issue #56): for every java process that is neither a
/// reactor nor below one, max(0, Xmx - RSS) is subtracted from MemAvailable before the floor test, because MemAvailable misses heap
/// such a JVM can still claim (2026-11-18: three TLC JVMs at -Xmx 8+10+8G on a 30G host; suite forks killed with 137). The gate
/// tests read a fake process list (BB_TEST_PS, "pid ppid rss_kb args" lines); one test runs real JVMs through the real `ps`.
///
/// Probes: drop the headroom from mem_held's comparison -> [#anOffQueueJvmsUnclaimedHeap_closesTheGate] red; drop the descendant walk
/// (mark only the reactor itself) -> [#aReactorsOwnHeap_andItsForks_doNotCloseTheGate] red; count every java whatever its -Xmx unit
/// as bytes -> [#xmxUnits_lastOneWins_absentSkips_andRssAboveXmxIsZero] red; drop the real maven launcher pattern ->
/// [#aRealMavenLaunchersForks_areNeverOffQueue] red; read argv[0] as `java` only (no path) ->
/// [#realProcesses_theRealPsPass_findsTheOffQueueJvm_andSkipsTheReactorsChild] red; read -Xmx from words after the main class or `-jar` ->
/// [#onlyJvmOptions_areRead_notWordsAfterTheMainClassOrJar] red; list only the current user's processes (`ps -u $USER`) ->
/// [#theRealPsScan_coversEveryUser_notOnlyTheCurrentOne] red; drop the ledger pids from the reactor set ->
/// [#aLedgerLaunchsJava_andItsForks_areNeverOffQueue] red.
class BbOffQueueJvmIT {
    private static final long GIB_KB = 1024L * 1024L;

    @TempDir
    Path dir;

    private final String marker = "offq-reactor-" + BbFixtures.RUN + "-" + UUID.randomUUID();
    private final List<Process> started = new ArrayList<>();
    private final List<Long> detached = new ArrayList<>();
    private Map<String, String> env;
    private Path ps;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(BbFixtures.linux(), "bb-queue-lib.sh uses pgrep -c and flock");
        ps = dir.resolve("ps");
        env = new HashMap<>();
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_TEST_QUEUE_ROOT",
                dir.resolve("q").toString());
        env.put("BB_TEST_PRIORITIES",
                Files.writeString(dir.resolve("prio"), "").toString());
        env.put("BB_TEST_PORT_MODULES",
                Files.writeString(dir.resolve("pgm"), "").toString());
        env.put("BB_TEST_PG_SLOTS_FILE",
                Files.writeString(dir.resolve("slots"), "3\n").toString());
        env.put("BB_TEST_TICKET_LOG",
                dir.resolve("log.tsv").toString());
        env.put("BB_TEST_REACTOR_PATTERN",
                "[" + marker.charAt(0) + "]" + marker.substring(1));
        env.put("BB_TEST_MEMINFO",
                dir.resolve("meminfo").toString());
        env.put("BB_TEST_MEM_FLOOR_FILE",
                dir.resolve("mem-floor-gb").toString());
        env.put("BB_TEST_PS", ps.toString());
        env.put("BB_TEST_LOADAVG",
                Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/100 1\n").toString());
        Files.writeString(ps, "");
    }

    @AfterEach
    void tearDown() {
        started.forEach(Process::destroyForcibly);
        detached.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
    }

    @Test
    void anOffQueueJvmsUnclaimedHeap_closesTheGate() throws Exception {
        var id = ticket("fresh");

        memAvailableKb(20 * GIB_KB);
        assertThat(pick()).as("no JVMs: 20 GB >= 8").isEqualTo(id);
        processes("  100     1 1048576 /usr/lib/jvm/bin/java -Xmx16g -cp tla2tools.jar tlc2.TLC spec");
        assertThat(lib("offqueue_headroom_kb").stdout().strip()).isEqualTo(Long.toString(15 * GIB_KB));
        assertThat(pick()).as("20 GB - (16 - 1) GB = 5 GB < 8").isEmpty();
        assertThat(lib("mem_held").stdout()).isEqualTo("held: MemAvailable 20.0 GB - off-queue JVM headroom 15.0 GB = 5.0 GB"
                                                      + " < floor 8 GB\n");
        assertThat(bbQueue()).contains("only while 0 queue launches run): held: MemAvailable 20.0 GB - off-queue JVM headroom 15.0 GB");
        memAvailableKb(24 * GIB_KB);
        assertThat(pick()).as("24 - 15 = 9 GB >= 8").isEqualTo(id);
    }

    @Test
    void aReactorsOwnHeap_andItsForks_doNotCloseTheGate() throws Exception {
        var id = ticket("fresh");

        memAvailableKb(20 * GIB_KB);
        processes("  200     1 500000 /usr/bin/java -Xmx16g -classpath boot " + marker,
                  "  201   200 1048576 /usr/bin/java -Xmx16g surefirebooter.jar",
                  "  202   201 1000 /usr/bin/java -Xmx16g grandchild-fork",
                  "  300   999 1000 sh -c java-wrapper",
                  "  301   300 1000 java -Xmx2g sibling");
        assertThat(lib("offqueue_jvms").stdout()).as("only the JVM outside the reactor tree")
                  .isEqualTo("301 2096152 2097152 1000\n");
        assertThat(pick()).as("20 - ~2 GB = ~18 GB >= 8").isEqualTo(id);
    }

    @Test
    void aLedgerLaunchsJava_andItsForks_areNeverOffQueue() throws Exception {
        var launch = BbFixtures.process("sleep", "600").start();

        started.add(launch);
        var c = launch.pid();

        processes("  " + c + "     1 1000 bash bb-build-job-subshell",
                  "  500  " + c + " 500000 /usr/bin/java -Xmx16g -cp classes org.example.BbTestLaunch",
                  "  501   500 1000 /usr/bin/java -Xmx8g fork");
        assertThat(lib("offqueue_jvms").stdout()).as("not in the ledger: both JVMs are off-queue").hasLineCount(2);
        BbFixtures.recordLaunch(dir.resolve("q"), "t-ledger", c);
        assertThat(lib("offqueue_jvms").stdout()).as("a live ledger launch's java (no pattern matches it) is the queue's own")
                  .isEmpty();
    }

    @Test
    void aRealMavenLaunchersForks_areNeverOffQueue() throws Exception {
        processes("  400     1 500000 /usr/bin/java -classpath boot -Dclassworlds.conf=m2.conf org.codehaus.plexus.classworlds.launcher.Launcher install",
                  "  401   400 1000 /usr/bin/java -Xmx16g surefirebooter.jar");
        assertThat(lib("offqueue_jvms").stdout()).as("a real maven reactor is a reactor even under a test's own pattern")
                  .isEmpty();
    }

    @Test
    void xmxUnits_lastOneWins_absentSkips_andRssAboveXmxIsZero() throws Exception {
        processes("  10     1 0 java -Xmx2048k k",
                  "  11     1 0 java -Xmx3M m",
                  "  12     1 0 java -Xmx4G g",
                  "  13     1 0 java -Xmx1t t",
                  "  14     1 0 java -Xmx1073741824 bytes",
                  "  15     1 1048576 java -Xmx2g -Xmx3072m last-wins",
                  "  16     1 9000000 java -Xmx4g rss-above-xmx",
                  "  17     1 100 java -Xms1g no-xmx",
                  "  18     1 100 java -Xmxlots malformed",
                  "  19     1 100 /usr/bin/javac -Xmx9g not-a-jvm",
                  "  20     1 100 bash -c java -Xmx9g not-argv0",
                  "garbage line");
        assertThat(lib("offqueue_jvms").stdout()).isEqualTo("""
                                                            10 2048 2048 0
                                                            11 3072 3072 0
                                                            12 4194304 4194304 0
                                                            13 1073741824 1073741824 0
                                                            14 1048576 1048576 0
                                                            15 2097152 3145728 1048576
                                                            16 0 4194304 9000000
                                                            """);
    }

    @Test
    void onlyJvmOptions_areRead_notWordsAfterTheMainClassOrJar() throws Exception {
        processes("  30     1 100 java -Xmx2g -jar app.jar -Xmx50g",
                  "  31     1 100 java -Xmx2g -cp a.jar:b.jar my.Main -Xmx50g",
                  "  32     1 100 java -cp a.jar -Xmx3g my.Main",
                  "  33     1 100 java -Xmx4g -m some.module/my.Main -Xmx50g",
                  "  34     1 100 java -jar app.jar -Xmx50g",
                  "  35     1 100 java -Xms1g my.Main -Xmx50g",
                  "  36     1 100 java -p mods -Xmx5g --add-modules m.x my.Main",
                  "  37     1 100 java -Xmx6g --module=some.module -Xmx50g");
        assertThat(lib("offqueue_jvms").stdout()).as("an application argument is never a JVM option; an option's separate value is skipped")
                  .isEqualTo("""
                             30 2097052 2097152 100
                             31 2097052 2097152 100
                             32 3145628 3145728 100
                             33 4194204 4194304 100
                             36 5242780 5242880 100
                             37 6291356 6291456 100
                             """);
    }

    @Test
    void theRealPsScan_coversEveryUser_notOnlyTheCurrentOne() throws Exception {
        // a fake ps first on PATH records its arguments: an off-queue JVM (TLC) can belong to another user, so `-u $USER` must not appear
        var shim = BbFixtures.executable(dir.resolve("shim/ps"),
                                         "#!/bin/sh\necho \"$*\" > \"" + dir.resolve("ps-args")
                                        + "\"\necho '  300     1 1000 java -Xmx2g x'\n");

        env.put("BB_TEST_PS", "");
        env.put("PATH",
                shim.getParent() + ":" + System.getenv("PATH"));
        var listed = lib("offqueue_jvms").stdout();
        var args = Files.readString(dir.resolve("ps-args")).strip();

        assertThat(listed).as("the shim's line went through the real-ps branch").isEqualTo("300 2096152 2097152 1000\n");
        assertThat(args.split(" ")).as("ps " + args).contains("-e").doesNotContain("-u", "-U", "--user", "-x");
    }

    @Test
    void anUnreadableProcessList_addsNoHeadroom() throws Exception {
        var id = ticket("fresh");

        memAvailableKb(9 * GIB_KB);
        Files.delete(ps);
        assertThat(lib("offqueue_headroom_kb").stdout().strip()).isEqualTo("0");
        assertThat(pick()).isEqualTo(id);
    }

    @Test
    void realProcesses_theRealPsPass_findsTheOffQueueJvm_andSkipsTheReactorsChild() throws Exception {
        env.put("BB_TEST_PS", "");  // empty: the real ps
        var java = ProcessHandle.current().info().command().orElseThrow();
        var sleeper = Files.writeString(dir.resolve("Sleeper.java"),
                                        "class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(600_000); } }\n");
        // the off-queue JVM is detached (its parent exits), so it is not below this test JVM, which is itself a maven fork
        var launch = BbFixtures.run(List.of("bash",
                                            "-c",
                                            "\"$0\" -Xmx2g -XX:+UseSerialGC \"$1\" > /dev/null 2>&1 < /dev/null & echo $!",
                                            java,
                                            sleeper.toString()),
                                    Map.of("PATH", System.getenv("PATH")),
                                    dir,
                                    null);
        var offQueue = Long.parseLong(launch.stdout().strip());

        detached.add(offQueue);
        var reactor = BbFixtures.process("bash",
                                         "-c",
                                         "\"$0\" -Xmx3g -XX:+UseSerialGC \"$1\"; : " + marker,
                                         java,
                                         sleeper.toString())
                                .start();

        started.add(reactor);
        var child = awaitChild(reactor.pid());
        var deadline = System.currentTimeMillis() + 30_000;
        var listed = "";

        while (!listed.contains(offQueue + " ") && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            listed = lib("offqueue_jvms").stdout();
        }

        assertThat(listed.lines().filter(line -> line.startsWith(offQueue + " ")).toList()).as(listed)
                  .singleElement()
                  .satisfies(line -> assertThat(line.split(" ") [2]).isEqualTo("2097152"));
        assertThat(listed.lines().anyMatch(line -> line.startsWith(child + " "))).as("the reactor's own JVM child is not off-queue: " + listed)
                  .isFalse();
    }

    /// The pid of the first child of `parent` (the reactor's java), waiting up to 10 s for it.
    private static long awaitChild(long parent) throws Exception {
        var deadline = System.currentTimeMillis() + 10_000;

        while (System.currentTimeMillis() < deadline) {
            var child = ProcessHandle.of(parent).flatMap(handle -> handle.children()
                                                                         .findFirst());

            if (child.isPresent()) {
                return child.get()
                            .pid();
            }

            Thread.sleep(50);
        }

        throw new IOException("no child of " + parent);
    }

    private String pick() throws Exception {
        var run = lib("pick \"$(ledger_count)\"");

        assertThat(run.rc()).as(run.stderr()).isZero();

        return run.stdout()
                  .strip();
    }

    private BbFixtures.Outcome lib(String function) throws Exception {
        return BbFixtures.run(List.of("bash",
                                      "-c",
                                      ". \"$1\" && " + function,
                                      "bash",
                                      BbFixtures.OPS_BB.resolve("bb-queue-lib.sh").toString()),
                              env,
                              dir,
                              null);
    }

    private String bbQueue() throws Exception {
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-queue").toString(),
                                         "0"),
                                 env,
                                 dir,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();

        return run.stdout();
    }

    private String ticket(String name) throws IOException {
        var id = name + "-" + BbFixtures.RUN;
        var created = System.currentTimeMillis() / 1000 * 1_000_000_000L;
        var waiting = Files.createDirectories(dir.resolve("q/waiting"));

        Files.writeString(waiting.resolve(id),
                          "owner=offq\nsha=0000000\ncreated=" + created
                         + "\nco=" + dir.resolve("co-" + name)
                         + "\nargs=-B install\n");

        return id;
    }

    private void processes(String... lines) throws IOException {
        Files.writeString(ps, String.join("\n", lines) + "\n");
    }

    private void memAvailableKb(long kb) throws IOException {
        Files.writeString(dir.resolve("meminfo"),
                          "MemTotal:       32000000 kB\nMemFree:          100000 kB\nMemAvailable:   " + kb + " kB\n");
    }
}
