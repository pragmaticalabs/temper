package org.pragmatica.temper.controller.events;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.mvn.UserProperties.TestEffect;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.protocol.api.EventKind;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.UserPropertyDto;
import org.pragmatica.temper.protocol.api.WsServerMessage;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventStore;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.Pending;
import org.pragmatica.temper.store.api.SubscriberRow;
import org.pragmatica.temper.store.pg.WireRecords;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Subscriber event delivery with a window of one (spec §8.3, m0-design §6.5), over long-poll because rc4's
/// WebSocket does not reach its handler (the §2.4 spike):
///
/// - [#next] answers the oldest matching event with `seq > cursor` (kinds `finished`/`cancelled` when `terminal`;
///   empty selectors mean every job), or a gap `{from, to: floor}` when retention deleted events after the cursor.
///   Asking again without an ack answers the same event. A poll that delivers nothing advances the cursor over what
///   it scanned (skip-ack, below).
/// - [#ack] moves the cursor (`GREATEST(cursor, seq)`).
/// - [#selectors] changes the subscriber's typed selectors ([Selector], m2-design §2), creating it when absent.
/// - With nothing pending, [#next] waits for the in-process wake signal, at most `pollWait`, then looks once more and
///   answers none.
///
/// **Guarantee:** every matching event is delivered at least once; a crash between print and ack repeats at most
/// that one event (one unacked event at a time); nothing is skipped silently, because events commit in `seq` order
/// under the store's advisory lock and the cursor only grows, and events deleted by retention are delivered as one
/// gap.
///
/// **Delivery under a selector change (m2-design §2.1, R-M2-12):** a poll delivers an event iff its `seq` is above the
/// cursor at that poll, its kind is admitted and it matches the selector set THAT poll read (once, with the cursor and
/// the set's version, at its start). A poll that scanned up to `seq` S and matched nothing advances the cursor to S
/// (skip-ack, [EventStore#skipTo]), but only if the set's version is still the one it read: a selector change that
/// lands mid-poll stops the advance, and the next poll scans again with the new set. A matched event is never passed
/// without its ack, so delivery stays at least once. Consequences: a selector added later catches only events after
/// the current cursor (`subscribe` prints the state of the jobs it already matches); a removal applies from the next
/// poll, and an event already delivered is acked as usual; a poll already waiting when a change commits keeps its set;
/// a gap names only events deleted by retention before the cursor passed them. A poll reads every kind and filters by
/// kind itself, so the advance covers what it read of every kind, but it stops below the first selected event of a
/// kind the poll does not admit (a `started` under `--terminal`): the skip-ack never passes it. The cursor is one per
/// subscriber, so an ack of a later delivered event does pass it, and a gap from that stopping point can cover events a
/// poll read past; a subscriber is meant for one mode. Under `--terminal` with every job selected, each `queued` or
/// `started` holds the advance, so polls re-read from it until the next ack (as before R-M2-12).
///
/// **Marks (m4-design §5.1):** a delivered event that states a verdict has its line carry the job's
/// user properties and narrowing options ([EventLines]), read from the stored recipe at delivery as every other view
/// derives them; a job no longer stored (retention) has none to show. When the job's lookup fails (the store, or a
/// stored recipe that does not decode), the line carries the fail-safe mark `tests-unknown=recipe-unreadable`, a WARN
/// names the job, and the event is delivered as usual, so one unreadable job never stalls the feed (maintainer ruling
/// Q-M4-11d): the line never reads as tested, and the operator sees which job to look at.
public record EventFeed(EventStore events, JobStore jobs, Wake wake, TimeSpan pollWait, int page) {
    /// The `subscribers.name` alphabet.
    public static final Pattern SUBSCRIBER = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger(EventFeed.class);

    /// The mark of a verdict line whose job could not be read: tests unknown.
    static final UserPropertyDto UNREADABLE = UserPropertyDto.userPropertyDto("recipe-unreadable",
                                                                              some(TestEffect.UNKNOWN.wire()));

    private static final Set<String> TERMINAL = Set.of(EventKind.FINISHED.wire(), EventKind.CANCELLED.wire());

