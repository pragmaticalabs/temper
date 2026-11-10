package org.pragmatica.kiln.service.flow;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;


/// Asynchronous steps one after another: each starts once the previous one succeeded, and the first failure ends the
/// sequence (what has run stays run).
public sealed interface Steps {
    static <T, R> Promise<List<R>> sequence(List<T> items, Fn1<Promise<R>, T> step) {
        return items.stream()
                    .reduce(Promise.success(List.<R> of()),
                            (done, item) -> done.flatMap(results -> appended(results, item, step)),
                            (left, _) -> left);
    }

    private static <T, R> Promise<List<R>> appended(List<R> results, T item, Fn1<Promise<R>, T> step) {
        return step.apply(item)
                   .map(result -> Stream.concat(results.stream(),
                                                Stream.of(result))
                                        .toList());
    }

    record unused() implements Steps {}
}
