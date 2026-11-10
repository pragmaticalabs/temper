package org.pragmatica.temper.controller.it;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// m2-design §2.1 end to end (it-pg) through the real routes: `PUT subscribers/{name}/selectors`, then `next` and `ack`.
/// A poll delivers an event iff it is above the cursor at that poll, its kind is admitted and it matches the selector
/// set that poll read; the cursor moves only on an ack. Each consequence the design lists is one test, and the
/// one-statement update is pinned by a concurrent loop (§2.3).
@Tag("pg")
class SelectorDeliveryIT {
    private static final Pattern SEQ = Pattern.compile("\"seq\":(\\d+)");
    private static final Pattern JOB = Pattern.compile("\"job\":(\\d+)");
    private static final Pattern SELECTORS = Pattern.compile("\"selectors\":\\[([^]]*)]");
    private ControllerHarness harness;

    @BeforeEach
    void start() throws Exception {
        harness = new ControllerHarness(false, TimeSpan.timeSpan(300).millis());
    }

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    // ---- HTTP -------------------------------------------------------------------------------------------------------
    private ControllerHarness.Reply selectors(String subscriber, List<String> add, List<String> remove) throws Exception {
        return harness.call("PUT",
                            "/subscribers/" + subscriber + "/selectors",
                            "{\"add\":" + json(add) + ",\"remove\":" + json(remove) + "}",
                            true);
    }

    private ControllerHarness.Reply subscribe(String subscriber, String selector) throws Exception {
        var reply = selectors(subscriber, List.of(selector), List.of());
        assertThat(reply.status()).as(reply.toString()).isEqualTo(200);
        return reply;
    }

    private ControllerHarness.Reply unsubscribe(String subscriber, String selector) throws Exception {
        var reply = selectors(subscriber, List.of(), List.of(selector));
        assertThat(reply.status()).as(reply.toString()).isEqualTo(200);
        return reply;
    }

