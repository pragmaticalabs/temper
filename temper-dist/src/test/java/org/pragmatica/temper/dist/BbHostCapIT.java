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


/// The per-host reactor cap of `ops/bb/bb-queue-lib.sh` (issue #56): pick() grants a ticket only while running reactors <
/// min(the ticket's cap=, the host cap). The host cap is the first positive-integer line of the per-host file
/// (BB_TEST_MAX_REACTORS_FILE, really ~/work/max-reactors), else 3. It is a HARD bound: neither the load nor the memory starvation
/// override passes it, and backfill still grants a ticket behind a head that its own smaller cap blocks.
/// A reactor is a stand-in process recorded in the LAUNCH LEDGER (the cap's input); its command line also matches BB_TEST_REACTOR_PATTERN, the observation.
///
/// The override path needs no check of its own: it runs only when the loop found a candidate, i.e. while reactors < the host cap.
///
/// Probes: drop the min with the host cap in pick's loop -> [#noFile_capsTheHostAtThree_evenForATicketThatAllowsFive] and
/// [#theHostCap_isNotPassedByTheLoadStarvationOverride] red; read only the default (ignore the file) ->
/// [#aFileOfOne_capsTheHostAtOne] red; accept 0 as a cap -> [#aMalformedFile_meansThree] red; drop the HOST CAP line from bb-queue ->
/// [#bbQueue_showsTheHostCap_besideTheMemoryGate] red; allow a value above 999 or beyond INT64 ->
/// [#aMalformedFile_meansThree] and [#aValueBeyondInt64_isMalformed_andDoesNotWedgeTheQueue] red; `head -1` -> `tail -1` ->
/// [#ofTwoValidLines_theFirstWins_and999IsTheLargestValue] red; the RUNNING line shows a fixed cap 5 -> [#bbQueue_showsTheHostCap_besideTheMemoryGate] red.
class BbHostCapIT {
    @TempDir
    Path dir;

