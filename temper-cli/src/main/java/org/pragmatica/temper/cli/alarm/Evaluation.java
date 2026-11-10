package org.pragmatica.temper.cli.alarm;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.temper.cli.Console;


/// What one check found: the conditions that have a state, and the failures that left other conditions without one
/// (each reported on stderr; such a condition is neither clear nor firing, so its issue is left as it is, and
/// `check-blind` fires instead, see [#withBlindness]).
public record Evaluation(List<Condition> conditions, List<Cause> undetermined) {
    /// A `check-blind` detail longer than this is cut: it lands in an issue body and on one output line.
    private static final int MAX_DETAIL = 2000;
    private static final String REDACTED = "[redacted]";

    public static Evaluation evaluation(List<Condition> conditions, List<Cause> undetermined) {
        return new Evaluation(List.copyOf(conditions), List.copyOf(undetermined));
    }

    public static Evaluation decided(Condition... conditions) {
        return evaluation(List.of(conditions), List.of());
    }

    /// Each member of a composite (several failures collected by `Promise.all`/`Result.allOf`) on its own.
    public static Evaluation undetermined(Cause cause) {
        return evaluation(List.of(),
                          cause.stream().toList());
    }

    /// Both findings, this one's first.
    public Evaluation and(Evaluation other) {
        return evaluation(Stream.concat(conditions.stream(), other.conditions.stream()).toList(),
                          Stream.concat(undetermined.stream(), other.undetermined.stream()).toList());
    }

    /// This finding without the conditions of `codes` (skipped).
    public Evaluation without(List<AlarmCode> codes) {
        return evaluation(conditions.stream().filter(condition -> !codes.contains(condition.code())).toList(),
                          undetermined);
    }

    /// This finding with `check-blind` decided: firing, naming every undetermined failure, when there is one; clear
    /// otherwise. A blind check is thereby delivered like any condition (a rotated controller token opens an issue
    /// instead of silencing the alarm). The detail is each failure's one line, `secret` replaced wherever it occurs
    /// (defence in depth: no failure carries a header) and the whole cut at [#MAX_DETAIL] characters.
    public Evaluation withBlindness(String secret) {
        return and(decided(blindness(secret)));
    }

    public boolean firing() {
        return conditions.stream()
                         .anyMatch(Condition.Firing.class::isInstance);
    }

    /// Whether `code` was decided firing.
    public boolean fires(AlarmCode code) {
        return conditions.stream()
                         .anyMatch(condition -> condition instanceof Condition.Firing && condition.code() == code);
    }

    private Condition blindness(String secret) {
        return undetermined.isEmpty()
               ? Condition.clear(AlarmCode.CHECK_BLIND)
               : Condition.firing(AlarmCode.CHECK_BLIND, blindDetail(secret));
    }

    private String blindDetail(String secret) {
        var detail = "not evaluated: " + undetermined.stream()
                                                     .map(Console::oneLine)
                                                     .map(line -> redacted(line, secret))
                                                     .distinct()
                                                     .collect(Collectors.joining("; "));

        return detail.length() > MAX_DETAIL
               ? detail.substring(0, MAX_DETAIL) + "…"
               : detail;
    }

    /// `text` with every occurrence of `secret` replaced.
    public static String redacted(String text, String secret) {
        return secret.isEmpty()
               ? text
               : text.replace(secret, REDACTED);
    }
}
