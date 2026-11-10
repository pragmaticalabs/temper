package org.pragmatica.temper.acceptance.hold;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.acceptance.stack.AcceptanceStack;
import org.pragmatica.temper.acceptance.stack.HostSettings;
import org.pragmatica.temper.acceptance.stack.HostTier;
import org.pragmatica.temper.acceptance.stack.RecordingRunner;
import org.pragmatica.temper.acceptance.stack.Shell;
import org.pragmatica.temper.controller.host.Connection;
import org.pragmatica.temper.protocol.api.UnitView;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// A11 (m1-design §9.1), host tier: bb-hold re-attach on a real host (bigboy), through the real controller (in this
/// JVM, on the acceptance Postgres of Testcontainers), its real ssh to the host's `worker-launch`, the real worker
/// jar of this build (installed first), real bb-hold and docker. The fixture's `hold` module is the controller's
/// `[portGroup] modules`, so its Gates go through bb-hold; `hold-long` sleeps 120 s in its test, `hold-short` 5 s.
/// `maxHolding` is 2, so a second temper unit can WAIT in bb-hold behind the first.
///
/// Cases (§9.1 and the M1 integration verification's F1/F4):
/// 1. while RUNNING, kill the ssh pid and restart the controller: the ticket stays running until the container exits,
///    then is gone within 10 s; PASSED with one start (`startedAt`, container and `portGroupSince` unchanged: the
///    re-sent `UnitStarted` of the re-attach, ruling R-9, is a double start the store must ignore, F1);
/// 1b. the same while WAITING: the waiter is re-attached, never `ERROR(lost)`, and runs once the group is free;
/// 2. `kill -9` of the holder: `VOID(port-group-lost)` within 30 s; the ticket gone from `bb-queue 0` within 10 s of it,
///    and the worker logged a `bb-queue reap` outcome for the unit after the kill (maintainer ruling A11, m1-design §14);
///    the container gone;
/// 3. cancel while WAITING: CANCELLED, no container, no temper ticket left queued;
/// 4. lost start, then exit (F4): no worker is attached when bb-hold grants and the container runs to its end; the
///    reconnect collects it.
///
/// Mutation probes (run by the main session, README.md): disable `HoldMonitor` → case 2 ends PASSED; delete Reconciler
/// rule 3 → case 1b ends `ERROR(lost)`; remove the `HoldMonitor`'s `bb-queue reap` → case 2 logs no reap outcome (on any
/// host) and its ticket is not released (on an idle host).
@Tag("host")
@ExtendWith(HostTier.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BbHoldReattachIT {
    private static final String UNIT = "gate";
    private static final Duration GATE = Duration.ofMinutes(30);
    /// bigboy's `~/tools/bb-hold` prints `bb-hold: queued ticket <id> owner=…` to stderr (its line 103; checked for the
    /// verification's F4), which `bbhold.err` captures; bb-build's `queued ticket=<id>` is another tool's format.
    private static final Pattern QUEUED = Pattern.compile("queued ticket (\\S+)");
    private AcceptanceStack stack;
    private HostSettings settings;
    /// The worker's stderr reaches this JVM through the controller's link, which logs each line as `[host] worker: …`.
    private final ListAppender<ILoggingEvent> workerLog = new ListAppender<>();
    private final Logger connectionLogger = (Logger) LoggerFactory.getLogger(Connection.class);

    @BeforeAll
    void stack() throws Exception {
        workerLog.start();
        connectionLogger.addAppender(workerLog);
        settings = HostSettings.fromProperties();
        stack = AcceptanceStack.host("hold", List.of("hold-long", "hold-short"), settings);
    }

    @AfterAll
    void close() {
        stack.close();
        connectionLogger.detachAppender(workerLog);
    }

    @Test
    @Order(1)
    void reattach_whileRunning_keepsTheTicketAndOneStart() throws Exception {
        var job = stack.submitGate("hold-long");
        var held = await("job " + job + " RUNNING and held", Duration.ofMinutes(15), () -> unit(job), BbHoldReattachIT::runningAndHeld);
        var ticket = held.ticket().expect("acceptance setup");
        var container = held.containerId().expect("acceptance setup");

        assertThat(bbQueue()).as("the temper ticket is running").contains(ticket);

        killSshSession();
        stack.restartController();

        while (containerRunning(containerName(job))) {
            assertThat(bbQueue()).as("ticket held while the container runs").contains(ticket);
            Thread.sleep(5_000);
        }
        await("ticket " + ticket + " released", Duration.ofSeconds(10), this::bbQueue, queue -> !queue.contains(ticket));

        stack.awaited(job, GATE);
        var done = unit(job);

        assertThat(stack.job(job).verdict()).isEqualTo(Option.some("PASSED"));
        assertThat(done.startedAt()).as("one start: the re-sent UnitStarted (R-9) did not overwrite it (F1)").isEqualTo(held.startedAt());
        assertThat(done.containerId()).isEqualTo(Option.some(container));
        assertThat(done.portGroupSince()).isEqualTo(held.portGroupSince());
        assertThat(done.ticket()).isEqualTo(Option.some(ticket));
    }

    @Test
    @Order(2)
    void reattach_whileWaiting_isNotLost() throws Exception {
        var occupant = stack.submitGate("hold-long");
        await("occupant held", Duration.ofMinutes(15), () -> unit(occupant), BbHoldReattachIT::runningAndHeld);
        var waiter = stack.submitGate("hold-short");
        await("waiter waiting in bb-hold", Duration.ofMinutes(10), () -> unit(waiter), BbHoldReattachIT::waiting);

        killSshSession();
        stack.restartController();

        assertThat(unit(waiter).reason()).as("a waiting unit is re-attached, never lost (Reconciler rule 3)")
                                         .isNotEqualTo(Option.some("lost"));
        stack.awaited(occupant, GATE);
        stack.awaited(waiter, GATE);
        assertThat(stack.job(occupant).verdict()).isEqualTo(Option.some("PASSED"));
        assertThat(stack.job(waiter).verdict()).as(stack.job(waiter).toString()).isEqualTo(Option.some("PASSED"));
    }

    @Test
    @Order(3)
    void holderKilled_isVoidPortGroupLost() throws Exception {
        var job = stack.submitGate("hold-long");
        var held = await("job " + job + " RUNNING and held", Duration.ofMinutes(15), () -> unit(job), BbHoldReattachIT::runningAndHeld);
        var ticket = held.ticket().expect("acceptance setup");

        var beforeKill = workerLines().size();
        Shell.ok("kill", "-9", holderPid(job));

        var ended = await("VOID within 30 s", Duration.ofSeconds(30), () -> stack.job(job), view -> view.verdict().isPresent());

        assertThat(ended.verdict()).isEqualTo(Option.some("VOID"));
        assertThat(ended.reason()).isEqualTo(Option.some("port-group-lost"));
        // maintainer ruling A11 (m1-design §14): bb-queue's reap() runs only inside a queue client (no timer), and the
        // `bb-queue 0` listing does not reap. So the end state (the listing) and the call (the worker logged a reap
        // outcome for this unit after the kill) are both asserted, and neither asks WHICH client moved the ticket: every
        // waiter reaps every 5 s, so on a busy host another client may move it first and the worker's reap prints
        // `reap: nothing to move`, which is correct. Red without the worker's reap on any host: no outcome line is
        // logged (only the worker's own reap logs `unit <container>: bb-queue reap: …`); on an idle host the ticket also
        // stays listed. An older bb-queue without the subcommand prints its listing, not an outcome line: red, as the
        // host needs that subcommand. The baseline is the kill, not the observed VOID: the worker reaps right after its
        // own VOID decision, which precedes the verdict this test sees.
        await("the ticket is released within 10 s of VOID", Duration.ofSeconds(10), this::bbQueue, queue -> !queue.contains(ticket));
        await("the worker logged a `bb-queue reap` outcome for " + containerName(job) + " after the kill", Duration.ofSeconds(10),
              () -> workerLinesSince(beforeKill),
              lines -> lines.stream().anyMatch(line -> reapOutcome(line, containerName(job))));
        await("the container is gone", Duration.ofSeconds(60), () -> containerExists(containerName(job)), exists -> !exists);
    }

    @Test
    @Order(4)
    void cancelWhileWaiting_isCancelled_withNoContainerAndNoTicket() throws Exception {
        var occupant = stack.submitGate("hold-long");
        await("occupant held", Duration.ofMinutes(15), () -> unit(occupant), BbHoldReattachIT::runningAndHeld);
        var waiter = stack.submitGate("hold-short");
        await("waiter waiting in bb-hold", Duration.ofMinutes(10), () -> unit(waiter), BbHoldReattachIT::waiting);
        var queued = queuedTicket(waiter);

        assertThat(bbQueue()).as("the waiter's ticket is queued").contains(queued);
        stack.cancel(waiter);

        var ended = await("waiter CANCELLED", Duration.ofMinutes(2), () -> stack.job(waiter), view -> view.verdict().isPresent());

        assertThat(ended.verdict()).isEqualTo(Option.some("CANCELLED"));
        assertThat(containerExists(containerName(waiter))).as("no container").isFalse();
        await("no queued temper ticket", Duration.ofSeconds(15), this::bbQueue, queue -> !queue.contains(queued));
        stack.awaited(occupant, GATE);
    }

    @Test
    @Order(5)
    void lostStartThenExit_isCollectedOnce() throws Exception {
        var occupant = stack.submitGate("hold-long");
        await("occupant held", Duration.ofMinutes(15), () -> unit(occupant), BbHoldReattachIT::runningAndHeld);
        var waiter = stack.submitGate("hold-short");
        await("waiter waiting in bb-hold", Duration.ofMinutes(10), () -> unit(waiter), BbHoldReattachIT::waiting);

        stack.stopController();
        await("the waiter was granted and its container exited with no worker attached",
              Duration.ofMinutes(20),
              () -> containerStatus(containerName(waiter)),
              status -> status.equals("exited"));
        stack.startController();

        stack.awaited(waiter, GATE);
        stack.awaited(occupant, GATE);
        var view = stack.job(waiter);
        var side = stack.side(waiter);

        assertThat(view.verdict()).as(view.toString()).isEqualTo(Option.some("PASSED"));
        assertThat(side.rows()).as("its hold test was collected exactly once")
                               .filteredOn(row -> row.className().equals("fx.hold.HoldSleepTest"))
                               .hasSize(1);
        assertThat(side.manifest().missing()).isEmpty();
        // The R-9 residual gap of the M1 integration verification (F4): the start was never recorded, because no
        // worker saw the grant (no bbhold.ticket, so the collection re-sends no UnitStarted). Pinned so that a change
        // either way is noticed; T1 looks for it in the live records.
        assertThat(view.units().getFirst().startedAt().isEmpty()).as("F4: no start recorded").isTrue();
        assertThat(side.result().units().getFirst().portGroup().isEmpty()).as("F4: no portGroup in the record").isTrue();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static boolean runningAndHeld(UnitView unit) {
        return unit.state().name().equals("RUNNING") && unit.portGroup().equals(Option.some(UnitView.HELD)) && unit.ticket().isPresent()
               && unit.containerId().isPresent() && unit.startedAt().isPresent();
    }

    private static boolean waiting(UnitView unit) {
        return unit.portGroup().equals(Option.some(UnitView.WAITING));
    }

    private UnitView unit(long job) {
        return stack.job(job).units().getFirst();
    }

    private void killSshSession() {
        var sessions = ((RecordingRunner) stack.ssh).sessions;

        assertThat(sessions).as("an ssh run session").isNotEmpty();
        sessions.getLast().kill();
    }

    /// One of the two `bb-queue reap` outcomes (`reaped <id>: <state>`, `reap: nothing to move`) as
    /// `BbQueueReap` logs it for this unit.
    private static boolean reapOutcome(String line, String container) {
        var prefix = "unit " + container + ": bb-queue reap: ";

        return line.contains(prefix + "reaped ") || line.contains(prefix + "reap: nothing to move");
    }

    private List<String> workerLinesSince(int from) {
        var lines = workerLines();

        return lines.subList(from, lines.size());
    }

    /// A copy taken under the appender's lock: `AppenderBase.doAppend` holds it while the link's drain thread appends.
    private List<String> workerLines() {
        synchronized (workerLog) {
            return workerLog.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    private String bbQueue() {
        try {
            return Shell.ok(settings.home().resolve("tools/bb-queue").toString(), "0");
        } catch (Exception e) {
            throw new AssertionError("bb-queue failed", e);
        }
    }

    private static String containerName(long job) {
        return "temper-" + job + "-" + UNIT;
    }

    private static String containerStatus(String name) {
        try {
            var run = Shell.run(60, "docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", name);
            return run.exit() == 0 ? run.out() : "absent";
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean containerRunning(String name) {
        return containerStatus(name).equals("running");
    }

    private static boolean containerExists(String name) {
        return !containerStatus(name).equals("absent");
    }

    private String holderPid(long job) throws Exception {
        return Files.readString(stack.unitDir(job, UNIT).resolve(".temper/bbhold.pid")).strip().split("\\s+")[0];
    }

    private String queuedTicket(long job) throws Exception {
        var err = Files.readString(stack.unitDir(job, UNIT).resolve(".temper/bbhold.err"));
        var matcher = QUEUED.matcher(err);

        assertThat(matcher.find()).as("bbhold.err names its queued ticket:\n" + err).isTrue();
        return matcher.group(1);
    }

    private static <T> T await(String what, Duration bound, Supplier<T> probe, Predicate<T> done) throws InterruptedException {
        var deadline = Instant.now().plus(bound);
        T last = probe.get();
        while (!done.test(last)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError(what + ": not within " + bound + "; last " + last);
            }
            Thread.sleep(1_000);
            last = probe.get();
        }
        return last;
    }

}
