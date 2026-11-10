package org.pragmatica.temper.controller.flow;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// Fork-join over a list of independent promises: all of them run to completion, and on success the result is every
/// value in list order. The two differ only in the failure.
public sealed interface Collect {
    /// Fails with a `Causes.CompositeCause` (rc4 `Result.allOf`) holding every failure in list order, even when only
    /// one failed; its message is `Composite:` followed by each failure's message on its own indented line. For fan-outs where
    /// each failure matters to whoever reads it (a log naming every host or action); never for a caller whose
    /// consumers switch on the cause type.
    static <T> Promise<List<T>> all(List<Promise<T>> promises) {
        return Promise.allOf(promises).flatMap(Collect::everyFailure);
    }

    /// Fails with the failure of the first promise IN LIST ORDER that failed (not the first to fail in time),
    /// unwrapped (rc4 `Result.firstFailureOf`), so a consumer can switch on its type.
    static <T> Promise<List<T>> first(List<Promise<T>> promises) {
        return Promise.allOf(promises).flatMap(Collect::firstFailure);
    }

    private static <T> Promise<List<T>> everyFailure(List<Result<T>> results) {
        return Result.allOf(results).async();
    }

    private static <T> Promise<List<T>> firstFailure(List<Result<T>> results) {
        return Result.firstFailureOf(results).async();
    }

    record unused() implements Collect {}
}
