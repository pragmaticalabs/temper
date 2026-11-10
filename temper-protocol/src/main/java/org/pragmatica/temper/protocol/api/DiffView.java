package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.diff.KeyOutcome;
import org.pragmatica.temper.domain.state.JobState;

import com.fasterxml.jackson.annotation.JsonProperty;


/// Two compared jobs (m2-design §3.3): side A (`a`), side B (`b`), and the sets of `TestDiff` over their rows.
///
/// - `a` and `b` name each side's job, verdict, recipe kind and compared row count; with `--base`, `base` is side A's
///   job, the Gate that `gate <sha>` answers about.
/// - `added`, `newlyFailing` and `newlyPassing` carry B's outcome, `removed` A's; `skipChanged` both (Q-M2-8: listed
///   here only, in no section). Every list is in class, then name order.
/// - `counts.addedFailing` is how many added tests fail; `counts.duplicates` how many keys had more than one row.
public record DiffView(Side a,
                       Side b,
                       Option<Long> base,
                       List<Entry> added,
                       List<Entry> removed,
                       List<Entry> newlyFailing,
                       List<Entry> newlyPassing,
                       List<Skip> skipChanged,
                       Counts counts) implements DiffAnswer {
    public DiffView {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        newlyFailing = List.copyOf(newlyFailing);
        newlyPassing = List.copyOf(newlyPassing);
        skipChanged = List.copyOf(skipChanged);
    }

    public static DiffView diffView(Side a,
                                    Side b,
                                    Option<Long> base,
                                    List<Entry> added,
                                    List<Entry> removed,
                                    List<Entry> newlyFailing,
                                    List<Entry> newlyPassing,
                                    List<Skip> skipChanged,
                                    Counts counts) {
        return new DiffView(a, b, base, added, removed, newlyFailing, newlyPassing, skipChanged, counts);
    }

    /// One side: its job, its verdict (PASSED, FAILED or EMPTY: a side not judged is refused, R-M2-13), its recipe kind
    /// and how many testcase rows were compared (0 for B: never a clean diff).
    public record Side(long job, JobState verdict, String recipe, int testcases) {
        public static Side side(long job, JobState verdict, String recipe, int testcases) {
            return new Side(job, verdict, recipe, testcases);
        }
    }

    /// One test in a section, with the outcome of the side the section reports.
    public record Entry(@JsonProperty("class") String className, String name, KeyOutcome outcome) {
        public static Entry entry(String className, String name, KeyOutcome outcome) {
            return new Entry(className, name, outcome);
        }
    }

    /// A test that moved between SKIPPED and PASSED or FAILED: A's outcome, then B's.
    public record Skip(@JsonProperty("class") String className, String name, KeyOutcome a, KeyOutcome b) {
        public static Skip skip(String className, String name, KeyOutcome a, KeyOutcome b) {
            return new Skip(className, name, a, b);
        }
    }

    /// The sizes of the sets, as the counts line prints them.
    public record Counts(int added,
                         int addedFailing,
                         int removed,
                         int newlyFailing,
                         int newlyPassing,
                         int skipChanged,
                         int duplicates) {
        public static Counts counts(int added,
                                    int addedFailing,
                                    int removed,
                                    int newlyFailing,
                                    int newlyPassing,
                                    int skipChanged,
                                    int duplicates) {
            return new Counts(added, addedFailing, removed, newlyFailing, newlyPassing, skipChanged, duplicates);
        }
    }
}
