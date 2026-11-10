package org.pragmatica.kiln.core.conflict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.conflict.ConflictEvent.State;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;


/// The predictions events.log has announced and not cleared, by [Prediction#key] (temper #100). Immutable: each step
/// answers the events to append and the announced set after them.
///
/// Restart (no DB, maintainer ruling): kiln seeds this from the tail of events.log ([#replayed]) before its first check, so a
/// restart appends a line only for what changed while it was down, never a burst of `new` lines for predictions that
/// were already announced.
public record Announced(Map<String, Prediction> byKey) {
    public static final Announced NONE = new Announced(Map.of());

    public Announced {
        byKey = Map.copyOf(byKey);
    }

    /// The set the lines leave behind, oldest first: a `new` line announces its key, a `cleared` line withdraws it.
    /// A line that does not parse is skipped.
    public static Announced replayed(List<String> lines) {
        var byKey = new LinkedHashMap<String, Prediction>();

        lines.forEach(line -> ConflictEvent.parsed(line).onPresent(event -> replay(byKey, event)));

        return new Announced(byKey);
    }

    private static void replay(Map<String, Prediction> byKey, ConflictEvent event) {
        if (event.state() == State.NEW) {
            byKey.put(event.prediction().key(),
                      event.prediction());
        } else {
            byKey.remove(event.prediction().key());
        }
    }

    /// One step's events, and the announced set after them (temper #100 rulings):
    /// - `new`: a current prediction whose key is not announced, or was announced at another tip;
    /// - `cleared`: an announced key that is not current and not `undetermined` (a check that failed, a head not yet in
    ///   the mirror, a pair not run because one side conflicts with the tip); it names the current `tip`.
    ///
    /// An undetermined key keeps its announcement, so a failed git call never reads as a cleared conflict.
    public Step step(List<Prediction> current, Predicate<String> undetermined, Sha tip, Instant at) {
        var next = new LinkedHashMap<>(byKey);
        var events = new ArrayList<ConflictEvent>();
        var currentKeys = current.stream().map(Prediction::key).collect(Collectors.toSet());

        byKey.values()
             .stream()
             .filter(old -> !currentKeys.contains(old.key()) && !undetermined.test(old.key()))
             .forEach(old -> cleared(next, events, old, tip, at));
        current.stream().filter(this::unannounced).forEach(prediction -> announced(next, events, prediction, at));

        return new Step(List.copyOf(events), new Announced(next));
    }

    private boolean unannounced(Prediction prediction) {
        return Option.option(byKey.get(prediction.key()))
                     .map(old -> !old.tip()
                                     .equals(prediction.tip()))
                     .or(true);
    }

    private static void cleared(Map<String, Prediction> next,
                                List<ConflictEvent> events,
                                Prediction old,
                                Sha tip,
                                Instant at) {
        next.remove(old.key());
        events.add(ConflictEvent.conflictEvent(at,
                                               State.CLEARED,
                                               new Prediction(old.kind(), old.prs(), old.paths(), tip, old.firsts())));
    }

    private static void announced(Map<String, Prediction> next,
                                  List<ConflictEvent> events,
                                  Prediction prediction,
                                  Instant at) {
        next.put(prediction.key(), prediction);
        events.add(ConflictEvent.conflictEvent(at, State.NEW, prediction));
    }

    /// The keys announced now.
    public Set<String> keys() {
        return byKey.keySet();
    }

    /// The events of one step, and the announced set after them.
    public record Step(List<ConflictEvent> events, Announced announced) {}
}
