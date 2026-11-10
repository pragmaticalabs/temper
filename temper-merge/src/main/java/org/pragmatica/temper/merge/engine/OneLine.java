package org.pragmatica.temper.merge.engine;

import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;


/// A cause's message as one line. Several failures joined by `Promise.all` or `Result.all` arrive as a composite
/// cause, whose message is a `Composite:` header and one indented line each; a job's observation, its reason and an
/// events line must each stay one line.
public sealed interface OneLine {
    static String of(Cause cause) {
        return cause.message()
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !"Composite:".equals(line))
                    .collect(Collectors.joining("; "));
    }

    record unused() implements OneLine {}
}