    public static EventFeed eventFeed(EventStore events, JobStore jobs, Wake wake, TimeSpan pollWait) {
        return new EventFeed(events, jobs, wake, pollWait, 200);
    }

    public Promise<Option<WsServerMessage>> next(String subscriber, boolean terminal) {
        return subscriberName(subscriber).async()
                             .flatMap(events::subscriber)
                             .flatMap(row -> deliverable(row, terminal));
    }

    public Promise<Unit> ack(String subscriber, long seq) {
        return subscriberName(subscriber).async()
                             .flatMap(name -> events.ack(name, seq));
    }

    /// `(selectors ∪ add) − remove` in one store statement; the row as it is after the change.
    public Promise<SubscriberRow> selectors(String subscriber, List<Selector> add, List<Selector> remove) {
        return subscriberName(subscriber).async()
                             .flatMap(name -> events.selectors(name, add, remove));
    }

    public static Result<String> subscriberName(String raw) {
        return Verify.ensure(raw, Verify.Is::matches, SUBSCRIBER, invalid("subscriber"));
    }

    /// One look's outcome: what it delivers, the highest `seq` it read (every kind), and the lowest selected event of a
    /// kind this poll does not admit, which caps the skip-ack.
    record Look(Option<WsServerMessage> found, long scanned, Option<Long> held) {}

    /// The wake promise is taken BEFORE looking, so an event committed between the look and the wait wakes us.
    private Promise<Option<WsServerMessage>> deliverable(SubscriberRow row, boolean terminal) {
        var signal = wake.next();

        return look(row, kinds(terminal), row.cursor(), none()).flatMap(first -> orWait(first, signal, row, terminal));
    }

    private Promise<Option<WsServerMessage>> orWait(Look first,
                                                    Promise<Unit> signal,
                                                    SubscriberRow row,
                                                    boolean terminal) {
        return first.found()
                    .isPresent()
               ? Promise.success(first.found())
               : afterWait(signal, row, terminal, first);
    }

    /// Design-out: the wait's timeout is the 30 s backstop re-poll, not a failure, so either outcome of the wait
    /// leads to one more look. It starts where the first look stopped reading: the events below were read with this
    /// poll's set and matched nothing, so reading them again could only announce their deletion as a false gap.
    private Promise<Option<WsServerMessage>> afterWait(Promise<Unit> signal,
                                                       SubscriberRow row,
                                                       boolean terminal,
                                                       Look first) {
        return signal.timeout(pollWait)
                     .fold(_ -> look(row,
                                     kinds(terminal),
                                     first.scanned(),
                                     first.held()))
                     .map(Look::found);
    }

    private static Set<String> kinds(boolean terminal) {
        return terminal
               ? TERMINAL
               : Set.of();
    }

    /// Pages through events after `after` until one is admitted and matches the selectors, or none is left. Each page
    /// is one `EventStore.pending` read of every kind, so the retention floor and the events come from one snapshot
    /// (events deleted above `after` are announced as a gap instead of being skipped, m1-design §4.4), and the highest
    /// `seq` read covers every kind: the kind filter applies here, not in the store. Every event between the cursor
    /// and `after` was read and did not match, so the gap starts after `after`.
    private Promise<Look> look(SubscriberRow row, Set<String> kinds, long after, Option<Long> held) {
        return events.pending(after,
                              Set.of(),
                              page)
                     .flatMap(pending -> gapOrMatch(row, kinds, after, held, pending));
    }

    private Promise<Look> gapOrMatch(SubscriberRow row,
                                     Set<String> kinds,
                                     long after,
                                     Option<Long> held,
                                     Pending pending) {
        return switch (pending) {
            case Pending.Gap gap -> Promise.success(new Look(some(new WsServerMessage.WsGap(gap.from(), gap.to())),
                                                             after,
                                                             held));
            case Pending.Events found -> matchIn(row,
                                                 kinds,
                                                 scannedTo(found.rows(), after),
                                                 heldIn(row, kinds, held, found.rows()),
                                                 found.rows());
        };
    }

    private Promise<Look> matchIn(SubscriberRow row,
                                  Set<String> kinds,
                                  long scanned,
                                  Option<Long> held,
                                  List<EventRow> batch) {
        var match = Option.from(batch.stream().filter(event -> wanted(row, kinds, event)).findFirst());

        return match.map(event -> marked(event).map(message -> new Look(some(message),
                                                                        scanned,
                                                                        held)))
                    .or(() -> noMatchUpTo(row,
                                          kinds,
                                          scanned,
                                          held,
                                          batch.size()));
    }

