package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.domain.DomainError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// A digest cursor (dashboard design §2): the `seq` of the last event a reader was shown and that event's identity,
/// `<seq>.<token>` on the wire. The token is the event's `at` in microseconds since the epoch: per row, and never
/// changed (nothing updates `events`). A cursor that names no stored event (the event floor, or 0 before any event)
/// has the token `-`.
///
/// What the token buys, exactly: a cursor from another event log (another database, or this one restored from an
/// older dump that then committed new events) is recognised once the event at its `seq` differs from the one it names,
/// or is missing above the floor. A cursor at or below the floor cannot be checked (its event is gone either way) and
/// is read as pruned. Two logs whose events at that `seq` were stamped in the same microsecond are not told apart.
public record EventCursor(long seq, Option<Long> token) {
    /// The wire form; `temper-protocol`'s `DigestView.CURSOR` is the same pattern (pinned by a test).
    public static final Pattern WIRE = Pattern.compile("^([0-9]{1,18})\\.([0-9]{1,18}|-)$");
    private static final String NO_EVENT = "-";

    public static Result<EventCursor> eventCursor(String raw) {
        return Verify.ensure(raw, Verify.Is::matches, WIRE, invalid("since")).flatMap(EventCursor::parsed);
    }

    /// The cursor naming `event`.
    public static EventCursor at(EventRow event) {
        return new EventCursor(event.seq(), some(micros(event.at())));
    }

    /// The cursor at `seq` (the floor, or 0) naming no stored event.
    public static EventCursor noEvent(long seq) {
        return new EventCursor(seq, none());
    }

    public static long micros(Instant at) {
        return at.getEpochSecond() * 1_000_000 + at.getNano() / 1_000;
    }

    /// Whether `at`, the `at` of the stored event at this cursor's `seq` (none: no such row), is the event it names.
    public boolean names(Option<Instant> at) {
        return token.filter(expected -> stamped(expected, at))
                    .isPresent();
    }

    private static boolean stamped(long expected, Option<Instant> at) {
        return at.map(EventCursor::micros)
                 .filter(micros -> micros == expected)
                 .isPresent();
    }

    public String wire() {
        return seq + "." + token.map(String::valueOf)
                                .or(NO_EVENT);
    }

    private static Result<EventCursor> parsed(String raw) {
        var dot = raw.indexOf('.');
        var token = raw.substring(dot + 1);

        return Number.parseLong(raw.substring(0, dot))
                     .mapError(DomainError.unparseable("since", raw))
                     .flatMap(seq -> withToken(seq, token, raw));
    }

    private static Result<EventCursor> withToken(long seq, String token, String raw) {
        return NO_EVENT.equals(token)
               ? Result.success(noEvent(seq))
               : Number.parseLong(token)
                       .mapError(DomainError.unparseable("since", raw))
                       .map(value -> new EventCursor(seq,
                                                     some(value)));
    }
}
