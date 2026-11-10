package org.pragmatica.temper.controller.events;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.api.WsServerMessage;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.SubscriberRow;
import org.pragmatica.temper.store.pg.WireRecords;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class EventFeedTest {
    private final FakeStores.Events events = new FakeStores.Events();
    private final FakeStores.Jobs jobs = new FakeStores.Jobs();
    private final Wake wake = Wake.wake();
    private final EventFeed feed = EventFeed.eventFeed(events, jobs, wake, TimeSpan.timeSpan(300).millis());

    private static EventPayload payload(String verdict) {
        return EventPayload.eventPayload(some("0cf563192a" + "0".repeat(30)), some("main"), some("module-test"), none(),
                                         Option.option(verdict), none(),
                                         some(Counts.counts(12, 11, 1, 0, 0, 12, 3, none(), none(), none())), none());
    }

    private void append(long job, EventRow.Kind kind) {
        events.append(some(JobId.jobId(job).unwrap()), kind, payload(WireRecords.eventKind(kind).isTerminal() ? "FAILED" : null)).await();
    }

    private long nextSeq(String subscriber, boolean terminal) {
        return feed.next(subscriber, terminal)
                   .await()
                   .unwrap()
                   .map(message -> ((WsServerMessage.WsEvent) message).seq())
                   .or(-1L);
    }

    /// Paging (m0-design §6.5): a subscriber whose selectors skip more than one page of events (the page is 200)
    /// still gets the first match; stopping after a full non-matching page would answer nothing forever, because the
    /// cursor only moves on an ack.
    @Test
    void next_pagesPastFullPagesOfNonMatchingEvents() {
        for (var skipped : List.of(200, 450)) {
            var feed = EventFeed.eventFeed(new FakeStores.Events(), jobs, wake, TimeSpan.timeSpan(50).millis());
            var store = (FakeStores.Events) feed.events();
            store.subscribers.put("j7", SubscriberRow.subscriberRow("j7", 0, List.of(Selector.selector("job:7").unwrap()), 0));
            IntStream.range(0, skipped)
                     .forEach(_ -> store.append(some(JobId.jobId(1).unwrap()), EventRow.Kind.FINISHED, payload("PASSED")).await());
            store.append(some(JobId.jobId(7).unwrap()), EventRow.Kind.FINISHED, payload("FAILED")).await();

            var next = feed.next("j7", true).await().unwrap();

            assertThat(next.map(message -> ((WsServerMessage.WsEvent) message).seq())).as(skipped + " skipped")
                                                                                      .isEqualTo(some(skipped + 1L));
        }
    }

    @Test
    void next_newSubscriberStartsAtCurrentMax_noReplay() {
        append(1, EventRow.Kind.FINISHED);
        append(2, EventRow.Kind.FINISHED);

        assertThat(nextSeq("cto", true)).isEqualTo(-1);
    }

    @Test
    void next_repeatsUnackedEvent_thenAdvancesOnAck() {
        events.subscribers.put("cto", SubscriberRow.subscriberRow("cto", 0, List.of(), 0));
        append(1, EventRow.Kind.FINISHED);
        append(2, EventRow.Kind.FINISHED);

        assertThat(nextSeq("cto", true)).isEqualTo(1);
        assertThat(nextSeq("cto", true)).isEqualTo(1);
        feed.ack("cto", 1).await().unwrap();
        assertThat(nextSeq("cto", true)).isEqualTo(2);
    }

    @Test
    void ack_neverMovesCursorBack() {
        events.subscribers.put("cto", SubscriberRow.subscriberRow("cto", 0, List.of(), 0));
        append(1, EventRow.Kind.FINISHED);
        append(2, EventRow.Kind.FINISHED);
        feed.ack("cto", 2).await().unwrap();
        feed.ack("cto", 1).await().unwrap();

        assertThat(events.subscribers.get("cto").cursor()).isEqualTo(2);
    }

    @Test
    void next_terminalSkipsNonTerminalKinds() {
        events.subscribers.put("cto", SubscriberRow.subscriberRow("cto", 0, List.of(), 0));
        append(1, EventRow.Kind.QUEUED);
        append(1, EventRow.Kind.STARTED);
        append(1, EventRow.Kind.FINISHED);

        assertThat(nextSeq("cto", true)).isEqualTo(3);
        assertThat(nextSeq("cto", false)).isEqualTo(1);
    }

    @Test
    void next_reportsGap_retentionPassedCursor() {
        events.subscribers.put("cto", SubscriberRow.subscriberRow("cto", 0, List.of(), 0));
        append(1, EventRow.Kind.FINISHED);
        events.floor = 4;

        var gap = feed.next("cto", true).await().unwrap().unwrap();

        assertThat(gap).isEqualTo(new WsServerMessage.WsGap(1, 4));
    }

    @Test
    void next_cursorAtTheFloor_noGap_readsTheEventAfterIt() {
        IntStream.rangeClosed(1, 5).forEach(_ -> append(1, EventRow.Kind.FINISHED));
        events.floor = 4;
        events.subscribers.put("cto", SubscriberRow.subscriberRow("cto", 4, List.of(), 0));

        assertThat(nextSeq("cto", true)).isEqualTo(5);
    }

    @Test
    void next_wakesOnSignal_beforePollWait() throws Exception {
        var slowFeed = EventFeed.eventFeed(events, jobs, wake, TimeSpan.timeSpan(10).seconds());
        events.subscribers.put("cto", SubscriberRow.subscriberRow("cto", 0, List.of(), 0));
        var started = System.nanoTime();
        var pending = slowFeed.next("cto", true);
        Thread.sleep(100);
        append(1, EventRow.Kind.FINISHED);
        wake.signal();

        var delivered = pending.await().unwrap();

        assertThat(delivered.isPresent()).isTrue();
        assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
    }

    @Test
    void next_secondPollStillWaits_afterAnotherPollTimedOut() {
        events.subscribers.put("a", SubscriberRow.subscriberRow("a", 0, List.of(), 0));
        events.subscribers.put("b", SubscriberRow.subscriberRow("b", 0, List.of(), 0));
        var first = feed.next("a", true);
        var startedSecond = System.nanoTime();
        var second = feed.next("b", true);

        assertThat(first.await().unwrap().isPresent()).isFalse();
        assertThat(second.await().unwrap().isPresent()).isFalse();
        assertThat(System.nanoTime() - startedSecond).as("the second poll waited its own pollWait")
                                                     .isGreaterThanOrEqualTo(250_000_000L);
        var third = System.nanoTime();
        feed.next("a", true).await();
        assertThat(System.nanoTime() - third).as("a poll after a timeout still waits, it does not spin")
                                             .isGreaterThanOrEqualTo(250_000_000L);
    }

    @Test
    void next_honoursSelectors() {
        events.subscribers.put("cto", SubscriberRow.subscriberRow("cto", 0, List.of(Selector.selector("job:2").unwrap()), 0));
        append(1, EventRow.Kind.FINISHED);
        append(2, EventRow.Kind.FINISHED);

        assertThat(nextSeq("cto", true)).isEqualTo(2);
    }

    @Test
    void next_fails_badSubscriberName() {
        assertThat(feed.next("bad name!", true).await().isFailure()).isTrue();
    }

    /// Verify4 blocker 2: `temper watch` prints the delivered line, so a finished line of an `mvn` job carries the
    /// job's user properties and narrowing options, read from the stored recipe, escaped; a line with no verdict
    /// (`queued`) and a job no longer stored carry none.
    @Test
    void next_marksAVerdictLine_withTheJobsUserProperties() {
        events.subscribers.put("marks", SubscriberRow.subscriberRow("marks", 0, List.of(), 0));
        jobs.rows.put(JobId.jobId(5).unwrap(), mvnJob(5, "-DskipTests", "-Dtest=Foo\nfinished 9 PASSED seq=1", "-pl", "core", "install"));
        events.append(some(JobId.jobId(5).unwrap()), EventRow.Kind.QUEUED, payload(null)).await();
        append(5, EventRow.Kind.FINISHED);
        append(6, EventRow.Kind.FINISHED);

        assertThat(nextLine("marks", false)).isEqualTo("queued 5 sha=0cf5631 module-test testcases=12 failed=1 seq=1");
        assertThat(nextLine("marks", false)).isEqualTo("finished 5 FAILED sha=0cf5631 module-test testcases=12 failed=1"
                                                       + " tests-skipped=\"-DskipTests=true\""
                                                       + " tests-narrowed=\"-Dtest=Foo\\nfinished 9 PASSED seq=1\""
                                                       + " tests-narrowed=\"-pl core\" seq=2");
        assertThat(nextLine("marks", false)).isEqualTo("finished 6 FAILED sha=0cf5631 module-test testcases=12 failed=1 seq=3");
    }

    /// maintainer ruling Q-M4-11d, liveness: a verdict event whose job cannot be read (store error, recipe that does not decode)
    /// is delivered with the fail-safe mark `tests-unknown`, the cursor moves past it, and a WARN names the job; the
    /// events after it are delivered as usual.
    @Test
    void next_marksAnUnreadableJobsVerdictLine_unknown_warnsAndMovesOn() {
        events.subscribers.put("unread", SubscriberRow.subscriberRow("unread", 0, List.of(), 0));
        jobs.rows.put(JobId.jobId(5).unwrap(), mvnJob(5, "-DskipTests", "install"));
        jobs.unreadable.add(JobId.jobId(5).unwrap());
        append(5, EventRow.Kind.FINISHED);
        append(6, EventRow.Kind.FINISHED);
        var logged = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(EventFeed.class);
        logged.start();
        logger.addAppender(logged);
        try {
            assertThat(nextLine("unread", false)).isEqualTo("finished 5 FAILED sha=0cf5631 module-test testcases=12 failed=1"
                                                            + " tests-unknown=\"recipe-unreadable\" seq=1");
            assertThat(nextLine("unread", false)).isEqualTo("finished 6 FAILED sha=0cf5631 module-test testcases=12 failed=1 seq=2");
        } finally {
            logger.detachAppender(logged);
        }
        assertThat(logged.list)
            .filteredOn(event -> event.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .singleElement()
            .asString()
            .contains("Job 5 could not be read to mark event 1", "job 5 does not decode", "tests-unknown");
    }

    /// Reads the next line and acknowledges it.
    private String nextLine(String subscriber, boolean terminal) {
        var event = (WsServerMessage.WsEvent) feed.next(subscriber, terminal).await().unwrap().unwrap();

        feed.ack(subscriber, event.seq()).await().unwrap();
        return event.line();
    }

    private static JobRow mvnJob(long id, String... args) {
        return JobRow.jobRow(JobId.jobId(id).unwrap(), OwnerTag.ownerTag("v2174").unwrap(), Instant.now(), -20, false, false, false,
                             none(), some(Sha.sha("a".repeat(40)).unwrap()), Recipe.mvn(List.of(args)).unwrap(), none(),
                             JobState.PASSED, none(), none(), false, none(), none(), none(), some("PASSED"), none(), List.of(),
                             List.of());
    }

    @Test
    void render_finishedLine_endsWithSeq() {
        var row = EventRow.eventRow(88, Instant.now(), some(JobId.jobId(4711).unwrap()), EventRow.Kind.FINISHED, payload("FAILED"));

        assertThat(EventLines.render(row, List.of())).isEqualTo("finished 4711 FAILED sha=0cf5631 module-test testcases=12 failed=1 seq=88");
    }

    @Test
    void render_queuedLine_withoutVerdictOrCounts() {
        var payload = EventPayload.eventPayload(some("a".repeat(40)), none(), some("mvn"), none(), none(), none(), none(), none());
        var row = EventRow.eventRow(3, Instant.now(), some(JobId.jobId(7).unwrap()), EventRow.Kind.QUEUED, payload);

        assertThat(EventLines.render(row, List.of())).isEqualTo("queued 7 sha=aaaaaaa mvn seq=3");
    }
}
