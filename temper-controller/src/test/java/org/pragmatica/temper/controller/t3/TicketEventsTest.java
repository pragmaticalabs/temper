package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.NewFleetEvent;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The ticket-diff table, row by row, on pure inputs (the unit side).
class TicketEventsTest {
    private static final Instant TAKEN = Instant.parse("2026-11-04T12:00:00Z");
    private static final Instant NOW = TAKEN.plusSeconds(30);
    private static final long BEFORE_NS = TAKEN.getEpochSecond() * 1_000_000_000L - 5_000_000_000L;
    private static final long AFTER_NS = TAKEN.getEpochSecond() * 1_000_000_000L + 5_000_000_000L;

    private final TicketDiff diff = TicketEvents.ticketEvents();

    private static BbTicket ticket(String id, String dir, String... kv) {
        var fields = new LinkedHashMap<String, String>();

        for (var i = 0; i < kv.length; i += 2) {
            fields.put(kv[i], kv[i + 1]);
        }

        return BbTicket.bbTicket(1, id, dir, none(), fields, List.of());
    }

    private static Option<BbSnapshot> baseline(BbTicket... tickets) {
        return some(BbSnapshot.bbSnapshot("h", TAKEN, some("present"), List.of(tickets)));
    }

    private List<String> kinds(Option<BbSnapshot> baseline, BbTicket... now) {
        return diff.events("h",
                           NOW,
                           List.of(now),
                           baseline)
                   .stream()
                   .map(NewFleetEvent::kind)
                   .toList();
    }

    @Test
    void noBaseline_noEvent_andTheBaselineIsLogged() {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(TicketEvents.class);

        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(kinds(none(), ticket("a", "waiting"), ticket("b", "running"), ticket("c", "done"))).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }

        var messages = new ArrayList<String>();

