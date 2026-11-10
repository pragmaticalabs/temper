package org.pragmatica.temper.controller.it;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.temper.store.api.UnitStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// Ruling R-13, amended, read side (it-pg tier), and m1-design §14 (a job whose start facts were lost never shows
/// QUEUED while it runs): the start of a running unit is not recorded and the store refuses the UNKNOWN mark while it
/// still serves reads. As soon as the controller knows the start is unrecorded (the first start attempt failed, or the
/// start was dropped), every read shows UNKNOWN and none shows QUEUED for that job, long before the start bound, while
/// the store itself still has it QUEUED; past the bound it stays UNKNOWN. The controller's clock is moved past the
/// bound instead of waiting.
@Tag("pg")
class StartUnknownReadIT {
    private static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";

    private final AtomicBoolean refusing = new AtomicBoolean(true);
    private final AtomicInteger refusedMarks = new AtomicInteger();
    private final AtomicBoolean refusingStarts = new AtomicBoolean(false);
    private final AtomicInteger refusedStarts = new AtomicInteger();
    private final AtomicBoolean terminalMarks = new AtomicBoolean(false);
    /// Armed with a job id: the next `job`/`list` read lets that job's start be recorded right after it read the rows
    /// ([#readThenRecordTheStart]).
    private final AtomicLong recordDuringRead = new AtomicLong(-1);
    private final AtomicReference<Duration> skew = new AtomicReference<>(Duration.ZERO);
    private ControllerHarness harness;

    @BeforeEach
    void start() throws Exception {
        harness = new ControllerHarness(TimeSpan.timeSpan(500).millis(),
                                        this::refusingUnknownMarks,
                                        () -> Instant.now().plus(skew.get()));
    }

