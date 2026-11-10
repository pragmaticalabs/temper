package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// `ops/bb/bb-tickets` on fixture queues, run for real. The clock is `BB_TICKETS_NOW`; a live waiter
/// is a `sleep` whose argv[0] is `bb-build __run <id>`, the text `waiter_alive` looks for.
///
/// Probes (C10): drop the trailer -> [#listing_ordersRunningThenWaitingThenRecentDone_withTheTrailerLast] red; report every waiter
/// `alive` -> [#waiter_isAliveOnlyForARunningWaiterProcess_andNullForDone] red; keep a value with a control byte ->
/// [#values_withControlBytes_orOver4096Bytes_orNonAscii_areDropped_andNamed] red.
class BbTicketsIT {
    private static final Path SCRIPT = BbFixtures.OPS_BB.resolve("bb-tickets");
    private static final long NOW = 2_000_000_000L;
    private static final Pattern TICKET = Pattern.compile("\"ticket\":\"([^\"]*)\"");

    @TempDir
    Path dir;

    private Path queue;
    private final List<Process> standIns = new ArrayList<>();

    @BeforeEach
    void fixtureQueue() throws IOException {
        assumeTrue(BbFixtures.linux(), "bb-tickets needs find -printf and pgrep -a");
        queue = dir.resolve("queue");
        for (var sub : List.of("running", "waiting", "done")) {
            Files.createDirectories(queue.resolve(sub));
        }
    }

    @AfterEach
    void killStandIns() {
        standIns.forEach(Process::destroyForcibly);
    }

    private Path ticket(String sub, String id, String content) throws IOException {
        return Files.writeString(queue.resolve(sub).resolve(id), content);
    }

    private Path ticketBytes(String sub, String id, byte[] content) throws IOException {
        return Files.write(queue.resolve(sub).resolve(id), content);
    }

