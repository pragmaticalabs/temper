package org.pragmatica.temper.controller.it;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.events.EventFeed;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.protocol.api.WsServerMessage;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventStore;
import org.pragmatica.temper.store.api.Pending;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.temper.store.api.SubscriberRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// R-M2-12 condition 1 on Postgres, made deterministic: a `subscribe` that lands while a poll scans (here: after the
/// poll read the subscriber, before it reads the events) must not let that poll's skip-ack pass an event the new
/// selector matches. The poll scans with the set it read, matches nothing, and its `skipTo` under the old version
/// changes nothing; the next poll delivers the event.
@Tag("pg")
class SkipAckRaceIT {
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private Store store;

    @BeforeEach
    void start() {
        store = Pg.freshStore();
    }

    @AfterEach
    void stop() throws Exception {
        store.close();
    }

    /// The real event store, with one hook run once, just before the first `pending` read. The hook is chained, never
    /// awaited: the store's callbacks run on its own threads, and blocking one of them would hang the poll.
    record Gated(EventStore real, Supplier<Promise<SubscriberRow>> hook, AtomicBoolean fired) implements EventStore {
        @Override
        public Promise<Long> append(Option<JobId> job, EventRow.Kind kind, EventPayload payload) {
            return real.append(job, kind, payload);
        }

        @Override
        public Promise<List<EventRow>> after(long seq, Set<String> kinds, int limit) {
            return real.after(seq, kinds, limit);
        }

        @Override
        public Promise<Pending> pending(long cursor, Set<String> kinds, int limit) {
            return fired.compareAndSet(false, true)
                   ? hook.get().flatMap(_ -> real.pending(cursor, kinds, limit))
                   : real.pending(cursor, kinds, limit);
        }

        @Override
        public Promise<Long> floor() {
            return real.floor();
        }

        @Override
        public Promise<SubscriberRow> subscriber(String name) {
            return real.subscriber(name);
        }

        @Override
        public Promise<Unit> ack(String name, long seq) {
            return real.ack(name, seq);
        }

        @Override
        public Promise<SubscriberRow> selectors(String name, List<Selector> add, List<Selector> remove) {
            return real.selectors(name, add, remove);
        }

        @Override
        public Promise<Unit> skipTo(String name, long version, long seq) {
            return real.skipTo(name, version, seq);
        }
    }

    private static List<Selector> sha(String hex) {
        return List.of(Selector.selector("sha:" + hex).unwrap());
    }

    private long finishedAt(String sha) {
        return store.events()
                    .append(none(),
                            EventRow.Kind.FINISHED,
                            EventPayload.eventPayload(some(sha), none(), some("gate"), none(), some("PASSED"), none(), none(), none()))
                    .await(Pg.WAIT)
                    .unwrap();
    }

    @Test
    void subscribeMidPoll_neverLetsTheSkipAckPassTheNewSelectorsEvent() {
        var events = store.events();
        events.selectors("cto", sha(SHA_A), List.of()).await(Pg.WAIT).unwrap();
        var matchedLater = finishedAt(SHA_B);
        Supplier<Promise<SubscriberRow>> subscribeMidPoll = () -> events.selectors("cto", sha(SHA_B), List.of());
        var gated = new Gated(events, subscribeMidPoll, new AtomicBoolean());
        var feed = EventFeed.eventFeed(gated, store.jobs(), Wake.wake(), TimeSpan.timeSpan(100).millis());

        assertThat(feed.next("cto", true).await(Pg.WAIT).unwrap().isPresent()).as("the poll scanned with {sha:A}")
                                                                              .isFalse();
        assertThat(events.subscriber("cto").await(Pg.WAIT).unwrap().cursor()).as("its skip-ack was refused").isZero();

        var next = feed.next("cto", true).await(Pg.WAIT).unwrap();

        assertThat(next.map(message -> ((WsServerMessage.WsEvent) message).seq())).isEqualTo(some(matchedLater));
    }

    /// The control: with no change during the scan, the same poll does advance the cursor over the event.
    @Test
    void noChangeMidPoll_theSkipAckAdvances() {
        var events = store.events();
        events.selectors("cto", sha(SHA_A), List.of()).await(Pg.WAIT).unwrap();
        var skipped = finishedAt(SHA_B);
        var feed = EventFeed.eventFeed(events, store.jobs(), Wake.wake(), TimeSpan.timeSpan(100).millis());

        assertThat(feed.next("cto", true).await(Pg.WAIT).unwrap().isPresent()).isFalse();
        assertThat(events.subscriber("cto").await(Pg.WAIT).unwrap().cursor()).isEqualTo(skipped);
    }
}