    @AfterEach
    void stop() {
        refusing.set(false);
        refusingStarts.set(false);
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void markRefused_readsServed_jobShowsUnknownAtOnce_andPastTheStartBound_neverQueued() throws Exception {
        harness.worker.respondWith(this::startUnrecordable);
        var body = mvnJob(BY_MAIN, "-pl", "lost-start", "verify");
        var job = jobOf(harness.post("/jobs", body));
        waitFor(() -> refusedMarks.get() > 0, "the dropped start's UNKNOWN mark refused");

        assertUnknownOnEveryRead(job, body);

        skew.set(ControllerHarness.TIMING.startBound().plusSeconds(1));

        assertUnknownOnEveryRead(job, body);
        assertThat(storedState(job)).as("the mark never landed: the store still says QUEUED").isEqualTo(JobState.QUEUED);

        harness.worker.send(ControllerHarness.exited(job));
        harness.worker.send(ControllerHarness.report(job));
        harness.waitForState(job, "PASSED");
    }

    /// m1-design §14: the start write is refused on every attempt; right after the first refused attempt (the clock
    /// is not moved, so the start bound plays no part) every read shows UNKNOWN. Once the mark lands the stored
    /// UNKNOWN shows; the completion then shows the verdict.
    @Test
    void startWriteRefused_jobShowsUnknownRightAfterTheFirstFailedAttempt_markLands_thenTerminal() throws Exception {
        refusingStarts.set(true);
        var body = mvnJob(BY_MAIN, "-pl", "held", "verify");
        var job = jobOf(harness.post("/jobs", body));
        waitFor(() -> refusedStarts.get() > 0, "the first start attempt refused");

        assertUnknownOnEveryRead(job, body);
        assertThat(storedState(job)).as("the store still says QUEUED").isEqualTo(JobState.QUEUED);

        refusing.set(false);
        waitFor(() -> storedState(job) == JobState.UNKNOWN, "the UNKNOWN mark landed");
        assertUnknownOnEveryRead(job, body);

        harness.worker.send(ControllerHarness.exited(job));
        harness.worker.send(ControllerHarness.report(job));
        harness.waitForState(job, "PASSED");
    }

    /// Verification F3 on Postgres: the dropped start's UNKNOWN mark ends on a terminal cause, unlanded. The unit still
    /// runs, so every read keeps showing it UNKNOWN, never QUEUED, until it completes.
    @Test
    void markEndsOnATerminalCause_jobStillShowsUnknown_untilItCompletes() throws Exception {
        refusingStarts.set(true);
        terminalMarks.set(true);
        var body = mvnJob(BY_MAIN, "-pl", "held", "verify");
        var job = jobOf(harness.post("/jobs", body));
        waitFor(() -> refusedMarks.get() > 0, "the UNKNOWN mark ended on the terminal cause");
        Thread.sleep(500);

        assertUnknownOnEveryRead(job, body);

        harness.worker.send(ControllerHarness.exited(job));
        harness.worker.send(ControllerHarness.report(job));
        harness.waitForState(job, "PASSED");
    }

    /// Verification F6: the set of units known unrecorded is read BEFORE the rows. The rows are read while the start
    /// is unrecorded; the start is then recorded and the unit leaves the set before the read answers. Read in that
    /// order, the view still shows UNKNOWN (the set held the unit); read the other way round, it would show QUEUED (a
    /// GRANTED row with no start, and no entry). Detail, list and the deduplicated submit.
    @Test
    void readSide_takesTheSetBeforeTheRows_aStartRecordedInBetweenNeverShowsQueued() throws Exception {
        for (var path : List.of("detail", "list", "dedup")) {
            refusingStarts.set(true);
            var body = mvnJob(BY_MAIN, "-pl", "held", "-DtargetTests=read-" + path, "verify");
            var job = jobOf(harness.post("/jobs", body));
            var refusedBefore = refusedMarks.get();
            waitFor(() -> refusedMarks.get() > refusedBefore, "the start dropped, its UNKNOWN mark refused");
            recordDuringRead.set(job);

            var shown = switch (path) {
                case "detail" -> harness.get("/jobs/" + job).body();
                case "list" -> harness.get("/jobs?limit=1000").body();
                default -> harness.post("/jobs", body).body();
            };

            assertThat(recordDuringRead.get()).as("the race was staged").isEqualTo(-1);
            assertThat(shown).as(path).contains("{\"job\":" + job + ",\"state\":\"UNKNOWN\"")
                             .doesNotContain("{\"job\":" + job + ",\"state\":\"QUEUED\"");
            assertThat(harness.get("/jobs/" + job).body()).as("the next read").startsWith("{\"job\":" + job + ",\"state\":\"RUNNING\"");
        }
    }

    /// m1-design §14: a start recorded late (the worker re-sends it once the store takes it) shows RUNNING.
    @Test
    void startWriteRefused_thenRecordedLate_jobShowsRunning() throws Exception {
        refusingStarts.set(true);
        var body = mvnJob(BY_MAIN, "-pl", "held", "verify");
        var job = jobOf(harness.post("/jobs", body));
        waitFor(() -> refusedStarts.get() > 0, "the first start attempt refused");
        assertUnknownOnEveryRead(job, body);

        refusingStarts.set(false);
        harness.worker.send(new Message.UnitStarted(job, "main", 1, "c" + job, "sha256:img", 1_700_000_000_000_000_000L,
                                                    Instant.now(), ControllerHarness.facts(), none()));

        harness.waitForState(job, "RUNNING");
        assertThat(harness.get("/jobs/" + job).body()).contains("\"name\":\"main\",\"state\":\"RUNNING\"")
                                                      .doesNotContain("UNKNOWN");
        harness.worker.send(ControllerHarness.exited(job));
        harness.worker.send(ControllerHarness.report(job));
        harness.waitForState(job, "PASSED");
    }

    /// Verification F1 on Postgres: the `state` filter pages past the store's `limit`. An older UNKNOWN job behind newer
    /// QUEUED ones is found with `state=UNKNOWN&limit=1`; an older QUEUED job behind a newer one that shows UNKNOWN is
    /// found with `state=QUEUED&limit=1`.
    @Test
    void stateFilter_limit1_pagesPastNewerJobsOfTheOtherShownState() throws Exception {
        refusingStarts.set(true);
        var unknown = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));
        waitFor(() -> refusedMarks.get() > 0, "the start dropped, its UNKNOWN mark refused");
        refusingStarts.set(false);
        var running = new java.util.ArrayList<Long>();
        var queued = -1L;
        for (var n = 0; n < 8 && queued < 0; n++) {
            var next = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "-DtargetTests=n" + n, "verify")));
            Thread.sleep(1000);
            if (harness.get("/jobs/" + next).body().startsWith("{\"job\":" + next + ",\"state\":\"QUEUED\"")) {
                queued = next;
            } else {
                running.add(next);
            }
        }
        assertThat(queued).as("precondition: a newer job waits QUEUED for capacity").isPositive();
        assertThat(harness.get("/jobs/" + unknown).body()).startsWith("{\"job\":" + unknown + ",\"state\":\"UNKNOWN\"");

        assertThat(harness.get("/jobs?state=UNKNOWN&limit=1").body()).startsWith("[{\"job\":" + unknown + ",\"state\":\"UNKNOWN\"");

        refusingStarts.set(true);
        var urgent = jobOf(harness.post("/jobs", "{\"submittedBy\":\"v2174\",\"priority\":10,\"source\":" + BY_MAIN
                                                 + ",\"recipe\":{\"mvn\":{\"args\":[\"-pl\",\"held\",\"-DtargetTests=urgent\",\"verify\"]}}}"));
        harness.worker.send(ControllerHarness.exited(running.getFirst()));
        harness.worker.send(ControllerHarness.report(running.getFirst()));
        var refusedBefore = refusedStarts.get();
        waitFor(() -> refusedStarts.get() > refusedBefore, "the urgent job granted first, its start refused");
        assertThat(harness.get("/jobs/" + urgent).body()).startsWith("{\"job\":" + urgent + ",\"state\":\"UNKNOWN\"");
        assertThat(harness.get("/jobs/" + queued).body()).startsWith("{\"job\":" + queued + ",\"state\":\"QUEUED\"");

        assertThat(harness.get("/jobs?state=QUEUED&limit=1").body()).startsWith("[{\"job\":" + queued + ",\"state\":\"QUEUED\"");
    }

    /// Detail, list, both `state` filters and the deduplicated submit all show the job UNKNOWN, none QUEUED.
    private void assertUnknownOnEveryRead(long job, String body) throws Exception {
        assertThat(harness.get("/jobs/" + job).body()).startsWith("{\"job\":" + job + ",\"state\":\"UNKNOWN\"")
                                                      .contains("\"name\":\"main\",\"state\":\"UNKNOWN\"")
                                                      .doesNotContain("QUEUED");
        assertThat(harness.get("/jobs?limit=1000").body()).contains("{\"job\":" + job + ",\"state\":\"UNKNOWN\"")
                                                          .doesNotContain("{\"job\":" + job + ",\"state\":\"QUEUED\"");
        assertThat(harness.get("/jobs?state=QUEUED&limit=1000").body()).doesNotContain("{\"job\":" + job + ",");
        assertThat(harness.get("/jobs?state=UNKNOWN&limit=1000").body()).contains("{\"job\":" + job + ",\"state\":\"UNKNOWN\"");
        assertThat(harness.post("/jobs", body).body()).isEqualTo("{\"job\":" + job + ",\"state\":\"UNKNOWN\",\"deduplicated\":true}");
    }

    private JobState storedState(long job) {
        return harness.store.jobs()
                            .job(JobId.jobId(job).unwrap())
                            .await()
                            .unwrap()
                            .map(row -> row.state())
                            .or(JobState.ERROR);
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never: " + what);
            }
            Thread.sleep(20);
        }
    }

    /// A unit whose args contain `lost-start` starts with marker epoch 0, which does not parse: the start is dropped
    /// at once and the unit keeps running.
    private List<Message> startUnrecordable(Message request) {
        return request instanceof Message.StartUnit start && start.steps().stream().anyMatch(step -> step.contains("lost-start"))
               ? List.of(new Message.UnitStarted(start.job(), start.unit(), 1, "c" + start.job(), "sha256:img", 0L, Instant.now(),
                                                 ControllerHarness.facts(), none()))
               : harness.respond(request);
    }

    /// The store the controller sees: every call is the real store's, except `UnitStore.startUnknown`, refused while
    /// [#refusing], and `UnitStore.started`, refused while [#refusingStarts] (reads and every other write are served).
    private Store refusingUnknownMarks(Store store) {
        var units = (UnitStore) Proxy.newProxyInstance(UnitStore.class.getClassLoader(),
                                                       new Class<?>[] {UnitStore.class},
                                                       (_, method, args) -> unitCall(store.units(), method, args));

        var jobs = (JobStore) Proxy.newProxyInstance(JobStore.class.getClassLoader(),
                                                     new Class<?>[] {JobStore.class},
                                                     (_, method, args) -> jobCall(store.jobs(), method, args));

        return (Store) Proxy.newProxyInstance(Store.class.getClassLoader(),
                                              new Class<?>[] {Store.class},
                                              (_, method, args) -> switch (method.getName()) {
                                                  case "units" -> units;
                                                  case "jobs" -> jobs;
                                                  default -> method.invoke(store, args);
                                              });
    }

    private Object jobCall(JobStore jobs, Method method, Object[] args) throws Exception {
        var armed = recordDuringRead.get();
        return (method.getName().equals("job") || method.getName().equals("list")) && armed > 0
               && recordDuringRead.compareAndSet(armed, -1)
               ? readThenRecordTheStart(jobs, method, args, armed)
               : method.invoke(jobs, args);
    }

    /// The race the read side's ordering closes: the rows are read while the start is unrecorded, then the start is
    /// recorded (the worker re-sends it) and the controller forgets the unit, and only then does the read answer. Run
    /// off the calling thread, which may be the store driver's own.
    @SuppressWarnings("unchecked")
    private Promise<Object> readThenRecordTheStart(JobStore jobs, Method method, Object[] args, long job) throws Exception {
        var rows = (Promise<Object>) method.invoke(jobs, args);
        var answer = Promise.<Object> promise();
        Thread.ofVirtual().start(() -> recordTheStartThenAnswer(rows, answer, job));
        return answer;
    }

    private void recordTheStartThenAnswer(Promise<Object> rows, Promise<Object> answer, long job) {
        var read = rows.await();
        try {
            refusingStarts.set(false);
            harness.worker.send(new Message.UnitStarted(job, "main", 1, "c" + job, "sha256:img", 1_700_000_000_000_000_000L,
                                                        Instant.now(), ControllerHarness.facts(), none()));
            waitFor(() -> storedState(job) == JobState.RUNNING, "the start recorded");
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        answer.resolve(read);
    }

    private Object unitCall(UnitStore units, Method method, Object[] args) throws Exception {
        if (method.getName().equals("started") && refusingStarts.get()) {
            refusedStarts.incrementAndGet();
            return Causes.cause("start write refused (reads still served)").promise();
        }
        if (method.getName().equals("startUnknown") && terminalMarks.get()) {
            refusedMarks.incrementAndGet();
            return Causes.terminal("unit row unreadable (terminal)").promise();
        }
        if (method.getName().equals("startUnknown") && refusing.get()) {
            refusedMarks.incrementAndGet();
            return Causes.cause("UNKNOWN mark refused (reads still served)").promise();
        }
        return method.invoke(units, args);
    }
}
