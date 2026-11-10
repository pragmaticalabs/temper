package org.pragmatica.temper.controller.events;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.api.WsServerMessage;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.SubscriberRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Typed selectors in [EventFeed] over the fake store (m2-design §2.2, F3): each selector matches its own field of the
/// event only, a ref selector never matches a job submitted by SHA, and a poll matches against the set it read.
class EventFeedSelectorTest {
    private static final String SHA_1 = "1".repeat(40);
    private static final String SHA_2 = "2".repeat(40);
    private final FakeStores.Events events = new FakeStores.Events();
    private final FakeStores.Jobs jobs = new FakeStores.Jobs();
    private final Wake wake = Wake.wake();
    private final EventFeed feed = EventFeed.eventFeed(events, jobs, wake, TimeSpan.timeSpan(200).millis());

    private long finished(long job, String sha, Option<String> ref) {
        return events.append(some(JobId.jobId(job).unwrap()),
                             EventRow.Kind.FINISHED,
                             EventPayload.eventPayload(some(sha),
                                                       ref,
                                                       some("gate"),
                                                       none(),
                                                       some("PASSED"),
                                                       none(),
                                                       some(Counts.counts(1, 1, 0, 0, 0, 1, 0, none(), none(), none())),
                                                       none()))
                     .await()
                     .unwrap();
    }

    private void select(String... wire) {
        feed.selectors("cto",
                       List.of(wire).stream().map(raw -> Selector.selector(raw).unwrap()).toList(),
                       List.of())
            .await()
            .unwrap();
    }

    private void deselect(String wire) {
        feed.selectors("cto", List.of(), List.of(Selector.selector(wire).unwrap())).await().unwrap();
    }

    /// Every event the subscriber is delivered, acking each, until a poll answers none.
    private List<Long> deliveredJobs() {
        var jobs = new ArrayList<Long>();
        var next = feed.next("cto", true).await().unwrap();
        while (next.isPresent()) {
            var event = (WsServerMessage.WsEvent) next.unwrap();
            jobs.add(event.job().unwrap());
            feed.ack("cto", event.seq()).await().unwrap();
            next = feed.next("cto", true).await().unwrap();
        }
        return jobs;
    }

    @Test
    void next_eachTypedSelector_matchesItsOwnFieldOnly() {
        select("job:2", "sha:" + SHA_2, "ref:feature");
        finished(1, SHA_1, some("main"));
        finished(2, SHA_1, some("main"));
        finished(3, SHA_2, some("main"));
        finished(4, SHA_1, some("feature"));
        finished(5, SHA_1, none());

        assertThat(deliveredJobs()).containsExactly(2L, 3L, 4L);
    }

    /// F3: a ref named `4711` selected job 4711 under untyped equality, and a job selector selected a ref `4711`.
    @Test
    void next_refNamed4711_doesNotSelectJob4711_andJob4711_doesNotSelectRef4711() {
        select("ref:4711");
        finished(4711, SHA_1, some("main"));
        finished(9, SHA_1, some("4711"));

        assertThat(deliveredJobs()).as("ref:4711 selects the job whose ref is 4711").containsExactly(9L);

        deselect("ref:4711");
        select("job:4711");
        finished(9, SHA_1, some("4711"));
        finished(4711, SHA_1, some("main"));

        assertThat(deliveredJobs()).as("job:4711 selects job 4711").containsExactly(4711L);
    }

    /// A job submitted by SHA has no ref in its payload: a ref selector never selects it, even at the ref's SHA.
    @Test
    void next_refSelector_neverSelectsAJobSubmittedBySha() {
        select("ref:main");
        finished(1, SHA_1, none());
        finished(2, SHA_1, some("main"));

        assertThat(deliveredJobs()).containsExactly(2L);
    }

    /// A short SHA can never be a selector (the wire form needs 40 hex), so no poll waits on one that matches nothing.
    @Test
    void selectors_refuseAShortShaOnTheWire() {
        assertThat(Selector.selector("sha:" + SHA_1.substring(0, 7)).isFailure()).isTrue();
    }

    /// §2.1, R-M2-12 condition 1: a poll matches against the set it read when it began. A poll already waiting when a
    /// selector is added keeps its own set, and its skip-ack over the new job's event is refused (the version moved), so
    /// the next poll reads the new set and still delivers the event.
    /// The 300 ms pause lets the poll reach its wait (pragmatica may run its steps on another thread); its own wait is
    /// 5 s, so the wake, not the timeout, ends it.
    @Test
    void next_aWaitingPoll_keepsTheSetItRead_theNextPollReadsTheNewOne() throws InterruptedException {
        var slow = EventFeed.eventFeed(events, jobs, wake, TimeSpan.timeSpan(5).seconds());
        select("job:1");
        assertThat(feed.next("cto", true).await().unwrap().isPresent()).as("nothing yet").isFalse();

        var waiting = slow.next("cto", true);
        Thread.sleep(300);
        select("job:2");
        finished(2, SHA_1, some("main"));
        wake.signal();

        assertThat(waiting.await().unwrap().isPresent()).as("the waiting poll read {job:1}").isFalse();
        assertThat(deliveredJobs()).as("the next poll reads {job:1, job:2}").containsExactly(2L);
    }

    /// R-M2-12: a gap names only events retention deleted before any poll scanned them, here job 2's.
    @Test
    void next_aGap_namesEventsDeletedBeforeAnyPollScannedThem() {
        select("job:1");
        finished(2, SHA_1, some("main"));
        finished(1, SHA_1, some("main"));
        events.floor = 1;

        assertThat(feed.next("cto", true).await().unwrap()).isEqualTo(some(new WsServerMessage.WsGap(1, 1)));
    }

