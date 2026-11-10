package org.pragmatica.temper.controller.it;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.temper.store.api.UnitStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// m1-design §14, the maintainer's 1 s ruling, pinned from both sides on Postgres through the API, with the production
/// threshold (`LinkTiming.DEFAULT.unrecordedAfter`, which [ControllerHarness#TIMING] uses):
///
/// - (a) once `UnitStarted` reaches the controller, no read shows the job QUEUED later than 1 s after it, whether the
///   start write is slow but succeeds, fails, or hangs;
/// - (b) a healthy start (a write that lands in 150 ms) never shows UNKNOWN at any read.
///
/// The bound is measured from the moment the fake worker sends the `UnitStarted`, which is never later than its arrival,
/// the ruling's clock. In (a) the ownership read that precedes the start write takes 500 ms, so a timer started at the
/// write rather than at the arrival shows QUEUED past the bound.
@Tag("pg")
class UnrecordedAfterIT {
    private static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";
    /// The ruling's bound, and the scheduling slack a read is given past it.
    private static final Duration BOUND = Duration.ofSeconds(1);
    private static final Duration SLACK = Duration.ofMillis(200);

    enum StartWrite { SLOW, FAILING, HANGING }

    private final AtomicReference<StartWrite> behaviour = new AtomicReference<>(StartWrite.SLOW);
    private final AtomicLong startDelayMs = new AtomicLong();
    private final AtomicLong firstStartNanos = new AtomicLong();
    private final AtomicLong unitStartedNanos = new AtomicLong();
    private final AtomicLong ownershipDelayMs = new AtomicLong();
    private ControllerHarness harness;

    record Read(Duration at, String body) {}

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    /// (a) A read 1 s (plus scheduling slack) or more after the `UnitStarted` shows UNKNOWN, never QUEUED, through 3 s
    /// of reads: the slow write lands at 3 s, the failing one is refused at once, the hanging one never answers within
    /// its 5 s attempt timeout.
    @ParameterizedTest
    @EnumSource(StartWrite.class)
    void unitStarted_noReadShowsQueuedPastOneSecond(StartWrite write) throws Exception {
        behaviour.set(write);
        startDelayMs.set(3000);
        ownershipDelayMs.set(500);
        harness = new ControllerHarness(TimeSpan.timeSpan(500).millis(), this::seen, Instant::now);
        harness.worker.respondWith(this::stamped);
        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));
        waitForUnitStarted();

        var late = reads(job, Duration.ofSeconds(3)).stream()
                                                    .filter(read -> read.at().compareTo(BOUND.plus(SLACK)) >= 0)
                                                    .toList();

        assertThat(late).as("reads past 1 s").isNotEmpty();
        assertThat(late).as("%s start write: reads past 1 s after UnitStarted", write)
                        .allSatisfy(read -> assertThat(read.body()).doesNotContain("\"state\":\"QUEUED\""));
    }

    /// (b) A healthy start write (150 ms) is never shown UNKNOWN, by any read from the submit until the job runs.
    @Test
    void healthyStart_neverShowsUnknown() throws Exception {
        behaviour.set(StartWrite.SLOW);
        startDelayMs.set(150);
        harness = new ControllerHarness(TimeSpan.timeSpan(500).millis(), this::seen, Instant::now);
        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));
        var seen = new ArrayList<String>();

        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && seen.stream().noneMatch(body -> body.startsWith("{\"job\":" + job + ",\"state\":\"RUNNING\""))) {
            seen.add(harness.get("/jobs/" + job).body());
        }

        assertThat(seen).as("the job reached RUNNING").anyMatch(body -> body.startsWith("{\"job\":" + job + ",\"state\":\"RUNNING\""));
        assertThat(firstStartNanos.get()).as("the start write ran").isPositive();
        assertThat(seen.size()).as("reads taken").isGreaterThan(5);
        assertThat(seen).as("every read of a healthy start").noneMatch(body -> body.contains("UNKNOWN"));
    }

    private List<Read> reads(long job, Duration window) throws Exception {
        var reads = new ArrayList<Read>();
        while (sinceUnitStarted().compareTo(window) < 0) {
            var at = sinceUnitStarted();
            reads.add(new Read(at, harness.get("/jobs/" + job).body()));
            Thread.sleep(20);
        }
        return reads;
    }

    private Duration sinceUnitStarted() {
        return Duration.ofNanos(System.nanoTime() - unitStartedNanos.get());
    }

    /// The harness's replies, stamping the moment the first `UnitStarted` is sent.
    private List<Message> stamped(Message request) {
        var replies = harness.respond(request);
        if (replies.stream().anyMatch(Message.UnitStarted.class::isInstance)) {
            unitStartedNanos.compareAndSet(0, System.nanoTime());
        }
        return replies;
    }

    private void waitForUnitStarted() throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (unitStartedNanos.get() == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never: the UnitStarted was sent");
            }
            Thread.sleep(5);
        }
    }

    private Store seen(Store store) {
        var units = (UnitStore) Proxy.newProxyInstance(UnitStore.class.getClassLoader(),
                                                       new Class<?>[] {UnitStore.class},
                                                       (_, method, args) -> unitCall(store.units(), method, args));
        return (Store) Proxy.newProxyInstance(Store.class.getClassLoader(),
                                              new Class<?>[] {Store.class},
                                              (_, method, args) -> method.getName().equals("units")
                                                                   ? units
                                                                   : method.invoke(store, args));
    }

    /// `started` as [#behaviour] says: delayed by [#startDelayMs] then the real write, refused, or never answered.
    /// Once the `UnitStarted` is sent, the ownership read (`active`) answers [#ownershipDelayMs] late.
    @SuppressWarnings("unchecked")
    private Object unitCall(UnitStore units, Method method, Object[] args) throws Exception {
        if (method.getName().equals("active") && unitStartedNanos.get() != 0) {
            return delayed(units, method, args, ownershipDelayMs.get());
        }
        if (!method.getName().equals("started")) {
            return method.invoke(units, args);
        }
        firstStartNanos.compareAndSet(0, System.nanoTime());
        return switch (behaviour.get()) {
            case FAILING -> Causes.cause("start write refused").promise();
            case HANGING -> Promise.promise();
            case SLOW -> delayed(units, method, args, startDelayMs.get());
        };
    }

    @SuppressWarnings("unchecked")
    private Promise<Object> delayed(UnitStore units, Method method, Object[] args, long delayMs) {
        var out = Promise.<Object> promise();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delayMs);
                ((Promise<Object>) method.invoke(units, args)).onResult(out::resolve);
            } catch (Exception e) {
                out.fail(Causes.fromThrowable(e));
            }
        });
        return out;
    }
}
