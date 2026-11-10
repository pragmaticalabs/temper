package org.pragmatica.temper.domain.seed;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Seed isolation failures (A13). The worker reports them as `Failure{code: "seed-isolation"}`.
public sealed interface SeedError extends Cause {
    /// Files of the workspace repository share inodes with another path: an in-place install through a hard
    /// link would write the seed's original (R-M2).
    record Hardlinked(int count, List<String> firstPaths, String message) implements SeedError, Cause.Terminal {
        public static final Fn2<Hardlinked, Integer, List<String>> FACTORY = Causes.forTwoValues("%d file(s) in the workspace repository are hard-linked (nlink > 1), e.g. %s",
                                                                                                 Hardlinked::new);

        public Hardlinked {
            firstPaths = List.copyOf(firstPaths);
        }
    }
}
