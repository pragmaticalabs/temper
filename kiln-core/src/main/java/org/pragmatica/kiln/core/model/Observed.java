package org.pragmatica.kiln.core.model;

import java.time.Instant;


/// A fact with the time kiln observed it. Every fact kiln shows travels in one (spec D-4), so no value is ever shown
/// without its age.
public record Observed<T>(T value, Instant observedAt) {
    public static <T> Observed<T> observed(T value, Instant observedAt) {
        return new Observed<>(value, observedAt);
    }
}
