package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.pragmatica.temper.testsupport.HermeticProcess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// The memory floor of `ops/bb/bb-queue-lib.sh` (issue #53): pick() grants nothing while MemAvailable (BB_TEST_MEMINFO, a fake
/// /proc/meminfo) is below the floor (per-host file BB_TEST_MEM_FLOOR_FILE, else BB_MEM_FLOOR_GB, else 8 GiB). The load gate's
/// starvation override must not bypass the floor while a reactor runs, because granting into low memory beside a running reactor
/// reproduced the 2026-11-13 OOM kills; with no reactor running the oldest ticket goes after LOAD_STARVE and records mem_override=.
/// A reactor is a stand-in process whose command line matches BB_TEST_REACTOR_PATTERN, so the count is the real pgrep count.
///
/// Probes: drop mem_held from pick's gate -> [#belowTheFloor_holdsTheGrant_andAboveItGrants] red; let the memory override fire with a
/// reactor running -> [#oneReactorBelowTheFloor_neverGrants_evenPastLoadStarve] red; make an unreadable meminfo hold ->
/// [#anUnreadableOrMalformedMeminfo_neverHoldsTheQueue] red.
class BbMemFloorIT {
    private static final long GIB_KB = 1024L * 1024L;

    @TempDir
    Path dir;

