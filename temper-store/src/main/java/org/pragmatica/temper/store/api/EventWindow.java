package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// The events one digest shows (dashboard design §2), with the cursor it hands back. The guarantee: every event with
/// `seq <= cursor.seq` was either in `events` or inside `gap`, for a cursor of this event log. It rests on two facts
/// of the store, not on this record:
///
/// - commit order is `seq` order. Every event insert takes `pg_advisory_xact_lock(70700001)` before its `seq` is drawn
///   (`EventWriter`), and Postgres releases a transaction's locks only after its commit is visible, so a snapshot that
///   sees `seq = n` sees every committed `seq < n`. A `seq` that never shows up is one a rolled-back insert drew: it
///   was never an event, so skipping it loses nothing;
/// - retention raises the floor in the transaction that deletes, and the floor, the head and the events come from one
///   snapshot, so a deleted event is always inside the gap.
///
/// "Of this event log" is checked as far as [EventCursor] can: a cursor above the floor must name the event stored at
/// its `seq`, or the read fails with [StoreError.CursorForeign]; one above every committed `seq` fails with
/// [StoreError.CursorAhead].
///
/// `more` says the read stopped at `max` and more events follow: the cursor is then the last event shown, never the
/// head, so the next digest starts right after it.
public record EventWindow(EventCursor cursor, boolean more, Option<Pending.Gap> gap, List<EventRow> events) {
    public EventWindow {
        events = List.copyOf(events);
    }

    /// The window over one snapshot: `floor` and `head` (the highest stored `seq`, 0 when none) as read; `anchor` the
    /// `at` of the stored event at `since.seq`, or at the head without `since` (none: no such row); `read` the events
    /// after `max(since.seq, floor)` (none at all without `since`), ascending, at most `max + 1`, so that one more than
    /// `max` says there are more.
    public static Result<EventWindow> eventWindow(Option<EventCursor> since,
                                                  long floor,
                                                  long head,
                                                  Option<Instant> anchor,
                                                  List<EventRow> read,
                                                  int max) {
        var top = Math.max(head, floor);

        return since.map(cursor -> checked(cursor, floor, top, anchor))
                    .or(() -> success(atHead(floor, head, anchor)))
                    .map(start -> window(start, floor, read, max));
    }

    /// Ahead of every committed `seq`: refused. Above the floor: it must name the event stored there. At or below the
    /// floor: its event is gone either way, and the gap reports what was deleted.
    private static Result<EventCursor> checked(EventCursor since, long floor, long top, Option<Instant> anchor) {
        return since.seq() > top
               ? StoreError.CursorAhead.FACTORY.apply(since.wire(),
                                                      top)
                                               .result()
               : since.seq() > floor && !since.names(anchor)
                 ? StoreError.CursorForeign.FACTORY.apply(since.wire()).result()
                 : success(since);
    }

    /// Without `since`: the head event when there is one above the floor, else the floor (retention emptied the table,
    /// or there never was an event).
    private static EventCursor atHead(long floor, long head, Option<Instant> anchor) {
        return anchor.filter(_ -> head > floor)
                     .map(at -> new EventCursor(head,
                                                some(EventCursor.micros(at))))
                     .or(() -> EventCursor.noEvent(Math.max(head, floor)));
    }

    private static EventWindow window(EventCursor since, long floor, List<EventRow> read, int max) {
        var shown = read.stream().limit(max).toList();

        return new EventWindow(shown.isEmpty()
                               ? stayed(since, floor)
                               : EventCursor.at(shown.getLast()),
                               read.size() > max,
                               gap(since.seq(), floor),
                               shown);
    }

    /// Nothing new: the cursor stays, unless retention moved the floor past it.
    private static EventCursor stayed(EventCursor since, long floor) {
        return since.seq() >= floor
               ? since
               : EventCursor.noEvent(floor);
    }

    /// Retention deleted `since + 1 .. floor` before this reader got to them.
    private static Option<Pending.Gap> gap(long since, long floor) {
        return floor > since
               ? some(new Pending.Gap(since + 1, floor))
               : none();
    }
}
