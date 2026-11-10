package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// `ops/bb/bb-build` run for real: the queue root is `BB_TEST_QUEUE_ROOT`, maven a `BB_TEST_MVN` stub, and
/// `BB_MAX_REACTORS=0` keeps the ticket in `waiting/` so the file can be read. The waiters are killed by recorded pid.
/// Old callers are compared with the bigboy file (vendored verbatim as the resource bb/bb-build.original).
///
/// Probes (C10): treat `--detached` as maven args -> [#detachedReader_isWrittenWithoutTheFlagInArgs_andDedupsWithAPlainBuild] red;
/// refuse a build without the new variables -> [#oldStyleCall_writesTheLegacyLines_plusOutputOnly] red; write a partial session
/// triple or refuse a malformed value -> [#malformedOptionalValues_neverBlockTheBuild_andNeverReachTheTicket] red; print the
/// fields in the bb-queue listing -> [#queueListing_isByteIdentical_forATicketCarryingEveryNewField] red.
class BbBuildFieldsIT {
    private static final Path BB_BUILD = BbFixtures.OPS_BB.resolve("bb-build");
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");
    private static final List<String> NEW_KEYS = List.of("session_pid", "session_start", "session_label", "agent", "lane", "pr", "output",
                                                         "detached_reader");

    @TempDir
    Path dir;

    private final List<String> tickets = new ArrayList<>();

    @BeforeEach
    void linuxOnly() {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
    }

    @AfterEach
    void killWaiters() throws Exception {
        for (var id : tickets) {
            BbFixtures.kill(BbFixtures.waiters(id));
        }
    }

