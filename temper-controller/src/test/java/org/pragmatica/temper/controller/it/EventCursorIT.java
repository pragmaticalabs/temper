package org.pragmatica.temper.controller.it;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// A5, server side (it-pg): the subscriber cursor advances only on ack, and asking again (a reconnect) replays from
/// cursor+1, over the long-poll transport that replaces `WS /events` (the §2.4 spike failed).
@Tag("pg")
class EventCursorIT {
    private static final Pattern SEQ = Pattern.compile("\"seq\":(\\d+)");
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

    private ControllerHarness.Reply next(String subscriber) throws Exception {
        return harness.get("/subscribers/" + subscriber + "/next?terminal=false");
    }

    private static long seq(ControllerHarness.Reply reply) {
        var matcher = SEQ.matcher(reply.body());
        assertThat(matcher.find()).as("an event in " + reply).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private ControllerHarness.Reply ack(String subscriber, long seq) throws Exception {
        return harness.post("/subscribers/" + subscriber + "/ack", "{\"ack\":{\"seq\":" + seq + "}}");
    }

    @Test
    void cursor_advancesOnlyOnAck_reconnectReplaysFromCursorPlusOne() throws Exception {
        assertThat(next("watcher").status()).as("a new subscriber starts at max(seq): nothing to replay").isEqualTo(204);

        harness.post("/jobs", mvnJob("{\"pushed\":{\"ref\":\"main\"}}", "-pl", "a5", "verify"));
        var first = next("watcher");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).startsWith("{\"event\":").contains("\"kind\":\"queued\"");
        var n = seq(first);
        assertThat(first.body()).contains("seq=" + n);

        var again = next("watcher");
        assertThat(seq(again)).as("no ack: the same event again").isEqualTo(n);

        assertThat(ack("watcher", n).status()).isEqualTo(204);
        assertThat(next("watcher").status()).as("acked, nothing newer yet").isEqualTo(204);

        harness.post("/jobs", mvnJob("{\"pushed\":{\"ref\":\"main\"}}", "-pl", "a5b", "verify"));
        assertThat(seq(next("watcher"))).isEqualTo(n + 1);
    }

    @Test
    void cursor_survivesControllerRestart() throws Exception {
        next("survivor");
        harness.post("/jobs", mvnJob("{\"pushed\":{\"ref\":\"main\"}}", "-pl", "restart", "verify"));
        var n = seq(next("survivor"));
        var store = harness.store;

        try (var restarted = harness.restartOn(store)) {
            assertThat(seq(restarted.get("/subscribers/survivor/next?terminal=false"))).isEqualTo(n);
        }
    }

    @Test
    void ack_answers404_unknownSubscriber_and400_badBody() throws Exception {
        assertThat(ack("nobody", 1).status()).isEqualTo(404);
        assertThat(harness.post("/subscribers/x/ack", "{\"seq\":1}").status()).isEqualTo(400);
        assertThat(harness.get("/subscribers/bad%20name/next").status()).isEqualTo(400);
    }

    @Test
    void concurrentWriters_neverSkipAnEvent() throws Exception {
        assertThat(next("racer").status()).isEqualTo(204);
        var events = harness.store.events();
        var perWriter = 100;
        var appended = new CopyOnWriteArrayList<Long>();
        var writers = new ArrayList<Thread>();
        for (int w = 0; w < 2; w++) {
            writers.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < perWriter; i++) {
                    appended.add(events.append(none(), EventRow.Kind.QUEUED,
                                               EventPayload.eventPayload(some("s"), none(), none(), none(), none(), none(),
                                                                         none(), none()))
                                       .await(Pg.WAIT)
                                       .unwrap());
                }
            }));
        }
        var received = new ArrayList<Long>();
        var deadline = System.nanoTime() + 60_000_000_000L;
        while (received.size() < 2 * perWriter && System.nanoTime() < deadline) {
            var reply = next("racer");
            if (reply.status() == 200) {
                var seq = seq(reply);
                received.add(seq);
                ack("racer", seq);
            }
        }
        for (var writer : writers) {
            writer.join();
        }

        assertThat(received).hasSize(2 * perWriter);
        assertThat(received).isEqualTo(appended.stream().sorted().toList());
        for (int i = 1; i < received.size(); i++) {
            assertThat(received.get(i)).isEqualTo(received.get(i - 1) + 1);
        }
    }
}