    /// A full page may have more after it; a short one ends the scan.
    private Promise<Look> noMatchUpTo(SubscriberRow row, Set<String> kinds, long scanned, Option<Long> held, int size) {
        return size < page
               ? skipped(row, scanned, held)
               : look(row, kinds, scanned, held);
    }

    /// Skip-ack (R-M2-12): nothing up to `scanned` matched the set this poll read, so the cursor moves there, under that
    /// set's version only; but never onto a selected event of a kind this poll does not admit (`held`), so a plain
    /// `watch` of the same subscriber still gets the `started` a `--terminal` poll passed over. A failed advance fails
    /// the poll (no recovery): the next poll scans the same events again.
    private Promise<Look> skipped(SubscriberRow row, long scanned, Option<Long> held) {
        var target = held.map(seq -> Math.min(scanned, seq - 1)).or(scanned);
        var nothing = new Look(none(), scanned, held);

        return target > row.cursor()
               ? events.skipTo(row.name(),
                               row.version(),
                               target)
                       .map(_ -> nothing)
               : Promise.success(nothing);
    }

    /// The lowest selected event this poll may not deliver, the first one found.
    private static Option<Long> heldIn(SubscriberRow row, Set<String> kinds, Option<Long> held, List<EventRow> batch) {
        return held.isPresent()
               ? held
               : Option.from(batch.stream().filter(event -> heldBack(row, kinds, event)).findFirst()).map(EventRow::seq);
    }

    private static boolean wanted(SubscriberRow row, Set<String> kinds, EventRow event) {
        return admitted(kinds, event) && selected(row.selectors(), event);
    }

    private static boolean heldBack(SubscriberRow row, Set<String> kinds, EventRow event) {
        return ! admitted(kinds, event) && selected(row.selectors(), event);
    }

    /// `kinds` holds wire kinds; empty admits every kind.
    private static boolean admitted(Set<String> kinds, EventRow event) {
        return kinds.isEmpty() || kinds.contains(WireRecords.eventKind(event.kind()).wire());
    }

    /// The highest `seq` read so far: the page's last event, or `after` when the page is empty.
    private static long scannedTo(List<EventRow> batch, long after) {
        return batch.isEmpty()
               ? after
               : Math.max(after,
                          batch.getLast().seq());
    }

    /// A typed selector matches its own field of the event only (F3); none means every job.
    static boolean selected(List<Selector> selectors, EventRow event) {
        return Selector.selects(selectors,
                                event.job(),
                                event.payload().sha(),
                                event.payload().ref());
    }

    /// The event's message, its line marked with the job's user properties when it states a verdict (only an `mvn`
    /// job has any).
    private Promise<WsServerMessage> marked(EventRow event) {
        return event.job()
                    .filter(_ -> event.payload()
                                      .verdict()
                                      .isPresent())
                    .map(job -> jobs.job(job)
                                    .map(row -> row.map(stored -> UserPropertyDto.of(RecipeDto.from(stored.recipe())))
                                                   .or(List.of()))
                                    .recover(cause -> unreadable(event, job, cause)))
                    .or(() -> Promise.success(List.of()))
                    .map(properties -> message(event, properties));
    }

    /// The fail-safe mark of an event whose job could not be read, and the operator's WARN naming the job.
    private static List<UserPropertyDto> unreadable(EventRow event, JobId job, Cause cause) {
        log.warn("Job {} could not be read to mark event {} ({}); its line is marked {} and delivered",
                 job,
                 event.seq(),
                 cause.message(),
                 UNREADABLE.token());

        return List.of(UNREADABLE);
    }

    static WsServerMessage message(EventRow event, List<UserPropertyDto> userProperties) {
        return new WsServerMessage.WsEvent(event.seq(),
                                           event.at(),
                                           event.job().map(JobId::value),
                                           WireRecords.eventKind(event.kind()),
                                           event.payload().verdict(),
                                           EventLines.render(event, userProperties));
    }
}
