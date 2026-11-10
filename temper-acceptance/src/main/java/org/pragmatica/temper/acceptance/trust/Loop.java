package org.pragmatica.temper.acceptance.trust;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// An asynchronous loop that runs for hours (T1's queue polls, [QueueWait]) in constant stack and memory: the same
/// mechanism as temper-cli's `Loop`, which `temper wait` polls with. This module never has temper-cli on its classpath
/// (its pom pulls the CLI for build order only, so the CLI's slf4j-nop cannot compete with logback), hence the copy.
/// Writing such a loop as `step().flatMap(_ -> loop())` nests one pending promise per iteration and unwinds the whole
/// chain on one stack when the last one resolves: a 6 h `temper wait` overflowed the stack that way and then hung.
///
/// Only the single outer promise carries the result, and every step starts on a fresh task of core's executor, so a
/// step that completes at once (an instant pause in a test, a refused connect) never starts the next iteration on its
/// own stack. A step that throws fails the loop: core resolves the started promise with the escape.
public sealed interface Loop {
    /// What one iteration decided: run again with a new state, or finish with a value.
    sealed interface Turn<S, T> {
        record Again<S, T>(S state) implements Turn<S, T> {}

        record Done<S, T>(T value) implements Turn<S, T> {}

        static <S, T> Turn<S, T> again(S state) {
            return new Again<>(state);
        }

        static <S, T> Turn<S, T> done(T value) {
            return new Done<>(value);
        }
    }

    /// Runs `step` from `initial` until a step answers [Turn.Done] (the loop's value) or fails (the loop's failure).
    static <S, T> Promise<T> loop(S initial, Fn1<Promise<Turn<S, T>>, S> step) {
        var outcome = Promise.<T> promise();

        iterate(outcome, initial, step);

        return outcome;
    }

    private static <S, T> Unit iterate(Promise<T> outcome, S state, Fn1<Promise<Turn<S, T>>, S> step) {
        // May run late: the callbacks are what resolve `turn` and `outcome`, which carry the loop's ordering themselves.
        Promise.<Turn<S, T>> promise()
               .onResult(result -> settle(outcome, result, step))
               .async(turn -> started(turn, state, step));

        return unit();
    }

    private static <S, T> Unit started(Promise<Turn<S, T>> turn, S state, Fn1<Promise<Turn<S, T>>, S> step) {
        step.apply(state).onResult(turn::resolve);

        return unit();
    }

    private static <S, T> Unit settle(Promise<T> outcome, Result<Turn<S, T>> result, Fn1<Promise<Turn<S, T>>, S> step) {
        return result.fold(cause -> resolved(outcome.fail(cause)), turn -> next(outcome, turn, step));
    }

    private static <S, T> Unit next(Promise<T> outcome, Turn<S, T> turn, Fn1<Promise<Turn<S, T>>, S> step) {
        return switch (turn) {
            case Turn.Again<S, T> again -> iterate(outcome, again.state(), step);
            case Turn.Done<S, T> done -> resolved(outcome.succeed(done.value()));
        };
    }

    private static <T> Unit resolved(Promise<T> outcome) {
        return unit();
    }

    record unused() implements Loop {}
}
