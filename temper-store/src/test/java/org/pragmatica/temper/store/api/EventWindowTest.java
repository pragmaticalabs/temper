package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [EventWindow#eventWindow] over hand-made reads: the cursor never passes an event that was not shown, `more` is the
/// one extra row, the gap is everything below the floor, a cursor ahead of the log is refused, and so is one above the
/// floor that does not name the event stored at its `seq` ([EventCursor]).
class EventWindowTest {
    private static final EventPayload PAYLOAD = EventPayload.eventPayload(none(), none(), none(), none(), none(), none(), none(), none());

    @Test
    void eventWindow_readOneMoreThanMax_showsMax_setsMore_cursorIsTheLastShown() {
        var window = window(some(cursor(10)), 0, 20, events(11, 13), 2);

        assertThat(window.events().stream().map(EventRow::seq).toList()).containsExactly(11L, 12L);
        assertThat(window.more()).isTrue();
        assertThat(window.cursor()).isEqualTo(EventCursor.at(event(12)));
    }

    @Test
    void eventWindow_readExactlyMax_noMore() {
        var window = window(some(cursor(10)), 0, 12, events(11, 12), 2);

        assertThat(window.more()).isFalse();
        assertThat(window.cursor()).isEqualTo(EventCursor.at(event(12)));
    }

    /// Nothing read: the cursor stays where it was, even if the head is higher (a head the snapshot saw but the read
    /// did not reach is never skipped).
    @Test
    void eventWindow_nothingRead_cursorStays() {
        assertThat(window(some(cursor(10)), 0, 10, List.of(), 5).cursor()).isEqualTo(cursor(10));
    }

    @Test
    void eventWindow_belowTheFloor_gapToTheFloor_cursorAtLeastTheFloor() {
        var empty = EventWindow.eventWindow(some(cursor(3)), 8, 8, none(), List.of(), 5).unwrap();
        var after = EventWindow.eventWindow(some(cursor(3)), 8, 9, none(), events(9, 9), 5).unwrap();

        assertThat(empty.gap()).isEqualTo(some(new Pending.Gap(4, 8)));
        assertThat(empty.cursor()).isEqualTo(EventCursor.noEvent(8));
        assertThat(after.gap()).isEqualTo(some(new Pending.Gap(4, 8)));
        assertThat(after.cursor()).isEqualTo(EventCursor.at(event(9)));
    }

    /// The boundary: one below the floor is a gap of exactly the floor's event; at the floor there is none.
    @Test
    void eventWindow_justBelowTheFloor_gapOfOne_atTheFloor_noGap() {
        assertThat(EventWindow.eventWindow(some(cursor(7)), 8, 9, none(), events(9, 9), 5).unwrap().gap())
            .isEqualTo(some(new Pending.Gap(8, 8)));
        assertThat(EventWindow.eventWindow(some(EventCursor.noEvent(8)), 8, 9, none(), events(9, 9), 5).unwrap().gap())
            .isEqualTo(none());
    }

    @Test
    void eventWindow_withoutSince_startsAtTheHeadEvent_orTheFloor_noGap() {
        assertThat(EventWindow.eventWindow(none(), 4, 9, some(at(9)), List.of(), 5).unwrap().cursor()).isEqualTo(cursor(9));
        assertThat(EventWindow.eventWindow(none(), 9, 0, none(), List.of(), 5).unwrap().cursor()).as("retention emptied the table")
                                                                                              .isEqualTo(EventCursor.noEvent(9));
        assertThat(EventWindow.eventWindow(none(), 0, 0, none(), List.of(), 5).unwrap().cursor()).as("no event ever")
                                                                                              .isEqualTo(EventCursor.noEvent(0));
        assertThat(EventWindow.eventWindow(none(), 9, 0, none(), List.of(), 5).unwrap().gap()).isEqualTo(none());
    }

    @Test
    void eventWindow_sinceAheadOfTheLog_failsCursorAhead() {
        failsWith(EventWindow.eventWindow(some(cursor(10)), 4, 9, none(), List.of(), 5),
                  StoreError.CursorAhead.FACTORY.apply(cursor(10).wire(), 9L));
        assertThat(window(some(cursor(9)), 4, 9, List.of(), 5).cursor()).isEqualTo(cursor(9));
        assertThat(EventWindow.eventWindow(some(EventCursor.noEvent(9)), 9, 0, none(), List.of(), 5).unwrap().cursor())
            .as("at the floor of an emptied table").isEqualTo(EventCursor.noEvent(9));
    }

    /// The restore the verifier found: a reader at 5, the database restored to head 2, events 3..6 committed anew. The
    /// event now at 5 is another one, so the read is refused rather than answered with cursor 6 and no gap.
    @Test
    void eventWindow_cursorNamesAnotherEvent_failsCursorForeign() {
        var restored = EventRow.eventRow(5, Instant.parse("2026-10-16T12:00:00Z"), none(), EventRow.Kind.QUEUED, PAYLOAD);

        failsWith(EventWindow.eventWindow(some(cursor(5)), 0, 6, some(restored.at()), List.of(event(6)), 10),
                  StoreError.CursorForeign.FACTORY.apply(cursor(5).wire()));
    }

    /// Above the floor and no row at the cursor's `seq`, or a cursor that names no event although it is above the
    /// floor: not this log.
    @Test
    void eventWindow_cursorEventMissingAboveTheFloor_failsCursorForeign() {
        failsWith(EventWindow.eventWindow(some(cursor(5)), 0, 6, none(), List.of(event(6)), 10),
                  StoreError.CursorForeign.FACTORY.apply(cursor(5).wire()));
        failsWith(EventWindow.eventWindow(some(EventCursor.noEvent(5)), 0, 6, some(at(5)), List.of(event(6)), 10),
                  StoreError.CursorForeign.FACTORY.apply("5.-"));
    }

    @Test
    void eventCursor_wireRoundTrips_andRejectsABareSeq() {
        assertThat(EventCursor.eventCursor(cursor(5).wire()).unwrap()).isEqualTo(cursor(5));
        assertThat(EventCursor.eventCursor("5.-").unwrap()).isEqualTo(EventCursor.noEvent(5));
        List.of("5", "5.", ".5", "-1.3", "5.x", "5.3.2")
            .forEach(raw -> assertThat(EventCursor.eventCursor(raw).isFailure()).as(raw).isTrue());
    }

    private static void failsWith(Result<EventWindow> result, StoreError expected) {
        result.onSuccess(_ -> fail("expected " + expected))
              .onFailure(cause -> assertThat(cause).isEqualTo(expected));
    }

    /// `since` naming the event at its own `seq` (anchor `at(seq)`).
    private static EventWindow window(Option<EventCursor> since, long floor, long head, List<EventRow> read, int max) {
        return EventWindow.eventWindow(since, floor, head, since.map(cursor -> at(cursor.seq())), read, max).unwrap();
    }

    private static EventCursor cursor(long seq) {
        return EventCursor.at(event(seq));
    }

    private static Instant at(long seq) {
        return Instant.EPOCH.plusSeconds(seq).plusNanos(123_000);
    }

    private static EventRow event(long seq) {
        return EventRow.eventRow(seq, at(seq), none(), EventRow.Kind.QUEUED, PAYLOAD);
    }

    private static List<EventRow> events(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(EventWindowTest::event).toList();
    }
}
