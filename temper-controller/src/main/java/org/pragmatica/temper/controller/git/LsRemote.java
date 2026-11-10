package org.pragmatica.temper.controller.git;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Pure reading of `git ls-remote <origin> refs/heads/<R> refs/tags/<R> <R>` output (spec §2.2). ls-remote
/// patterns match ref-name TAILS, so the output is filtered to exact names: `refs/heads/R`, `refs/tags/R` and, when
/// `R` is itself a full name (`refs/...`), `R`. An annotated tag's peeled line (`^{}`) gives its commit. Exactly one
/// distinct commit must remain [G]; none or several is `Refusal(source)`.
public sealed interface LsRemote {
    String PEELED = "^{}";

    /// The peeled names must be asked for explicitly: `ls-remote` prints `refs/tags/R^{}` (an annotated tag's commit)
    /// only when that exact name is a pattern.
    static List<String> patterns(Ref ref) {
        var tag = "refs/tags/" + ref.value();

        return ref.value()
                  .startsWith("refs/")
               ? List.of("refs/heads/" + ref.value(), tag, tag + PEELED, ref.value(), ref.value() + PEELED)
               : List.of("refs/heads/" + ref.value(), tag, tag + PEELED, ref.value());
    }

    static Result<Sha> resolve(Ref ref, String output) {
        return single(ref, commits(exact(ref, entries(output))));
    }

    private static List<Entry> entries(String output) {
        return Arrays.stream(output.split("\n"))
                     .map(String::trim)
                     .filter(Predicate.not(String::isEmpty))
                     .flatMap(line -> parseLine(line).stream())
                     .toList();
    }

    private static List<Entry> exact(Ref ref, List<Entry> entries) {
        var names = exactNames(ref);

        return entries.stream()
                      .filter(exactIn(names))
                      .toList();
    }

    private static Set<String> commits(List<Entry> lines) {
        return lines.stream()
                    .filter(commitLineIn(lines))
                    .map(Entry::sha)
                    .collect(Collectors.toSet());
    }

    private static Result<Sha> single(Ref ref, Set<String> commits) {
        return commits.size() == 1
               ? Sha.sha(commits.iterator().next())
               : Refusal.refusal(RefusalCode.SOURCE,
                                 describe(ref,
                                          commits.size()))
                        .result();
    }

    private static String describe(Ref ref, int count) {
        return count == 0
               ? "ref " + ref + " matches no pushed branch or tag"
               : "ref " + ref + " is ambiguous: it names " + count + " different commits";
    }

    private static Set<String> exactNames(Ref ref) {
        var heads = "refs/heads/" + ref.value();
        var tags = "refs/tags/" + ref.value();

        return ref.value()
                  .startsWith("refs/")
               ? Set.of(heads, tags, tags + PEELED, ref.value(), ref.value() + PEELED)
               : Set.of(heads, tags, tags + PEELED);
    }

    private static Predicate<Entry> exactIn(Set<String> names) {
        return candidate -> names.contains(candidate.name());
    }

    private static Predicate<Entry> commitLineIn(List<Entry> lines) {
        return candidate -> commitLine(candidate, lines);
    }

    /// A tag object line is replaced by its peeled (`^{}`) commit line when the latter is present.
    private static boolean commitLine(Entry entry, List<Entry> lines) {
        return entry.name()
                    .endsWith(PEELED) || lines.stream()
                                              .noneMatch(other -> other.name()
                                                                       .equals(entry.name() + PEELED));
    }

    private static Option<Entry> parseLine(String line) {
        var parts = line.split("\\s+");

        return parts.length == 2
               ? some(Entry.entry(parts[0], parts[1]))
               : none();
    }

    record Entry(String sha, String name) {
        static Entry entry(String sha, String name) {
            return new Entry(sha, name);
        }
    }

    record unused() implements LsRemote {}
}
