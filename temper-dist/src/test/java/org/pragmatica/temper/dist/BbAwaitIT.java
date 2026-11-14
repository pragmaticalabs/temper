package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.pragmatica.temper.testsupport.HermeticProcess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// `bb-await` (issue #48): the one bounded wait for agent lanes. It takes a ticket id, a path or a numeric pid, never a pattern, and
/// always ends by a deadline (exit 124). Real script against a fake queue directory (`BB_TEST_QUEUE_ROOT`), a stand-in waiter
/// `bash <dir>/bb-build __run <id>` so reap() leaves a running ticket alone, and real child processes for `--gone`.
///
/// Probes: remove the deadline check in bb-await -> [#aDeadline_endsTheWaitWithExit124_namingWhatWasAwaited] and
/// [#aTicketThatNeverFinishes_endsByTheDeadline] hang to their own bound and go red; replace `exit "$rc"` by `exit 0` -> the two
/// pass-through tests red; make the pid check accept any word (drop the digits pattern) -> [#aPatternOrANonNumericPid_isUsageExit2] red.
class BbAwaitIT {
    private static final String TICKET_DONE = "\nstate=finished\nstarted=100\nended=105\nrc=";

    @TempDir
    Path dir;

    private Path queue;
    private Path tools;
    private Path fake;
    private int outputs;
    private final List<Process> started = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "needs /proc, flock, pgrep and GNU tools");
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }

        tools = Files.createDirectories(dir.resolve("tools"));
        BbFixtures.executable(tools.resolve("bb-await"),
                              Files.readString(BbFixtures.OPS_BB.resolve("bb-await")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        fake = Files.createDirectories(dir.resolve("fake"));
        Files.writeString(fake.resolve("bb-build"), "#!/bin/bash\nsleep 600\n");
    }

    @AfterEach
    void cleanUp() {
        for (var process : started) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private Map<String, String> env() {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "aw");
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_AWAIT_INTERVAL", "0.2");

        return env;
    }

    private List<String> command(String... args) {
        var command = new ArrayList<>(List.of("bash",
                                              tools.resolve("bb-await").toString()));

        command.addAll(List.of(args));

        return command;
    }

    private BbFixtures.Outcome await(String... args) throws Exception {
        return BbFixtures.run(command(args), env(), null, null);
    }

    private record Running(Process process, Path out, Path err) {}

    /// bb-await in the background, its output in files (the test reads them after it exits).
    private Running awaitInBackground(String... args) throws IOException {
        var n = outputs++;
        var out = dir.resolve("out-" + n);
        var err = dir.resolve("err-" + n);
        var builder = HermeticProcess.builder(command(args), env());

        builder.redirectOutput(out.toFile()).redirectError(err.toFile());
        var process = builder.start();

        started.add(process);

        return new Running(process, out, err);
    }

    private static int exit(Running r, long seconds) throws Exception {
        assertThat(r.process().waitFor(seconds, TimeUnit.SECONDS)).as("bb-await ended within " + seconds + " s")
                  .isTrue();

        return r.process()
                .exitValue();
    }

    private Path ticket(String state, String id, String extra) throws IOException {
        return Files.writeString(queue.resolve(state).resolve(id),
                                 "id=" + id
                                + "\nowner=aw\nco=" + dir.resolve("co")
                                + "\ncreated=" + (System.currentTimeMillis() * 1_000_000L)
                                + "\ngroup=0\nargs=-B test \n" + extra);
    }

    private Process waiterFor(String id) throws IOException {
        var builder = HermeticProcess.builder("bash",
                                              fake.resolve("bb-build").toString(),
                                              "__run",
                                              id);

        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();

        started.add(process);

        return process;
    }

    private static String id() {
        return System.currentTimeMillis() + "-" + BbFixtures.RUN;
    }

    // ---- --ticket
    @Test
    void aFinishedTicketWithRc0_passesTheRcThrough() throws Exception {
        var id = id();

        ticket("done", id, TICKET_DONE + "0\n");
        var run = await("--ticket", id);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("ticket=" + id + " state=finished rc=0 ");
    }

    @Test
    void aFinishedTicketWithRc1_passesTheRcThrough() throws Exception {
        var id = id();

        ticket("done", id, TICKET_DONE + "1\n");
        var run = await("--ticket", id);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(1);
        assertThat(run.stdout()).contains("ticket=" + id + " state=finished rc=1 ");
    }

    @Test
    void aTicketThatIsNotFinished_hasItsOwnDistinctCode() throws Exception {
        var cancelled = id() + "1";
        var abandoned = id() + "2";
        var expired = id() + "3";
        var orphaned = id() + "4";
        var noRc = id() + "5";

        ticket("done", cancelled, "\nstate=cancelled\nended=105\n");
        ticket("done", abandoned, "\nstate=abandoned\nended=105\n");
        ticket("done", expired, "\nstate=expired\nended=105\n");
        ticket("done", orphaned, "\nstate=orphaned\nended=105\n");
        ticket("done", noRc, "\nstate=finished\nended=105\n");
        assertThat(await("--ticket", cancelled).rc()).as("cancelled").isEqualTo(9);
        assertThat(await("--ticket", abandoned).rc()).as("abandoned").isEqualTo(9);
        assertThat(await("--ticket", expired).rc()).as("expired").isEqualTo(5);
        assertThat(await("--ticket", orphaned).rc()).as("orphaned").isEqualTo(70);
        assertThat(await("--ticket", noRc).rc()).as("finished without a numeric rc").isEqualTo(70);
    }

    @Test
    void anUnknownTicket_isExit4_notAWaitForever() throws Exception {
        var run = await("--ticket", id());

        assertThat(run.rc()).isEqualTo(4);
        assertThat(run.stderr()).contains("unknown ticket");
    }

    @Test
    void aRunningTicket_isWaitedOnUntilItReachesDone_thenItsRcIsTheExit() throws Exception {
        var id = id();
        var running = ticket("running", id, "\nstarted=100\n");

        waiterFor(id);
        awaitWaiter(id);
        var wait = awaitInBackground("--ticket", id);

        assertThat(wait.process().waitFor(1500, TimeUnit.MILLISECONDS)).as("still waiting while the ticket runs")
                  .isFalse();
        Files.writeString(running, "state=finished\nended=105\nrc=3\n", java.nio.file.StandardOpenOption.APPEND);
        Files.move(running,
                   queue.resolve("done").resolve(id),
                   StandardCopyOption.ATOMIC_MOVE);
        assertThat(exit(wait, 20)).isEqualTo(3);
        assertThat(Files.readString(wait.out())).contains("ticket=" + id + " state=finished rc=3 ");
    }

    @Test
    void aTicketThatNeverFinishes_endsByTheDeadline() throws Exception {
        var id = id();

        ticket("running", id, "\nstarted=100\n");
        waiterFor(id);
        awaitWaiter(id);
        var t0 = System.nanoTime();
        var wait = awaitInBackground("--ticket", id, "--deadline", "2s");
        var rc = exit(wait, 15);
        var seconds = (System.nanoTime() - t0) / 1_000_000_000.0;

        assertThat(rc).as(Files.readString(wait.err())).isEqualTo(124);
        assertThat(Files.readString(wait.err())).contains("deadline 2s passed waiting for ticket " + id);
        assertThat(seconds).isLessThan(15.0);
    }

    // ---- --file, --gone, deadline
    @Test
    void aFile_isWaitedOnUntilItAppears() throws Exception {
        var target = dir.resolve("marker/DONE");
        var wait = awaitInBackground("--file", target.toString());

        assertThat(wait.process().waitFor(1, TimeUnit.SECONDS)).as("waiting while the path is absent").isFalse();
        Files.createDirectories(target.getParent());
        Files.writeString(target, "x");
        assertThat(exit(wait, 20)).isZero();
        assertThat(Files.readString(wait.out())).contains("awaited file=" + target + " present");
    }

    @Test
    void aProcess_isWaitedOnUntilItIsGone() throws Exception {
        var sleeper = HermeticProcess.builder("sleep", "600").start();

        started.add(sleeper);
        var wait = awaitInBackground("--gone",
                                     Long.toString(sleeper.pid()));

        assertThat(wait.process().waitFor(1, TimeUnit.SECONDS)).as("waiting while the process lives").isFalse();
        sleeper.destroyForcibly();
        sleeper.waitFor();
        assertThat(exit(wait, 20)).isZero();
        assertThat(Files.readString(wait.out())).contains("awaited pid=" + sleeper.pid() + " gone");
    }

    @Test
    void anAlreadyGoneProcess_endsAtOnce() throws Exception {
        var sleeper = HermeticProcess.builder("true").start();

        sleeper.waitFor();
        var run = await("--gone",
                        Long.toString(sleeper.pid()));

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
    }

    @Test
    void aDeadline_endsTheWaitWithExit124_namingWhatWasAwaited() throws Exception {
        var never = dir.resolve("never-written");
        var t0 = System.nanoTime();
        var wait = awaitInBackground("--file", never.toString(), "--deadline", "2s");
        var rc = exit(wait, 15);
        var seconds = (System.nanoTime() - t0) / 1_000_000_000.0;

        assertThat(rc).as(Files.readString(wait.err())).isEqualTo(124);
        assertThat(Files.readString(wait.err())).contains("deadline 2s passed waiting for file " + never);
        assertThat(seconds).as("within a second of the deadline, never an unbounded wait").isBetween(0.9, 8.0);
        var sleeper = HermeticProcess.builder("sleep", "600").start();

        started.add(sleeper);
        var gone = awaitInBackground("--gone",
                                     Long.toString(sleeper.pid()),
                                     "--deadline",
                                     "1s");

        assertThat(exit(gone, 15)).isEqualTo(124);
        assertThat(Files.readString(gone.err())).contains("waiting for pid " + sleeper.pid());
    }

    // ---- usage: never a pattern
    @Test
    void aPatternOrANonNumericPid_isUsageExit2() throws Exception {
        for (var bad : List.of("bb-mutate.*X", "abc", "12 34", "0", "-1", "+5", "", "1;id", "$$", "12345678901")) {
            var run = await("--gone", bad, "--deadline", "1s");

            assertThat(run.rc()).as("--gone '" + bad + "': " + run.stderr()).isEqualTo(2);
            assertThat(run.stderr()).contains("usage: bb-await").contains("never a pattern");
        }
    }

    @Test
    void malformedArguments_areUsageExit2() throws Exception {
        var bad = List.of(List.<String> of(),
                          List.of("--wat"),
                          List.of("--ticket"),
                          List.of("--ticket", "notanid"),
                          List.of("--ticket", "1-2", "--file", "/x"),
                          List.of("--file", "/x", "--gone", "1"),
                          List.of("--ticket=1-2"),
                          List.of("--file", "/x", "--deadline"),
                          List.of("--file", "/x", "--deadline", "5"),
                          List.of("--file", "/x", "--deadline", "1d"),
                          List.of("--file", "/x", "--deadline", "-3s"),
                          List.of("--file", "/x", "--deadline", "1s", "--deadline", "2s"),
                          List.of("--deadline", "1s"),
                          List.of("--file", "/x", "stray"));

        for (var args : bad) {
            var run = await(args.toArray(String[]::new));

            assertThat(run.rc()).as(args + ": " + run.stderr()).isEqualTo(2);
            assertThat(run.stderr()).contains("usage: bb-await");
        }
    }

    @Test
    void deadlineUnits_areSecondsMinutesHours_andNothingElse() throws Exception {
        var present = Files.writeString(dir.resolve("present"), "x");

        for (var ok : List.of("90s", "30m", "6h", "0s", "007s")) {
            var run = await("--file", present.toString(), "--deadline", ok);

            assertThat(run.rc()).as(ok + ": " + run.stderr()).isZero();
        }

        var zero = awaitInBackground("--file",
                                     dir.resolve("absent").toString(),
                                     "--deadline",
                                     "0s");

        assertThat(exit(zero, 15)).as("a zero deadline ends at the first check").isEqualTo(124);
        for (var bad : List.of("90", "1d", "1.5h", "h", "9999999999s")) {
            assertThat(await("--file", present.toString(), "--deadline", bad).rc()).as(bad).isEqualTo(2);
        }
    }

    private void awaitWaiter(String id) throws Exception {
        for (var i = 0; i < 100 && BbFixtures.waiters(id).isEmpty(); i++) {
            Thread.sleep(100);
        }

        assertThat(BbFixtures.waiters(id)).as("the stand-in waiter for " + id).isNotEmpty();
    }
}
