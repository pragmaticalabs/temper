package org.pragmatica.temper.controller.flow;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// Runs a step over many items in consecutive batches (the retention GC, the `result.json` sweep): the items of one
/// batch run together, and the next batch starts once every item of the previous one resolved, so at most `size`
/// steps are in flight. Every item's result is answered in item order; one failed item does not stop the others.
public sealed interface Batches {
    static <T, R> Promise<List<Result<R>>> run(List<T> items, int size, Fn1<Promise<R>, T> step) {
        return from(items, Math.max(1, size), step, 0, List.of());
    }

    /// As [#run], answering the values in item order, or the failures of the items that failed.
    static <T, R> Promise<List<R>> values(List<T> items, int size, Fn1<Promise<R>, T> step) {
        return run(items, size, step).flatMap(Batches::collected);
    }

    private static <R> Promise<List<R>> collected(List<Result<R>> results) {
        return Result.allOf(results).async();
    }

    private static <T, R> Promise<List<Result<R>>> from(List<T> items,
                                                        int size,
                                                        Fn1<Promise<R>, T> step,
                                                        int start,
                                                        List<Result<R>> done) {
        return start >= items.size()
               ? Promise.success(done)
               : batch(items.subList(start,
                                     Math.min(items.size(), start + size)),
                       step).flatMap(results -> from(items, size, step, start + size, appended(done, results)));
    }

    private static <T, R> Promise<List<Result<R>>> batch(List<T> items, Fn1<Promise<R>, T> step) {
        return Promise.allOf(items.stream().map(step::apply).toList());
    }

    private static <R> List<Result<R>> appended(List<Result<R>> done, List<Result<R>> more) {
        return Stream.concat(done.stream(),
                             more.stream())
                     .toList();
    }

    record unused() implements Batches {}
}
