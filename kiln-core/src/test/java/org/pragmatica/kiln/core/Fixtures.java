package org.pragmatica.kiln.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Assertions;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Unwrapping helpers for tests: a success's value, or a failure's cause, failing the test otherwise; and the text of a
/// test resource.
public sealed interface Fixtures {
    static <T> T ok(Result<T> result) {
        return result.onFailure(cause -> Assertions.fail("expected a success, got " + cause.message()))
                     .unwrap();
    }

    static <T> Cause failure(Result<T> result) {
        return result.fold(cause -> cause, value -> Assertions.fail("expected a failure, got " + value));
    }

    /// A failure's causes, composites flattened: `Result.all` and `Result.allOf` collect every failure into one.
    static <T> List<Cause> causes(Result<T> result) {
        return flattened(failure(result));
    }

    private static List<Cause> flattened(Cause cause) {
        return cause instanceof Causes.CompositeCause composite
               ? composite.stream()
                          .flatMap(inner -> flattened(inner).stream())
                          .toList()
               : List.of(cause);
    }

    /// A test resource as UTF-8 text, e.g. `github/branch-main.json`.
    static String resource(String name) {
        try (InputStream in = Objects.requireNonNull(Fixtures.class.getResourceAsStream("/" + name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record unused() implements Fixtures {}
}