    private final String marker = "memfloor-reactor-" + BbFixtures.RUN + "-" + UUID.randomUUID();
    private final List<Process> reactors = new ArrayList<>();
    private Map<String, String> env;
    private Path meminfo;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(BbFixtures.linux(), "bb-queue-lib.sh uses pgrep -c and flock");
        meminfo = dir.resolve("meminfo");
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
        env.put("BB_TEST_MEMINFO", meminfo.toString());
        env.put("BB_TEST_MEM_FLOOR_FILE",
                dir.resolve("mem-floor-gb").toString());
        env.put("BB_LOAD_STARVE_SECS", "1200");
        env.put("BB_LOAD_STARVE_REACTORS", "2");
        loadIs("0.00");
    }

    @AfterEach
    void tearDown() {
        reactors.forEach(Process::destroyForcibly);
    }

    @Test
    void belowTheFloor_holdsTheGrant_andAboveItGrants() throws Exception {
        var id = ticket("fresh", 0);

        memAvailableKb(5 * GIB_KB);
        assertThat(pick()).isEmpty();
        assertThat(field(id, "mem_override")).isEmpty();
        memAvailableKb(9 * GIB_KB);
        assertThat(pick()).isEqualTo(id);
    }

    @Test
    void theHoldReason_isShownByBbQueue_nextToTheLoadGate() throws Exception {
        memAvailableKb(5 * GIB_KB);
        var held = bbQueue();

        assertThat(held).contains("MEMORY GATE (grant only while MemAvailable >= 8 GB; override: the oldest grantable ticket after 1200s,"
                                 + " only while 0 reactors run): held: MemAvailable 5.0 GB < floor 8 GB");
        var lines = held.lines().toList();
        var memory = indexStartingWith(lines, "MEMORY GATE (");

        assertThat(lines.get(memory - 1)).startsWith("LOAD GATE (");
        assertThat(lines.get(memory + 1)).startsWith("RUNNING (");
        assertThat(lines.get(memory + 2)).startsWith("TICKET ");
        memAvailableKb(9 * GIB_KB);
        assertThat(bbQueue()).contains("only while 0 reactors run): open");
    }

    @Test
    void zeroReactorsBelowTheFloor_grantTheOldestAfterLoadStarve_andRecordMemOverride() throws Exception {
        var young = ticket("young", 10);
        var old = ticket("old", 2000);

        memAvailableKb(5 * GIB_KB);
        assertThat(pick()).isEqualTo(old);
        assertThat(field(old, "mem_override")).matches("[0-9]+ waited=(19|20)[0-9]{2}s reactors=0 MemAvailable=5\\.0GB"
                                                      + " held: MemAvailable 5\\.0 GB < floor 8 GB");
        assertThat(field(old, "load_override")).as("load was fine, so no load override").isEmpty();
        assertThat(field(young, "mem_override")).isEmpty();
    }

    @Test
    void zeroReactorsBelowTheFloor_beforeLoadStarve_grantNothing() throws Exception {
        var id = ticket("recent", 600);

        memAvailableKb(5 * GIB_KB);
        assertThat(pick()).isEmpty();
        assertThat(field(id, "mem_override")).isEmpty();
    }

    @Test
    void oneReactorBelowTheFloor_neverGrants_evenPastLoadStarve() throws Exception {
        var id = ticket("old", 5000);

        reactor();
        memAvailableKb(5 * GIB_KB);
        assertThat(pick()).isEmpty();
        assertThat(field(id, "mem_override")).isEmpty();
        // the load override alone would allow this start (1 reactor < LOAD_STARVE_REACTORS 2); the memory floor still forbids it
        loadIs("999.00");
        assertThat(pick()).isEmpty();
        assertThat(field(id, "load_override")).isEmpty();
        assertThat(field(id, "mem_override")).isEmpty();
    }

    @Test
    void zeroReactorsWithBothGatesHeld_grantTheStarvedTicket_andRecordBothOverrides() throws Exception {
        var id = ticket("old", 2000);

        memAvailableKb(5 * GIB_KB);
        loadIs("999.00");
        assertThat(pick()).isEqualTo(id);
        assertThat(field(id, "load_override")).contains("reactors=0 held: load");
        assertThat(field(id, "mem_override")).contains("reactors=0 MemAvailable=5.0GB");
    }

    @Test
    void anUnreadableOrMalformedMeminfo_neverHoldsTheQueue() throws Exception {
        var id = ticket("fresh", 0);

        Files.deleteIfExists(meminfo);
        assertThat(pick()).as("meminfo absent").isEqualTo(id);
        for (var content : List.of("MemAvailable:    abc kB\n",
                                   "MemTotal:       32000000 kB\nMemFree:          100 kB\n",
                                   "MemAvailable:    100 MB\n",
                                   "MemAvailable:    -5 kB\n",
                                   "")) {
            Files.writeString(meminfo, content);
            assertThat(pick()).as("meminfo " + content).isEqualTo(id);
        }

        assertThat(field(id, "mem_override")).isEmpty();
        Files.createDirectories(dir.resolve("unreadable"));
        env.put("BB_TEST_MEMINFO",
                dir.resolve("unreadable").toString());
        assertThat(pick()).as("meminfo a directory").isEqualTo(id);
    }

    @Test
    void thePerHostFile_overridesTheEnvironment_whichOverridesTheDefault() throws Exception {
        var id = ticket("fresh", 0);
        var floorFile = dir.resolve("mem-floor-gb");

        memAvailableKb(5 * GIB_KB);
        Files.writeString(floorFile, "4\n");
        assertThat(pick()).as("file 4 < 5 GB available").isEqualTo(id);
        memAvailableKb(9 * GIB_KB);
        Files.writeString(floorFile, "16\n");
        env.put("BB_MEM_FLOOR_GB", "4");
        assertThat(pick()).as("file 16 beats env 4").isEmpty();
        assertThat(bbQueue()).contains("MemAvailable >= 16 GB").contains("held: MemAvailable 9.0 GB < floor 16 GB");
        Files.writeString(floorFile, "not a number\n");
        assertThat(pick()).as("malformed file: env 4 applies").isEqualTo(id);
        env.put("BB_MEM_FLOOR_GB", "12");
        assertThat(pick()).as("malformed file: env 12 applies").isEmpty();
        env.put("BB_MEM_FLOOR_GB", "0");
        assertThat(pick()).as("env 0 is not a floor: default 8 applies").isEqualTo(id);
        env.remove("BB_MEM_FLOOR_GB");
        Files.delete(floorFile);
        memAvailableKb(7 * GIB_KB);
        assertThat(pick()).as("no file, no env: default 8").isEmpty();
    }

    @Test
    void withMemoryFine_theLoadGateBehavesAsBefore() throws Exception {
        memAvailableKb(64 * GIB_KB);
        loadIs("999.00");
        var fresh = ticket("fresh", 0);

        assertThat(pick()).as("load held, not starved").isEmpty();
        var old = ticket("old", 2000);

        assertThat(pick()).as("load held, starved, 0 reactors < 2").isEqualTo(old);
        assertThat(field(old, "load_override")).matches("[0-9]+ waited=(19|20)[0-9]{2}s reactors=0 held: load .*");
        assertThat(field(old, "mem_override")).isEmpty();
        assertThat(field(fresh, "load_override")).isEmpty();
        reactor();
        env.put("BB_LOAD_STARVE_REACTORS", "1");
        Files.delete(dir.resolve("q/waiting").resolve(old));
        var older = ticket("older", 3000);

        assertThat(pick()).as("load held, starved, 1 reactor not < 1").isEmpty();
        assertThat(field(older, "load_override")).isEmpty();
        loadIs("0.00");
        assertThat(pick()).as("both gates open: the head of the order").isEqualTo(older);
    }

    private String pick() throws Exception {
        var run = lib("pick");

        assertThat(run.rc()).as(run.stderr()).isZero();

        return run.stdout()
                  .strip();
    }

    private String count() throws Exception {
        return lib("reactors").stdout()
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

    /// A waiting ticket enqueued `ageSecs` ago; its id carries this JVM's pid (BbFixtures.RUN).
    private String ticket(String name, long ageSecs) throws IOException {
        var id = name + "-" + BbFixtures.RUN;
        var created = (System.currentTimeMillis() / 1000 - ageSecs) * 1_000_000_000L;
        var waiting = Files.createDirectories(dir.resolve("q/waiting"));

        Files.writeString(waiting.resolve(id),
                          "owner=memfloor\nsha=0000000\ncreated=" + created
                         + "\nco=" + dir.resolve("co-" + name)
                         + "\nargs=-B install\n");

        return id;
    }

    private String field(String id, String key) throws IOException {
        var prefix = key + "=";

        return Files.readAllLines(dir.resolve("q/waiting").resolve(id))
                    .stream()
                    .filter(line -> line.startsWith(prefix))
                    .map(line -> line.substring(prefix.length()))
                    .reduce((first, second) -> second)
                    .orElse("");
    }

    private void memAvailableKb(long kb) throws IOException {
        Files.writeString(meminfo,
                          "MemTotal:       32000000 kB\nMemFree:          100000 kB\nMemAvailable:   " + kb + " kB\n");
    }

    private void loadIs(String load5) throws IOException {
        env.put("BB_TEST_LOADAVG",
                Files.writeString(dir.resolve("loadavg"), load5 + " " + load5 + " " + load5 + " 1/100 1\n").toString());
    }

    /// A stand-in reactor: a shell whose command line holds the marker, so `pgrep -fc $PAT` counts exactly it.
    private void reactor() throws Exception {
        reactors.add(HermeticProcess.builder("bash", "-c", "sleep 600; : " + marker).start());
        var deadline = System.currentTimeMillis() + 10_000;

        while (!count().equals(Integer.toString(reactors.size())) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(count()).as("stand-in reactors counted by pgrep").isEqualTo(Integer.toString(reactors.size()));
    }

    private static int indexStartingWith(List<String> lines, String prefix) throws IOException {
        for (var i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }

        throw new IOException("no line starts with " + prefix);
    }
}
