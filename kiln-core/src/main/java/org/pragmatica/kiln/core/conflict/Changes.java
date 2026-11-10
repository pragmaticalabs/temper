package org.pragmatica.kiln.core.conflict;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;


/// What one PR changes against its merge base with the tip (`git diff --name-status -M tip...head`), as the pair filter
/// needs it (PR #108 review F3):
/// - `paths`: every path touched, both sides of a rename;
/// - `added`: the paths that exist only after the change (an add, a rename's or a copy's new path);
/// - `movedFrom`: the directories a rename moved a file out of, below the directory the old and the new path share
///   (`d/one` to `e/one` gives `d`; `src/a/b/X.java` to `src/c/b/X.java` gives `src/a/b` and `src/a`). Git infers a
///   directory rename from such moves and carries the other side's new files along.
public record Changes(Set<String> paths, Set<String> added, Set<String> movedFrom) {
    public Changes {
        paths = Set.copyOf(paths);
        added = Set.copyOf(added);
        movedFrom = Set.copyOf(movedFrom);
    }

    /// Plain edits only: every path is touched, none added or moved.
    public static Changes edits(Set<String> paths) {
        return new Changes(paths, Set.of(), Set.of());
    }

    /// The changes `--name-status` lists, one [Entry] per changed path.
    public static Changes changes(List<Entry> entries) {
        return new Changes(entries.stream().flatMap(Entry::touched).collect(Collectors.toSet()),
                           entries.stream().flatMap(Entry::created).collect(Collectors.toSet()),
                           entries.stream().flatMap(Entry::vacated).collect(Collectors.toSet()));
    }

    /// The directories the `added` paths live in, every level: a path the other side adds as a FILE where this side puts
    /// a directory, or a directory the other side moves, conflicts with these.
    public Set<String> addedUnder() {
        return added.stream()
                    .flatMap(Changes::parents)
                    .collect(Collectors.toSet());
    }

    /// Every proper parent directory of `path`: `a/b/c` gives `a/b` and `a`.
    static Stream<String> parents(String path) {
        return IntStream.range(0,
                               path.length())
                        .filter(at -> path.charAt(at) == '/')
                        .mapToObj(at -> path.substring(0, at))
                        .filter(parent -> !parent.isEmpty());
    }

    /// One changed path: `status` is `--name-status`' letter (`A`, `M`, `D`, `T`, `R`, `C`), `to` the new path of a
    /// rename or a copy and `from` its old one; for any other status `from` and `to` are the same path.
    public record Entry(char status, String from, String to) {
        Stream<String> touched() {
            return Stream.of(from, to).distinct();
        }

        Stream<String> created() {
            return switch (status) {
                case 'A', 'R', 'C' -> Stream.of(to);
                default -> Stream.empty();
            };
        }

        /// The directories a rename moved `from` out of, below the deepest directory `from` and `to` share.
        Stream<String> vacated() {
            if (status != 'R') {
                return Stream.empty();
            }

            var shared = parents(to).collect(Collectors.toSet());

            return parents(from).filter(parent -> !shared.contains(parent));
        }
    }
}
