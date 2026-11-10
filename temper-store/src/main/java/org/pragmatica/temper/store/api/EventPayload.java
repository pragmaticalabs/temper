package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.verdict.Counts;

import static org.pragmatica.lang.Option.option;


/// The `events.payload` jsonb: the facts an event line is rendered from (`EventLines.render`) and the job's sha/ref for
/// subscriber selectors. Which fields are set depends on the kind: `unit` for `started`, `start-unknown` and
/// `unit-finished`, `verdict`/`reason` for `unit-finished`, `finished` and `cancelled`, `counts` for `finished` and
/// `cancelled`, `supersededBy` for `superseded`. `counts` serializes member for member as the result record's `counts`,
/// so the jsonb shape is the wire one. `known` is the `KNOWN(#n)` annotations of a `finished` / `cancelled` job's
/// record (m2-design §4.3), empty otherwise.
public record EventPayload(Option<String> sha,
                           Option<String> ref,
                           Option<String> recipe,
                           Option<String> unit,
                           Option<String> verdict,
                           Option<String> reason,
                           Option<Counts> counts,
                           Option<Long> supersededBy,
                           List<String> known) {
    /// A payload stored before `known` existed has no such field, which the JSON reader hands over as null: it reads as
    /// no annotations. A null element inside the list is dropped [G]: the annotations never change a verdict, so a
    /// corrupt one must not make the event unreadable.
    public EventPayload {
        known = option(known).map(EventPayload::present).or(List.of());
    }

    private static List<String> present(List<String> annotations) {
        return annotations.stream()
                          .flatMap(annotation -> option(annotation).stream())
                          .toList();
    }

    public static EventPayload eventPayload(Option<String> sha,
                                            Option<String> ref,
                                            Option<String> recipe,
                                            Option<String> unit,
                                            Option<String> verdict,
                                            Option<String> reason,
                                            Option<Counts> counts,
                                            Option<Long> supersededBy) {
        return eventPayload(sha, ref, recipe, unit, verdict, reason, counts, supersededBy, List.of());
    }

    public static EventPayload eventPayload(Option<String> sha,
                                            Option<String> ref,
                                            Option<String> recipe,
                                            Option<String> unit,
                                            Option<String> verdict,
                                            Option<String> reason,
                                            Option<Counts> counts,
                                            Option<Long> supersededBy,
                                            List<String> known) {
        return new EventPayload(sha, ref, recipe, unit, verdict, reason, counts, supersededBy, known);
    }
}
