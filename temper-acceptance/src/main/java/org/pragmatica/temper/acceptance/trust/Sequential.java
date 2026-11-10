package org.pragmatica.temper.acceptance.trust;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.lang.Promise;


/// Runs one asynchronous step per item, strictly one after another (a T1 Gate holds the port group, so two must
/// never overlap), collecting the answers in item order.
public sealed interface Sequential {
    static <T, R> Promise<List<R>> each(List<T> items, Function<T, Promise<R>> step) {
        return items.stream()
                    .reduce(Promise.success(List.<R> of()),
                            (done, item) -> next(done, item, step),
                            (first, _) -> first);
    }

    private static <T, R> Promise<List<R>> next(Promise<List<R>> done, T item, Function<T, Promise<R>> step) {
        return done.flatMap(answers -> appended(answers, step.apply(item)));
    }

    private static <R> Promise<List<R>> appended(List<R> answers, Promise<R> answer) {
        return answer.map(found -> Stream.concat(answers.stream(),
                                                 Stream.of(found))
                                         .toList());
    }

    record unused() implements Sequential {}
}