    private Path checkout(String name) throws Exception {
        var co = Files.createDirectories(dir.resolve("work").resolve("t3b-" + name));
        Files.writeString(co.resolve(".gitignore"), ".bb/\n");
        for (var step : List.of(List.of("init", "-q"), List.of("add", "."), List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));
            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }
        return co;
    }

    private Map<String, String> env(Path queue, Map<String, String> extra) throws IOException {
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho stubbed \"$@\"\n");
        var env = new HashMap<String, String>();
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "t3b");
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES", dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES", dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_MAX_REACTORS", "0");
        env.putAll(extra);
        return env;
    }

    private BbFixtures.Outcome bb(Path script, Path queue, Map<String, String> extra, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(script.toString());
        command.addAll(List.of(args));
        var outcome = BbFixtures.run(command, env(queue, extra), null, null);
        var matcher = QUEUED.matcher(outcome.stdout());
        if (matcher.find()) {
            tickets.add(matcher.group(1));
        }
        return outcome;
    }

    private List<String> ticket(Path queue, BbFixtures.Outcome outcome) throws IOException {
        var matcher = QUEUED.matcher(outcome.stdout());
        assertThat(matcher.find()).as(outcome.stdout() + outcome.stderr()).isTrue();
        return Files.readAllLines(queue.resolve("waiting").resolve(matcher.group(1))).stream()
                    .filter(line -> !line.startsWith("blocked_since="))
                    .toList();
    }

    private static List<String> keys(List<String> lines) {
        return lines.stream().map(line -> line.substring(0, line.indexOf('='))).toList();
    }

    private static String value(List<String> lines, String key) {
        return lines.stream().filter(line -> line.startsWith(key + "=")).map(line -> line.substring(key.length() + 1)).reduce((a, b) -> b).orElse(null);
    }

    private Path original() throws Exception {
        var orig = dir.resolve("orig");
        BbFixtures.executable(orig.resolve("bb-build"), new String(BbFixtures.originalBbBuild(), java.nio.charset.StandardCharsets.UTF_8));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), orig.resolve("bb-queue-lib.sh"));
        return orig.resolve("bb-build");
    }

    @Test
    void oldStyleCall_writesTheLegacyLines_plusOutputOnly() throws Exception {
        var co = checkout("old");
        var legacyQueue = dir.resolve("q-legacy");
        var newQueue = dir.resolve("q-new");

        var legacy = ticket(legacyQueue, bb(original(), legacyQueue, Map.of(), co.toString(), "-B", "clean", "install"));
        var current = ticket(newQueue, bb(BB_BUILD, newQueue, Map.of(), co.toString(), "-B", "clean", "install"));

        assertThat(keys(legacy)).containsExactly("id", "owner", "co", "sha", "key", "cap", "limit", "created", "group", "group_reason", "args");
        assertThat(keys(current)).containsExactly("id", "owner", "co", "sha", "key", "cap", "limit", "created", "group", "group_reason", "output", "args");
        assertThat(value(current, "output")).isEqualTo(co + "/.bb/build.log");
        var volatileKeys = List.of("id=", "created=");
        assertThat(current.stream().filter(line -> !line.startsWith("output=")).filter(line -> volatileKeys.stream().noneMatch(line::startsWith)).toList())
            .isEqualTo(legacy.stream().filter(line -> volatileKeys.stream().noneMatch(line::startsWith)).toList());
    }

    /// T3.7 (B half): a ticket written by the bigboy bb-build is parsed by bb-tickets with exactly its own fields, and the same call
    /// through the new bb-build differs by the `output` field only.
    @Test
    void bbTickets_parsesATicketOfTheOriginalBbBuild_andTheNewOneAlike() throws Exception {
        var co = checkout("tkt");
        var legacyQueue = dir.resolve("q-legacy");
        var newQueue = dir.resolve("q-new");
        var legacy = ticket(legacyQueue, bb(original(), legacyQueue, Map.of(), co.toString(), "-B", "test"));
        var current = ticket(newQueue, bb(BB_BUILD, newQueue, Map.of(), co.toString(), "-B", "test"));

        var legacyJson = BbFixtures.run(List.of("bash", BbFixtures.OPS_BB.resolve("bb-tickets").toString()), env(legacyQueue, Map.of()), null, null);
        var newJson = BbFixtures.run(List.of("bash", BbFixtures.OPS_BB.resolve("bb-tickets").toString()), env(newQueue, Map.of()), null, null);

        assertThat(legacyJson.rc()).as(legacyJson.stderr()).isZero();
        var legacyLine = legacyJson.stdout().lines().filter(l -> l.contains("\"ticket\"")).findFirst().orElseThrow();
        var newLine = newJson.stdout().lines().filter(l -> l.contains("\"ticket\"")).findFirst().orElseThrow();
        assertThat(legacyLine).contains("\"dir\":\"waiting\"", "\"owner\":\"t3b\"", "\"dropped\":[]").doesNotContain("\"output\"");
        assertThat(keys(legacy)).doesNotContain("output");
        assertThat(newLine).contains("\"output\":\"" + co + "/.bb/build.log\"");
        assertThat(newJson.stdout()).contains("\"count\":1,");
        assertThat(current).hasSize(legacy.size() + 1);
    }

    @Test
    void everyField_isWritten_afterGroupReason_andBeforeArgs() throws Exception {
        var co = checkout("all");
        var queue = dir.resolve("q");
        var env = Map.of("BB_SESSION_PID", "37444", "BB_SESSION_START", "1793667998", "BB_SESSION_LABEL", "CTO", "BB_AGENT", "t3wpb",
                         "BB_LANE", "wpb", "BB_PR", "71");

        var outcome = bb(BB_BUILD, queue, env, "--detached", "--reader", "lead1", co.toString(), "-B", "clean", "install");
        var lines = ticket(queue, outcome);

        assertThat(outcome.rc()).as(outcome.stderr()).isZero();
        assertThat(keys(lines)).containsExactly("id", "owner", "co", "sha", "key", "cap", "limit", "created", "group", "group_reason", "session_pid",
                                                "session_start", "session_label", "agent", "lane", "pr", "output", "detached_reader", "args");
        assertThat(lines).contains("session_pid=37444", "session_start=1793667998", "session_label=CTO", "agent=t3wpb", "lane=wpb", "pr=71",
                                   "output=" + co + "/.bb/build.log", "detached_reader=lead1");
        assertThat(outcome.stderr()).isEmpty();
    }

    @Test
    void detachedReader_isWrittenWithoutTheFlagInArgs_andDedupsWithAPlainBuild() throws Exception {
        var co = checkout("det");
        var detachedQueue = dir.resolve("q-detached");
        var plainQueue = dir.resolve("q-plain");

        var detached = ticket(detachedQueue, bb(BB_BUILD, detachedQueue, Map.of(), "--detached", "--reader", "lead1", co.toString(), "-B", "test"));
        var plain = ticket(plainQueue, bb(BB_BUILD, plainQueue, Map.of(), co.toString(), "-B", "test"));

        assertThat(value(detached, "detached_reader")).isEqualTo("lead1");
        assertThat(value(detached, "args")).isEqualTo("-B test ").isEqualTo(value(plain, "args"));
        assertThat(value(detached, "key")).isNotBlank().isEqualTo(value(plain, "key"));
        assertThat(value(plain, "detached_reader")).isNull();
        assertThat(Files.readString(co.resolve(".bb/build.log"))).contains("queued ticket");
    }

    @Test
    void detached_isRefused_underQueue0_withoutReader_andWithABadLabel() throws Exception {
        var co = checkout("ref");
        var queue = dir.resolve("q");

        var noQueue = bb(BB_BUILD, queue, Map.of("BB_QUEUE", "0"), "--detached", "--reader", "lead1", co.toString());
        var noReader = bb(BB_BUILD, queue, Map.of(), "--detached", co.toString());
        var bareReader = bb(BB_BUILD, queue, Map.of(), "--detached", "--reader");
        var badLabel = bb(BB_BUILD, queue, Map.of(), "--detached", "--reader", "a b", co.toString());
        var badLabel2 = bb(BB_BUILD, queue, Map.of(), "--detached", "--reader", "x/y", co.toString());

        assertThat(noQueue.rc()).as(noQueue.stderr()).isEqualTo(2);
        assertThat(noQueue.stderr()).contains("--detached needs the queue");
        assertThat(List.of(noReader.rc(), bareReader.rc(), badLabel.rc(), badLabel2.rc())).containsOnly(2);
        assertThat(Files.exists(queue.resolve("waiting")) ? Files.list(queue.resolve("waiting")).count() : 0).isZero();
        assertThat(co.resolve(".bb")).doesNotExist();
    }

    @Test
    void malformedOptionalValues_neverBlockTheBuild_andNeverReachTheTicket() throws Exception {
        var co = checkout("bad");

        var agent = dir.resolve("q1");
        var spaced = bb(BB_BUILD, agent, Map.of("BB_AGENT", "a b"), co.toString());
        var spacedTicket = ticket(agent, spaced);
        assertThat(spaced.rc()).isZero();
        assertThat(spaced.stderr()).contains("bb-build: ignoring BB_AGENT: not ");
        assertThat(keys(spacedTicket)).doesNotContain("agent");

        var partial = dir.resolve("q2");
        var noStart = bb(BB_BUILD, partial, Map.of("BB_SESSION_PID", "37444", "BB_SESSION_LABEL", "CTO"), co.toString());
        var partialTicket = ticket(partial, noStart);
        assertThat(noStart.rc()).isZero();
        assertThat(keys(partialTicket)).doesNotContain("session_pid", "session_start", "session_label");

        var triple = dir.resolve("q3");
        var badLabel = bb(BB_BUILD, triple, Map.of("BB_SESSION_PID", "37444", "BB_SESSION_START", "1793667998", "BB_SESSION_LABEL", "bad label"), co.toString());
        var tripleTicket = ticket(triple, badLabel);
        assertThat(badLabel.rc()).isZero();
        assertThat(badLabel.stderr()).contains("ignoring BB_SESSION_LABEL");
        assertThat(keys(tripleTicket)).doesNotContain("session_pid", "session_start", "session_label");

        var shortStart = dir.resolve("q4");
        var badStart = bb(BB_BUILD, shortStart, Map.of("BB_SESSION_PID", "0", "BB_SESSION_START", "123", "BB_SESSION_LABEL", "CTO", "BB_PR", "0"), co.toString());
        var shortTicket = ticket(shortStart, badStart);
        assertThat(badStart.stderr()).contains("ignoring BB_SESSION_PID").contains("ignoring BB_SESSION_START").contains("ignoring BB_PR");
        assertThat(keys(shortTicket)).doesNotContain("session_pid", "session_start", "session_label", "pr");

        var injected = dir.resolve("q5");
        var newline = bb(BB_BUILD, injected, Map.of("BB_LANE", "x\nagent=evil"), co.toString());
        var injectedTicket = ticket(injected, newline);
        assertThat(newline.rc()).isZero();
        assertThat(newline.stderr()).contains("ignoring BB_LANE");
        assertThat(keys(injectedTicket)).doesNotContain("lane", "agent");
    }

    @Test
    void queueListing_isByteIdentical_forATicketCarryingEveryNewField() throws Exception {
        var co = checkout("lst");
        var queue = dir.resolve("q");
        var env = Map.of("BB_SESSION_PID", "37444", "BB_SESSION_START", "1793667998", "BB_SESSION_LABEL", "CTO", "BB_AGENT", "t3wpb",
                         "BB_LANE", "wpb", "BB_PR", "71");
        var made = bb(BB_BUILD, queue, env, "--detached", "--reader", "lead1", co.toString(), "-B", "test");
        var withFields = ticket(queue, made);
        var id = value(withFields, "id");
        BbFixtures.kill(BbFixtures.waiters(id));

        var tools = dir.resolve("tools");
        BbFixtures.executable(tools.resolve("bb-queue"), Files.readString(BbFixtures.OPS_BB.resolve("bb-queue")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));

        // a non-numeric `created` prints `?` as the age, so the two listings cannot differ by a minute rolling over
        var plainQueue = dir.resolve("q-plain");
        var fixedQueue = dir.resolve("q-fixed");
        for (var q : List.of(plainQueue, fixedQueue)) {
            Files.createDirectories(q.resolve("waiting"));
            Files.createDirectories(q.resolve("running"));
            Files.createDirectories(q.resolve("done"));
        }
        var fixed = withFields.stream().map(line -> line.startsWith("created=") ? "created=x" : line).toList();
        Files.write(fixedQueue.resolve("waiting").resolve(id), fixed);
        Files.write(plainQueue.resolve("waiting").resolve(id), fixed.stream().filter(line -> !NEW_KEYS.contains(line.substring(0, line.indexOf('=')))).toList());

        var listingWith = BbFixtures.run(List.of(tools.resolve("bb-queue").toString()), env(fixedQueue, Map.of()), null, null);
        var listingWithout = BbFixtures.run(List.of(tools.resolve("bb-queue").toString()), env(plainQueue, Map.of()), null, null);

        assertThat(listingWith.rc()).as(listingWith.stderr()).isZero();
        assertThat(listingWith.stdout()).contains(id).isEqualTo(listingWithout.stdout());
        assertThat(listingWith.stdout()).doesNotContain("t3wpb").doesNotContain("lead1").doesNotContain("37444");
    }
}