    private static String json(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private ControllerHarness.Reply next(String subscriber) throws Exception {
        return harness.get("/subscribers/" + subscriber + "/next?terminal=true");
    }

    private void ack(String subscriber, long seq) throws Exception {
        assertThat(harness.post("/subscribers/" + subscriber + "/ack", "{\"ack\":{\"seq\":" + seq + "}}").status()).isEqualTo(204);
    }

    private static long seq(ControllerHarness.Reply reply) {
        return number(SEQ, reply);
    }

    private static long job(ControllerHarness.Reply reply) {
        return number(JOB, reply);
    }

    private static long number(Pattern pattern, ControllerHarness.Reply reply) {
        var matcher = pattern.matcher(reply.body());
        assertThat(matcher.find()).as(pattern + " in " + reply).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static List<String> selectorsOf(ControllerHarness.Reply reply) {
        var matcher = SELECTORS.matcher(reply.body());
        assertThat(matcher.find()).as("selectors in " + reply).isTrue();
        return matcher.group(1).isEmpty()
               ? List.of()
               : Stream.of(matcher.group(1).split(",")).map(quoted -> quoted.substring(1, quoted.length() - 1)).toList();
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------------
    /// A real job (the route looks an added `job:` up), submitted by SHA so that no submit supersedes another; with no
    /// host it stays QUEUED and writes no terminal event of its own.
    private long submitted(String module) throws Exception {
        return jobOf(harness.post("/jobs", mvnJob("{\"pushed\":{\"sha\":\"" + ControllerHarness.SHA_A + "\"}}", "-pl", module, "verify")));
    }

    /// A `finished` event for `job`, appended directly: the test controls exactly which events exist and in what order.
    private long finished(long job) {
        return harness.store.events()
                            .append(some(JobId.jobId(job).unwrap()),
                                    EventRow.Kind.FINISHED,
                                    EventPayload.eventPayload(some("0cf563192a" + "0".repeat(30)),
                                                              some("main"),
                                                              some("mvn"),
                                                              none(),
                                                              some("FAILED"),
                                                              none(),
                                                              some(Counts.counts(1, 0, 1, 0, 0, 1, 0, none(), none(), none())),
                                                              none()))
                            .await(Pg.WAIT)
                            .unwrap();
    }

    // ---- §2.1 ---------------------------------------------------------------------------------------------------------
    /// R-M2-12: a selector added later catches the events after the current cursor, here one no poll scanned yet.
    @Test
    void addedSelector_catchesEventsAfterTheCursor_thatNoPollScanned() throws Exception {
        var watched = submitted("watched");
        var late = submitted("late");
        assertThat(next("cto").status()).as("created at the end of the stream").isEqualTo(204);
        subscribe("cto", "job:" + watched);

        var lateFinished = finished(late);
        subscribe("cto", "job:" + late);
        var delivered = next("cto");

        assertThat(delivered.status()).isEqualTo(200);
        assertThat(seq(delivered)).isEqualTo(lateFinished);
        assertThat(job(delivered)).isEqualTo(late);
    }

    /// R-M2-12 (a): a poll that matched nothing advanced the cursor over the event, so a selector added after it does
    /// not deliver it (the `subscribe` reply shows that job's state instead).
    @Test
    void addedSelector_missesAnEventAPollAlreadyScanned_skipAck() throws Exception {
        var watched = submitted("watched");
        var late = submitted("late");
        assertThat(next("cto").status()).isEqualTo(204);
        subscribe("cto", "job:" + watched);

        var lateFinished = finished(late);
        assertThat(next("cto").status()).as("job:" + late + " is not selected yet").isEqualTo(204);
        assertThat(harness.store.events().subscriber("cto").await(Pg.WAIT).unwrap().cursor()).as("skip-ack")
                                                                                     .isEqualTo(lateFinished);

        subscribe("cto", "job:" + late);

        assertThat(next("cto").status()).isEqualTo(204);
    }

    /// Once the cursor passed an event (an ack of a later delivered one), a selector added for it never replays it.
    @Test
    void addedSelector_neverReplaysAnEventTheCursorPassed() throws Exception {
        var watched = submitted("watched");
        var late = submitted("late");
        assertThat(next("cto").status()).isEqualTo(204);
        subscribe("cto", "job:" + watched);

        finished(late);
        var watchedFinished = finished(watched);
        var delivered = next("cto");
        assertThat(seq(delivered)).isEqualTo(watchedFinished);
        ack("cto", watchedFinished);

        subscribe("cto", "job:" + late);

        assertThat(next("cto").status()).as("job:" + late + "'s finished is below the cursor now").isEqualTo(204);
    }

    /// A removal applies from the next poll: the delivered, unacked event is no longer the answer; acking it still works.
    @Test
    void removedSelector_appliesAtTheNextPoll_andTheDeliveredEventIsAckedNormally() throws Exception {
        var first = submitted("first");
        var second = submitted("second");
        assertThat(next("cto").status()).isEqualTo(204);
        subscribe("cto", "job:" + first);
        subscribe("cto", "job:" + second);
        var firstFinished = finished(first);
        var secondFinished = finished(second);
        assertThat(seq(next("cto"))).isEqualTo(firstFinished);
        assertThat(seq(next("cto"))).as("unacked: the same event again").isEqualTo(firstFinished);

        assertThat(selectorsOf(unsubscribe("cto", "job:" + first))).containsExactly("job:" + second);

        assertThat(seq(next("cto"))).as("the next poll reads the new set").isEqualTo(secondFinished);
        ack("cto", firstFinished);
        assertThat(seq(next("cto"))).isEqualTo(secondFinished);
        ack("cto", secondFinished);
        assertThat(next("cto").status()).isEqualTo(204);
    }

    /// F4 (ruled R-M2-9): the empty set is every job, the first selector narrows it, removing the last widens it back.
    @Test
    void emptySet_isEveryJob_firstSelectorNarrows_lastRemovalWidens() throws Exception {
        var watched = submitted("watched");
        var other = submitted("other");
        assertThat(next("cto").status()).isEqualTo(204);

        var otherFinished = finished(other);
        assertThat(seq(next("cto"))).as("no selector: every job").isEqualTo(otherFinished);

        assertThat(selectorsOf(subscribe("cto", "job:" + watched))).containsExactly("job:" + watched);
        assertThat(next("cto").status()).as("narrowed to job:" + watched).isEqualTo(204);

        assertThat(selectorsOf(unsubscribe("cto", "job:" + watched))).isEmpty();
        var laterFinished = finished(other);
        assertThat(seq(next("cto"))).as("widened back to every job, from the cursor on").isEqualTo(laterFinished);
    }

    /// A plain `watch` (every kind) honours the selectors as `--terminal` does, here on a subscriber that the PUT created
    /// and no poll ever saw: the PUT's cursor rule puts it at the end of the stream, as a first poll would.
    @Test
    void plainWatch_honoursTheSelectors_onASubscriberThePutCreated() throws Exception {
        var watched = submitted("watched");
        var other = submitted("other");
        subscribe("plain", "job:" + watched);

        submitted("later");
        finished(other);
        var watchedFinished = finished(watched);
        var delivered = harness.get("/subscribers/plain/next?terminal=false");

        assertThat(delivered.status()).isEqualTo(200);
        assertThat(seq(delivered)).as("not the later job's queued, not the other job's finished").isEqualTo(watchedFinished);
    }

    // ---- §2.3 ---------------------------------------------------------------------------------------------------------
    @Test
    void put_answers404_forAnUnknownJob_400_forABareSelector_orABadName_andChangesNothing() throws Exception {
        assertThat(selectors("cto", List.of("job:999999"), List.of()).status()).isEqualTo(404);
        assertThat(selectors("cto", List.of("4711"), List.of()).status()).isEqualTo(400);
        assertThat(selectors("cto", List.of("sha:0cf5631"), List.of()).status()).isEqualTo(400);
        assertThat(selectors("bad%20name", List.of("ref:main"), List.of()).status()).isEqualTo(400);
        assertThat(harness.call("PUT", "/subscribers/cto/selectors", "{\"add\":[\"ref:main\"]}", false).status()).isEqualTo(401);

        assertThat(selectorsOf(selectors("cto", List.of(), List.of()))).as("none of them changed the set").isEmpty();
    }

    /// Two clients change one subscriber 100 times each, concurrently. Their changes commute (each removes only what it
    /// added), so the final set is the serial result. A read-modify-write in two statements loses updates here.
    @Test
    void concurrentChanges_fromTwoClients_endInTheSerialResult() throws Exception {
        assertThat(next("racer").status()).isEqualTo(204);
        var failures = new ConcurrentLinkedQueue<String>();
        var rounds = 100;
        var clients = new ArrayList<Thread>();
        clients.add(Thread.ofVirtual().start(() -> IntStream.range(0, rounds)
                                                            .forEach(i -> change(failures, List.of("ref:a-" + i), List.of()))));
        clients.add(Thread.ofVirtual().start(() -> IntStream.range(0, rounds)
                                                            .forEach(i -> change(failures,
                                                                                 List.of("ref:b-" + i),
                                                                                 i == 0
                                                                                 ? List.of()
                                                                                 : List.of("ref:b-" + (i - 1))))));
        for (var client : clients) {
            client.join();
        }
        var expected = new TreeSet<String>();
        IntStream.range(0, rounds).forEach(i -> expected.add("ref:a-" + i));
        expected.add("ref:b-" + (rounds - 1));

        assertThat(failures).isEmpty();
        assertThat(selectorsOf(selectors("racer", List.of(), List.of()))).containsExactlyElementsOf(expected);
    }

    private void change(ConcurrentLinkedQueue<String> failures, List<String> add, List<String> remove) {
        try {
            var reply = selectors("racer", add, remove);
            if (reply.status() != 200) {
                failures.add(reply.toString());
            }
        } catch (Exception e) {
            failures.add(e.toString());
        }
    }
}