        appender.list.forEach(event -> {
            if (event.getLevel() == Level.INFO) {
                messages.add(event.getFormattedMessage());
            }
        });
        assertThat(messages).containsExactly("baseline h");
    }

    @Test
    void absentToWaiting_queued() {
        assertThat(kinds(baseline(), ticket("a", "waiting"))).containsExactly("ticket-queued");
    }

    @Test
    void absentToRunning_queuedThenStarted() {
        assertThat(kinds(baseline(), ticket("a", "running"))).containsExactly("ticket-queued", "ticket-started");
    }

    @Test
    void absentToDone_newerThanTheBaseline_queuedStartedFinished() {
        assertThat(kinds(baseline(),
                         ticket("a", "done", "created", Long.toString(AFTER_NS), "started", "1", "state", "finished"))).containsExactly("ticket-queued",
                                                                                                                                        "ticket-started",
                                                                                                                                        "ticket-finished");
    }

    @Test
    void absentToDone_withoutStarted_hasNoStartedEvent() {
        assertThat(kinds(baseline(), ticket("a", "done", "created", Long.toString(AFTER_NS), "state", "expired"))).containsExactly("ticket-queued",
                                                                                                                                   "ticket-finished");
    }

    @Test
    void absentToDone_older_noEvent() {
        assertThat(kinds(baseline(), ticket("a", "done", "created", Long.toString(BEFORE_NS), "state", "finished"))).isEmpty();
        assertThat(kinds(baseline(), ticket("b", "done", "state", "finished"))).as("no created at all").isEmpty();
    }

    /// `created`/1e9 > `taken_at`, strictly. `taken_at` is a timestamptz (microsecond precision), compared as exact
    /// epoch nanoseconds: equal means no events, one nanosecond either side decides.
    @Test
    void absentToDone_createdVersusTakenAt_isStrict_atNanosecondResolution() {
        var taken = TAKEN.plusNanos(123_456_000L);
        var takenNs = taken.getEpochSecond() * 1_000_000_000L + taken.getNano();
        var base = some(BbSnapshot.bbSnapshot("h", taken, some("present"), List.of()));

        assertThat(kinds(base, done(takenNs))).as("equal").isEmpty();
        assertThat(kinds(base, done(takenNs - 1))).as("a nanosecond older").isEmpty();
        assertThat(kinds(base, done(takenNs + 1))).as("a nanosecond newer").containsExactly("ticket-queued", "ticket-finished");
        assertThat(kinds(baseline(), done(TAKEN.getEpochSecond() * 1_000_000_000L))).as("whole-second equal").isEmpty();
    }

    private static BbTicket done(long createdNs) {
        return ticket("a", "done", "created", Long.toString(createdNs), "state", "finished");
    }

    @Test
    void aTwentyDigitCreated_yieldsNoEvent() {
        assertThat(kinds(baseline(), ticket("a", "done", "created", "99999999999999999999", "state", "finished"))).isEmpty();
        assertThat(kinds(baseline(), ticket("b", "done", "created", "10000000000000000000", "state", "finished"))).isEmpty();
    }

    @Test
    void pr_mustBeOneToTenDigitsWithoutLeadingZero() {
        for (var pr : List.of("0", "007", "12345678901", "-1", "4x")) {
            assertThat(prPayload(pr)).as(pr).doesNotContain("\"pr\"");
        }
        assertThat(prPayload("1")).contains("\"pr\":1");
        assertThat(prPayload("9999999999")).contains("\"pr\":9999999999");
    }

    private String prPayload(String pr) {
        return diff.events("h", NOW, List.of(ticket("t", "waiting", "pr", pr)), baseline()).getFirst().payload();
    }

    @Test
    void waitingToRunning_started() {
        assertThat(kinds(baseline(ticket("a", "waiting")), ticket("a", "running"))).containsExactly("ticket-started");
    }

    @Test
    void waitingToDone_startedOnlyIfSet_thenFinished() {
        assertThat(kinds(baseline(ticket("a", "waiting")), ticket("a", "done", "started", "1"))).containsExactly("ticket-started",
                                                                                                                 "ticket-finished");
        assertThat(kinds(baseline(ticket("a", "waiting")), ticket("a", "done"))).containsExactly("ticket-finished");
    }

    @Test
    void runningToDone_finished_withStateAndRcFromTheDoneFile() {
        var events = diff.events("h",
                                 NOW,
                                 List.of(ticket("a", "done", "owner", "o", "state", "finished", "rc", "3")),
                                 baseline(ticket("a", "running", "owner", "o")));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().payload()).isEqualTo("{\"host\":\"h\",\"ticket\":\"a\",\"owner\":\"o\",\"state\":\"finished\",\"rc\":\"3\"}");
    }

    @Test
    void leavingTheListing_isFinishedGone_withoutRc() {
        var events = diff.events("h",
                                 NOW,
                                 List.of(),
                                 baseline(ticket("a", "running", "rc", "9"), ticket("b", "waiting")));

        assertThat(events).extracting(NewFleetEvent::kind).containsExactly("ticket-finished", "ticket-finished");
        assertThat(events).extracting(NewFleetEvent::payload)
                  .allSatisfy(payload -> assertThat(payload).contains("\"state\":\"gone\"")
                                                   .doesNotContain("\"rc\""));
    }

    @Test
    void unchanged_andDoneToAnything_noEvents() {
        var running = ticket("a", "running");
        var done = ticket("d", "done");

        assertThat(kinds(baseline(running, ticket("w", "waiting"), done), running, ticket("w", "waiting"), done)).isEmpty();
        assertThat(kinds(baseline(done), ticket("d", "running"))).as("done to anything").isEmpty();
        assertThat(kinds(baseline(done))).as("done leaving the window").isEmpty();
    }

    @Test
    void withinOneRound_byTicketId_thenInTheTableOrder() {
        var events = diff.events("h",
                                 NOW,
                                 List.of(ticket("b", "running"), ticket("a", "running"), ticket("c", "waiting")),
                                 baseline());

        assertThat(events).extracting(NewFleetEvent::subject).containsExactly("h/a", "h/a", "h/b", "h/b", "h/c");
        assertThat(events).extracting(NewFleetEvent::kind)
                  .containsExactly("ticket-queued", "ticket-started", "ticket-queued", "ticket-started", "ticket-queued");
    }

    @Test
    void theEvent_isControllers_notCatchUp_andCarriesTheContractedPayloadInOrder() {
        var full = ticket("t1",
                          "waiting",
                          "owner",
                          "o",
                          "agent",
                          "ag",
                          "lane",
                          "l",
                          "pr",
                          "42",
                          "session_label",
                          "CTO",
                          "co",
                          "/w/co",
                          "output",
                          "/w/co/.bb/build.log");
        var event = diff.events("h", NOW, List.of(full), baseline()).getFirst();

        assertThat(event.source()).isEqualTo("controller");
        assertThat(event.catchUp()).isFalse();
        assertThat(event.observedAt()).isEqualTo(NOW);
        assertThat(event.subject()).isEqualTo("h/t1");
        assertThat(event.payload()).isEqualTo("{\"host\":\"h\",\"ticket\":\"t1\",\"owner\":\"o\",\"agent\":\"ag\",\"lane\":\"l\",\"pr\":42,\"sessionLabel\":\"CTO\",\"co\":\"/w/co\",\"output\":\"/w/co/.bb/build.log\"}");
    }
}