    private BbFixtures.Outcome list(String... args) throws Exception {
        var env = new HashMap<String, String>();
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.resolve("home").toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TICKETS_NOW", Long.toString(NOW));
        var command = new ArrayList<String>(List.of("bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        return BbFixtures.run(command, env, null, null);
    }

    private static List<String> ids(BbFixtures.Outcome outcome) {
        return outcome.stdout().lines()
                      .map(TICKET::matcher)
                      .filter(java.util.regex.Matcher::find)
                      .map(matcher -> matcher.group(1))
                      .toList();
    }

    private static String line(BbFixtures.Outcome outcome, String id) {
        return outcome.stdout().lines().filter(l -> l.contains("\"ticket\":\"" + id + "\"")).findFirst().orElseThrow();
    }

    private void waiterFor(String id) throws Exception {
        var process = HermeticProcess.builder("bash", "-c", "exec -a \"bb-build __run " + id + "\" sleep 300").start();
        standIns.add(process);
        for (var i = 0; i < 100 && BbFixtures.waiters(id).isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(BbFixtures.waiters(id)).as("the stand-in waiter for " + id).hasSize(1);
    }

    @Test
    void listing_ordersRunningThenWaitingThenRecentDone_withTheTrailerLast() throws Exception {
        ticket("running", "r2", "id=r2\ncreated=200\n");
        ticket("running", "r1", "id=r1\ncreated=100\n");
        ticket("running", "rx", "id=rx\ncreated=abc\n");
        ticket("running", "ra", "id=ra\n");
        ticket("waiting", "w2", "id=w2\ncreated=1793000000000000002\n");
        ticket("waiting", "w1", "id=w1\ncreated=1793000000000000001\n");
        ticket("done", "dOld", "id=dOld\nended=" + (NOW - 86_401) + "\n");
        ticket("done", "dA", "id=dA\nended=" + (NOW - 50) + "\nstate=finished\nrc=0\n");
        ticket("done", "dB", "id=dB\nended=" + (NOW - 10) + "\n");
        var noEnded = ticket("done", "dM", "id=dM\nstate=abandoned\n");
        Files.setLastModifiedTime(noEnded, FileTime.fromMillis((NOW - 30) * 1000));
        var staleMtime = ticket("done", "dS", "id=dS\n");
        Files.setLastModifiedTime(staleMtime, FileTime.fromMillis((NOW - 90_000) * 1000));

        var out = list();

        assertThat(out.rc()).as(out.stderr()).isZero();
        assertThat(ids(out)).containsExactly("ra", "rx", "r1", "r2", "w1", "w2", "dB", "dM", "dA");
        var lines = out.stdout().lines().toList();
        assertThat(lines.getLast()).isEqualTo("{\"v\":1,\"end\":true,\"count\":9,\"queue\":\"present\",\"at\":" + NOW + "}");
        assertThat(lines).hasSize(10);
        assertThat(line(out, "dA")).isEqualTo("{\"v\":1,\"ticket\":\"dA\",\"dir\":\"done\",\"waiter\":null,"
                                              + "\"fields\":{\"id\":\"dA\",\"ended\":\"" + (NOW - 50) + "\",\"state\":\"finished\",\"rc\":\"0\"},\"dropped\":[]}");
    }

    @Test
    void waiter_isAliveOnlyForARunningWaiterProcess_andNullForDone() throws Exception {
        ticket("running", "live-1", "id=live-1\ncreated=1\n");
        ticket("running", "live-12", "id=live-12\ncreated=2\n");
        ticket("waiting", "dead-1", "id=dead-1\ncreated=3\n");
        ticket("done", "fin-1", "id=fin-1\nended=" + (NOW - 5) + "\n");
        waiterFor("live-1");

        var out = list();

        assertThat(line(out, "live-1")).contains("\"waiter\":\"alive\"");
        assertThat(line(out, "live-12")).as("an id that merely starts with a live id").contains("\"waiter\":\"gone\"");
        assertThat(line(out, "dead-1")).contains("\"waiter\":\"gone\"");
        assertThat(line(out, "fin-1")).contains("\"waiter\":null");
    }

    @Test
    void legacyTicket_hasItsFieldsListed_andNothingElse() throws Exception {
        ticket("waiting", "1793825000000000000-1234", String.join("\n", "id=1793825000000000000-1234", "owner=t2int2", "co=/home/builder/work/co/t2int2-full",
                                                                    "sha=8c8a4e6e22abd026b1b34c42e607165cb756b209", "key=191150ebff0b6487", "cap=5", "limit=0",
                                                                    "created=1793825000000000000", "group=1", "group_reason=forced by BB_PORT_GROUP=1",
                                                                    "args=-B clean install ", ""));

        var out = list();

        assertThat(line(out, "1793825000000000000-1234")).isEqualTo("{\"v\":1,\"ticket\":\"1793825000000000000-1234\",\"dir\":\"waiting\",\"waiter\":\"gone\","
                                                                     + "\"fields\":{\"id\":\"1793825000000000000-1234\",\"owner\":\"t2int2\",\"co\":\"/home/builder/work/co/t2int2-full\","
                                                                     + "\"sha\":\"8c8a4e6e22abd026b1b34c42e607165cb756b209\",\"key\":\"191150ebff0b6487\",\"cap\":\"5\",\"limit\":\"0\","
                                                                     + "\"created\":\"1793825000000000000\",\"group\":\"1\",\"group_reason\":\"forced by BB_PORT_GROUP=1\","
                                                                     + "\"args\":\"-B clean install \"},\"dropped\":[]}");
    }

    @Test
    void values_withControlBytes_orOver4096Bytes_orNonAscii_areDropped_andNamed() throws Exception {
        var content = new ByteArrayOutputStream();
        content.writeBytes("id=bad\nok=1\nok=2\ntabbed=a\tb\ncr=x\r\nhigh=caf".getBytes(StandardCharsets.UTF_8));
        content.writeBytes(new byte[] {(byte) 0xc3, (byte) 0xa9, '\n'});
        content.writeBytes(("long=" + "a".repeat(4097) + "\nedge=" + "b".repeat(4096) + "\nquote=say \"hi\" \\ done\n").getBytes(StandardCharsets.UTF_8));
        content.writeBytes("del=a\u007fb\nlatin=\u0001x\nnot a key=1\nUPPER=1\nlast=first\nlast=\u0002\n".getBytes(StandardCharsets.UTF_8));
        ticketBytes("running", "bad", content.toByteArray());

        var out = list();

        var json = line(out, "bad");
        assertThat(json).contains("\"ok\":\"2\"").doesNotContain("\"ok\":\"1\"");
        assertThat(json).contains("\"edge\":\"" + "b".repeat(4096) + "\"").contains("\"quote\":\"say \\\"hi\\\" \\\\ done\"");
        assertThat(json).contains("\"dropped\":[\"tabbed\",\"cr\",\"high\",\"long\",\"del\",\"latin\",\"last\"]");
        assertThat(json).doesNotContain("not a key").doesNotContain("UPPER").doesNotContain("\"last\":");
        assertThat(out.stdout().chars().allMatch(c -> c == '\n' || (c >= 0x20 && c < 0x7f))).as("pure printable ASCII on the wire").isTrue();
    }

    @Test
    void namesThatAreNoTicketId_areSkipped_andLinksAreNotFollowed() throws Exception {
        ticket("running", "ok.id_1-x", "id=ok\n");
        ticket("running", "has space", "id=a\n");
        ticket("running", "semi;colon", "id=b\n");
        ticket("running", "x".repeat(65), "id=c\n");
        ticket("running", "multi\nline", "id=d\n");
        ticket("running", "done\tx", "id=e\n");
        Files.createSymbolicLink(queue.resolve("running").resolve("link"), queue.resolve("running").resolve("ok.id_1-x"));
        Files.createDirectories(queue.resolve("running").resolve("subdir"));

        var out = list();

        assertThat(ids(out)).containsExactly("ok.id_1-x");
        assertThat(out.stdout()).contains("\"count\":1,");
    }

    @Test
    void capsAre500RunningAnd500Waiting_and200Done() throws Exception {
        for (var i = 0; i < 502; i++) {
            ticket("running", "r%04d".formatted(i), "id=r\ncreated=%d\n".formatted(1000 + i));
            ticket("waiting", "w%04d".formatted(i), "id=w\ncreated=%d\n".formatted(1000 + i));
        }
        for (var i = 0; i < 203; i++) {
            ticket("done", "d%04d".formatted(i), "id=d\nended=%d\n".formatted(NOW - 1000 + i));
        }

        var out = list();

        var ids = ids(out);
        assertThat(ids).hasSize(1200);
        assertThat(ids.stream().filter(id -> id.startsWith("r"))).hasSize(500).first().isEqualTo("r0000");
        assertThat(ids.stream().filter(id -> id.startsWith("w"))).hasSize(500);
        assertThat(ids.stream().filter(id -> id.startsWith("d"))).hasSize(200).first().isEqualTo("d0202");
        assertThat(out.stdout().lines().toList().getLast()).contains("\"count\":1200,");
    }

    @Test
    void absentQueue_isATrailerOnly_exit0_andNothingIsCreated() throws Exception {
        queue = dir.resolve("nowhere");

        var out = list();

        assertThat(out.rc()).isZero();
        assertThat(out.stdout()).isEqualTo("{\"v\":1,\"end\":true,\"count\":0,\"queue\":\"absent\",\"at\":" + NOW + "}\n");
        assertThat(queue).doesNotExist();
    }

    @Test
    void anyArgument_isExit64_withNothingOnStdout() throws Exception {
        ticket("running", "r1", "id=r1\n");

        var out = list("--all");

        assertThat(out.rc()).isEqualTo(64);
        assertThat(out.stdout()).isEmpty();
    }

    @Test
    void unreadableDirectory_isExit74_withNothingOnStdout() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root reads everything");
        ticket("running", "r1", "id=r1\n");
        ticket("done", "d1", "id=d1\nended=" + (NOW - 1) + "\n");
        var done = queue.resolve("done").toFile();
        assertThat(done.setReadable(false) && done.setExecutable(false)).isTrue();
        try {
            var out = list();

            assertThat(out.rc()).isEqualTo(74);
            assertThat(out.stdout()).isEmpty();
        } finally {
            done.setReadable(true);
            done.setExecutable(true);
        }
    }

    @Test
    void reading_changesNothing_noLock_noDirectory_noMtime() throws Exception {
        Files.delete(queue.resolve("done"));
        ticket("running", "r1", "id=r1\ncreated=1\n");

        var before = snapshot();
        var out = list();

        assertThat(out.rc()).isZero();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(queue.resolve("done")).doesNotExist();
        assertThat(queue.resolve(".lock")).doesNotExist();
    }

    private List<String> snapshot() throws IOException {
        try (Stream<Path> walk = Files.walk(queue)) {
            return walk.sorted()
                       .map(path -> {
                           try {
                               return path + " " + Files.getLastModifiedTime(path) + " " + (Files.isRegularFile(path) ? Files.size(path) : -1);
                           } catch (IOException e) {
                               throw new IllegalStateException(e);
                           }
                       })
                       .collect(Collectors.toList());
        }
    }

    @Test
    void sortKeys_putDoneNewestFirst_whateverTheNameOrder() throws Exception {
        IntStream.range(0, 5).forEach(i -> {
            try {
                ticket("done", "z" + i, "id=z\nended=" + (NOW - 100 + i * 7) + "\n");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        var out = list();

        assertThat(ids(out)).containsExactly("z4", "z3", "z2", "z1", "z0");
    }
}