    /// R-M2-12: once a poll scanned past job 2's event, deleting it is no gap: the cursor is already over it.
    @Test
    void next_noGap_forEventsAPollAlreadyScanned() {
        select("job:1");
        finished(2, SHA_1, some("main"));
        assertThat(feed.next("cto", true).await().unwrap().isPresent()).isFalse();
        finished(1, SHA_1, some("main"));
        events.floor = 1;

        assertThat(deliveredJobs()).containsExactly(1L);
    }

    /// R-M2-12 (a): a poll that matched nothing advances the cursor to the highest `seq` it scanned, across pages.
    @Test
    void skipAck_movesTheCursorOverEveryScannedEvent_acrossPages() {
        select("job:1");
        IntStream.range(0, 450).forEach(_ -> finished(2, SHA_1, some("main")));

        assertThat(feed.next("cto", true).await().unwrap().isPresent()).isFalse();
        assertThat(events.subscribers.get("cto").cursor()).isEqualTo(450);
    }

    /// R-M2-12 (a): a matched, unacked event is never passed: the cursor stays below it until its ack.
    @Test
    void skipAck_neverPassesAMatchedUnackedEvent() {
        select("job:1");
        finished(2, SHA_1, some("main"));
        var matched = finished(1, SHA_1, some("main"));
        finished(3, SHA_1, some("main"));

        assertThat(feed.next("cto", true).await().unwrap().map(message -> ((WsServerMessage.WsEvent) message).seq())).isEqualTo(some(matched));
        assertThat(events.subscribers.get("cto").cursor()).as("no skip-ack past it").isZero();
        assertThat(feed.next("cto", true).await().unwrap().map(message -> ((WsServerMessage.WsEvent) message).seq())).isEqualTo(some(matched));
    }

    /// A `--terminal` poll's skip-ack stops below a selected event of a kind it does not admit, so a plain `watch` of the
    /// same subscriber still gets it (verifier 3, finding 1).
    @Test
    void skipAck_underTerminal_stopsBelowASelectedNonTerminalEvent() {
        select("sha:" + SHA_1);
        var queued = events.append(some(JobId.jobId(1).unwrap()),
                                   EventRow.Kind.QUEUED,
                                   EventPayload.eventPayload(some(SHA_1), none(), some("gate"), none(), none(), none(), none(), none()))
                           .await()
                           .unwrap();
        finished(2, SHA_2, some("main"));

        assertThat(feed.next("cto", true).await().unwrap().isPresent()).isFalse();
        assertThat(events.subscribers.get("cto").cursor()).as("capped below the queued event").isZero();
        assertThat(feed.next("cto", false).await().unwrap().map(message -> ((WsServerMessage.WsEvent) message).seq())).isEqualTo(some(queued));
    }

    /// The cap holds across pages (here two events a page): found on page 1 with the scan ending on page 2, and found on
    /// page 2 with the scan ending on page 3 (verifier 4, finding 3).
    @Test
    void skipAck_underTerminal_capCarriesAcrossPages() {
        var paged = new EventFeed(events, jobs, wake, TimeSpan.timeSpan(50).millis(), 2);
        select("sha:" + SHA_1);
        var queued = queued(1, SHA_1);
        finished(2, SHA_2, some("main"));
        finished(3, SHA_2, some("main"));

        assertThat(paged.next("cto", true).await().unwrap().isPresent()).isFalse();
        assertThat(events.subscribers.get("cto").cursor()).as("held on page 1").isEqualTo(queued - 1);

        events.subscribers.put("cto", SubscriberRow.subscriberRow("cto", queued, events.subscribers.get("cto").selectors(), 1));
        finished(4, SHA_2, some("main"));
        var later = queued(5, SHA_1);
        finished(6, SHA_2, some("main"));

        assertThat(paged.next("cto", true).await().unwrap().isPresent()).isFalse();
        assertThat(events.subscribers.get("cto").cursor()).as("held on page 2").isEqualTo(later - 1);
    }

    private long queued(long job, String sha) {
        return events.append(some(JobId.jobId(job).unwrap()),
                             EventRow.Kind.QUEUED,
                             EventPayload.eventPayload(some(sha), none(), some("gate"), none(), none(), none(), none(), none()))
                     .await()
                     .unwrap();
    }

    /// The re-look after the wait starts where the first look stopped reading, so retention deleting what the first
    /// look already skipped is no gap (verifier 3, finding 2).
    @Test
    void next_theReLookAfterTheWait_announcesNoGapForWhatTheFirstLookSkipped() throws InterruptedException {
        var slow = EventFeed.eventFeed(events, jobs, wake, TimeSpan.timeSpan(5).seconds());
        select("job:1");
        finished(2, SHA_1, some("main"));

        var waiting = slow.next("cto", true);
        Thread.sleep(300);
        assertThat(events.subscribers.get("cto").cursor()).as("the first look skipped").isEqualTo(1);
        events.floor = 1;
        wake.signal();

        assertThat(waiting.await().unwrap()).isEqualTo(Option.none());
    }

    /// F4 (ruled R-M2-9): removing the last selector widens the subscriber back to every job, from the cursor on: the
    /// event a narrowed poll already scanned is not replayed (R-M2-12).
    @Test
    void next_removingTheLastSelector_widensToEveryJob_fromTheCursor() {
        select("job:1");
        finished(2, SHA_1, some("main"));
        assertThat(feed.next("cto", true).await().unwrap().isPresent()).isFalse();

        deselect("job:1");
        finished(3, SHA_1, some("main"));

        assertThat(deliveredJobs()).containsExactly(3L);
    }
}