    private final String marker = "hostcap-reactor-" + BbFixtures.RUN + "-" + UUID.randomUUID();
    private final List<Process> reactors = new ArrayList<>();
    private Map<String, String> env;
    private Path capFile;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(BbFixtures.linux(), "bb-queue-lib.sh uses pgrep -c and flock");
        capFile = dir.resolve("max-reactors");
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
                Files.writeString(dir.resolve("meminfo"), "MemAvailable:   67108864 kB\n").toString());
        env.put("BB_TEST_PS",
                Files.writeString(dir.resolve("ps"), "").toString());
        env.put("BB_TEST_MAX_REACTORS_FILE", capFile.toString());
        env.put("BB_LOAD_STARVE_SECS", "1200");
        env.put("BB_LOAD_STARVE_REACTORS", "2");
        loadIs("0.00");
    }

    @AfterEach
    void tearDown() {
        reactors.forEach(Process::destroyForcibly);
    }

    @Test
    void noFile_capsTheHostAtThree_evenForATicketThatAllowsFive() throws Exception {
        var id = ticket("five", 0, 5);

        reactor();
        reactor();
        assertThat(pick()).as("2 reactors < min(5, 3)").isEqualTo(id);
        reactor();
        assertThat(pick()).as("3 reactors = the default host cap").isEmpty();
        assertThat(hostCap()).isEqualTo("3");
    }

    @Test
    void aFileOfOne_capsTheHostAtOne() throws Exception {
        var id = ticket("five", 0, 5);

        Files.writeString(capFile, "  1  \n");
        assertThat(pick()).as("0 reactors < 1").isEqualTo(id);
        reactor();
        assertThat(pick()).as("1 reactor = host cap 1").isEmpty();
        assertThat(hostCap()).isEqualTo("1");
    }

    @Test
    void aFileOfThree_withATicketCapOfTwo_theSmallerBinds() throws Exception {
        var two = ticket("two", 0, 2);

        Files.writeString(capFile, "3\n");
        reactor();
        assertThat(pick()).isEqualTo(two);
        reactor();
        assertThat(pick()).as("2 reactors = the ticket's own cap 2 < host cap 3").isEmpty();
    }

    @Test
    void aMalformedFile_meansThree() throws Exception {
        var id = ticket("five", 0, 5);

        reactor();
        reactor();
        reactor();
        for (var content : List.of("abc\n",
                                   "0\n",
                                   "-1\n",
                                   "2.5\n",
                                   "",
                                   "\n",
                                   "1 2\n",
                                   "07\n",
                                   "1000\n",
                                   "9999\n",
                                   "99999999999999999999\n",
                                   "9223372036854775808\n")) {
            Files.writeString(capFile, content);
            assertThat(hostCap()).as("file '" + content + "'").isEqualTo("3");
            assertThat(pick()).as("file '" + content + "': 3 reactors = cap 3").isEmpty();
        }

        Files.writeString(capFile, "junk\n4\n");
        assertThat(hostCap()).as("the first valid line counts").isEqualTo("4");
        assertThat(pick()).isEqualTo(id);
        Files.createDirectories(dir.resolve("unreadable"));
        env.put("BB_TEST_MAX_REACTORS_FILE",
                dir.resolve("unreadable").toString());
        assertThat(hostCap()).as("file a directory").isEqualTo("3");
        assertThat(pick()).isEmpty();
    }

    @Test
    void ofTwoValidLines_theFirstWins_and999IsTheLargestValue() throws Exception {
        Files.writeString(capFile, "2\n5\n");
        assertThat(hostCap()).as("first of two valid lines").isEqualTo("2");
        Files.writeString(capFile, "bad\n7\n2\n");
        assertThat(hostCap()).as("first valid of three").isEqualTo("7");
        Files.writeString(capFile, "999\n");
        assertThat(hostCap()).isEqualTo("999");
        Files.writeString(capFile, "1000\n999\n");
        assertThat(hostCap()).as("1000 is malformed, so the next valid line counts").isEqualTo("999");
    }

    @Test
    void aValueBeyondInt64_isMalformed_andDoesNotWedgeTheQueue() throws Exception {
        var id = ticket("five", 0, 5);

        Files.writeString(capFile, "99999999999999999999\n");
        var run = lib("pick");

        assertThat(run.stderr()).as("no 'integer expression expected'").isEmpty();
        assertThat(run.stdout().strip()).as("0 reactors < the default cap 3: granted").isEqualTo(id);
    }

    @Test
    void theHostCap_isNotPassedByTheLoadStarvationOverride() throws Exception {
        var old = ticket("old", 5000, 5);

        env.put("BB_LOAD_STARVE_REACTORS", "5");
        loadIs("999.00");
        reactor();
        Files.writeString(capFile, "2\n");
        assertThat(pick(old)).as("control: 1 reactor < host cap 2, starved past LOAD_STARVE").isEqualTo(old);
        assertThat(field(old, "load_override")).contains("launches=1 held: load");
        Files.delete(dir.resolve("q/waiting").resolve(old));
        var older = ticket("older", 6000, 5);

        Files.writeString(capFile, "1\n");
        assertThat(pick(older)).as("1 reactor = host cap 1: the override must not pass it").isEmpty();
        assertThat(field(older, "load_override")).isEmpty();
        loadIs("0.00");
        assertThat(pick(older)).as("load open, host cap still reached").isEmpty();
    }

    @Test
    void aHostCapOfOne_stillLetsTheMemoryOverrideStartTheFirstTicket_andHoldsTheNext() throws Exception {
        var old = ticket("old", 5000, 5);

        Files.writeString(capFile, "1\n");
        Files.writeString(dir.resolve("meminfo"), "MemAvailable:   1048576 kB\n");
        assertThat(pick(old)).as("0 reactors, nothing running, starved: the memory override starts it").isEqualTo(old);
        assertThat(field(old, "mem_override")).contains("launches=0");
        reactor();
        Files.delete(dir.resolve("q/waiting").resolve(old));
        var next = ticket("next", 6000, 5);

        Files.writeString(dir.resolve("meminfo"), "MemAvailable:   67108864 kB\n");
        assertThat(pick(next)).as("memory fine, 1 reactor = host cap 1").isEmpty();
    }

    @Test
    void backfill_stillGrantsBehindAHeadBlockedByItsOwnSmallerCap_withinTheHostCap() throws Exception {
        var head = ticket("head", 100, 1);
        var behind = ticket("behind", 50, 5);

        Files.writeString(capFile, "2\n");
        reactor();
        assertThat(pick()).as("head waits for an empty host; behind fits under min(5, 2)").isEqualTo(behind);
        assertThat(field(head, "blocked_since")).isNotEmpty();
        reactor();
        assertThat(pick()).as("2 reactors = host cap 2: nothing").isEmpty();
    }

    @Test
    void bbQueue_showsTheHostCap_besideTheMemoryGate() throws Exception {
        var shown = bbQueue();
        var lines = shown.lines().toList();
        var cap = indexStartingWith(lines, "HOST CAP (");

        assertThat(lines.get(cap)).isEqualTo("HOST CAP (grant only while queue launches < min(the ticket's cap, this host's cap);"
                                            + " ~/work/max-reactors, default 3; no override passes it): 3");
        assertThat(lines.get(cap - 1)).startsWith("MEMORY GATE (");
        assertThat(lines.get(cap + 1)).startsWith("RUNNING (");
        assertThat(lines.get(cap + 2)).startsWith("TICKET ");
        assertThat(running(shown)).as("default host cap 3 binds the default ticket cap 5")
                  .contains("; cap 3 (5 bounded by the host cap 3) unless a ticket sets a lower one;");
        Files.writeString(capFile, "2\n");
        shown = bbQueue();
        assertThat(shown).contains("no override passes it): 2\n");
        assertThat(running(shown)).contains("; cap 2 (5 bounded by the host cap 2) unless");
        Files.writeString(capFile, "8\n");
        assertThat(running(bbQueue())).as("a host cap above 5 leaves the default ticket cap 5")
                  .contains("; cap 5 (5 bounded by the host cap 8) unless");
    }

    private static String running(String listing) {
        return listing.lines()
                      .filter(l -> l.startsWith("RUNNING ("))
                      .findFirst()
                      .orElseThrow();
    }

    private String pick() throws Exception {
        return pick("");
    }

    private String pick(String caller) throws Exception {
        var run = lib("pick \"$(ledger_count)\" " + caller);

        assertThat(run.rc()).as(run.stderr()).isZero();

        return run.stdout()
                  .strip();
    }

    private String hostCap() throws Exception {
        return lib("host_cap").stdout()
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

    /// A waiting ticket enqueued `ageSecs` ago with its own cap; its id carries this JVM's pid (BbFixtures.RUN).
    private String ticket(String name, long ageSecs, int cap) throws IOException {
        var id = name + "-" + BbFixtures.RUN;
        var created = (System.currentTimeMillis() / 1000 - ageSecs) * 1_000_000_000L;
        var waiting = Files.createDirectories(dir.resolve("q/waiting"));

        Files.writeString(waiting.resolve(id),
                          "owner=hostcap\nsha=0000000\ncap=" + cap
                         + "\ncreated=" + created
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

    private void loadIs(String load5) throws IOException {
        env.put("BB_TEST_LOADAVG",
                Files.writeString(dir.resolve("loadavg"), load5 + " " + load5 + " " + load5 + " 1/100 1\n").toString());
    }

    /// A stand-in launch: a shell whose command line holds the marker (so the `reactors` observation counts it) and that is recorded
    /// in the LAUNCH LEDGER, the cap's only input.
    private void reactor() throws Exception {
        var launch = BbFixtures.process("bash", "-c", "sleep 600; : " + marker).start();

        reactors.add(launch);
        BbFixtures.recordLaunch(dir.resolve("q"), "standin-" + reactors.size(), launch.pid());
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
