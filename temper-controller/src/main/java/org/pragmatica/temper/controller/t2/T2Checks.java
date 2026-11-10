package org.pragmatica.temper.controller.t2;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.VerdictRow;


/// The checks the controller judges in `GET fleet/pr-fresh`: G9 from kiln's file list and
/// the repo's `ci_control_paths`, the server part of G6 from the latest verdict row, and the order every NO reason is
/// printed in (G1, G2, G3, G4, G5, G8, G9, G10, G6, G7; each check's lines in production order). Pure.
public sealed interface T2Checks {
    List<String> ORDER = List.of("G1", "G2", "G3", "G4", "G5", "G8", "G9", "G10", "G6", "G7");
    int MAX_CHANGED = 20;
    String ACCEPT = "ACCEPT";

    /// One NO reason: the check and its exact line.
    record Reason(String check, String line) {
        public static Reason reason(String check, String line) {
            return new Reason(check, line);
        }
    }

    /// `reasons` in the print order; a check the gate does not name sorts last, and lines of one check keep their order.
    static List<Reason> ordered(List<Reason> reasons) {
        return reasons.stream()
                      .sorted(Comparator.comparingInt(T2Checks::rank))
                      .toList();
    }

    private static int rank(Reason reason) {
        return Option.some(ORDER.indexOf(reason.check()))
                     .filter(index -> index >= 0)
                     .or(ORDER.size());
    }

    /// G9: a short file read (`files.read` ≠ `pull.changedFiles`) is incomplete; a repo without globs is unconfigured
    /// (fail closed, Q5); otherwise one line per path that a glob matches, sorted, at most 20, then the count of
    /// the rest. `paths` holds each file's `filename` and, for a rename, its `previous_filename`.
    static List<Reason> g9(long filesRead, long changedFiles, List<String> paths, List<String> globs) {
        return Stream.of(incomplete(filesRead, changedFiles),
                         globs.isEmpty()
                         ? List.of(g9Line("ci-control-unconfigured"))
                         : changed(paths, globs))
                     .flatMap(List::stream)
                     .toList();
    }

    private static List<Reason> incomplete(long filesRead, long changedFiles) {
        return filesRead == changedFiles
               ? List.of()
               : List.of(g9Line("files-read-incomplete " + filesRead + "/" + changedFiles));
    }

    private static List<Reason> changed(List<String> paths, List<String> globs) {
        var matched = paths.stream()
                           .filter(path -> globs.stream()
                                                .anyMatch(glob -> matches(glob, path)))
                           .distinct()
                           .sorted()
                           .toList();

        return Stream.concat(matched.stream()
                                    .limit(MAX_CHANGED)
                                    .map(path -> g9Line("ci-control-changed " + path
                                                       + " (needs an explicit maintainer ruling)")),
                             more(matched.size()))
                     .toList();
    }

    private static Stream<Reason> more(int matched) {
        return matched > MAX_CHANGED
               ? Stream.of(g9Line("ci-control-changed … " + (matched - MAX_CHANGED) + " more"))
               : Stream.empty();
    }

    private static Reason g9Line(String line) {
        return Reason.reason("G9", line);
    }

    /// G9 glob: a whole segment `**` matches zero or more segments, `*` any run of characters within one segment,
    /// every other character is literal, and the glob must match the whole path (`pom.xml` is the root file only).
    static boolean matches(String glob, String path) {
        return matches(glob.split("/", -1), 0, path.split("/", -1), 0);
    }

    private static boolean matches(String[] glob, int at, String[] path, int from) {
        return at == glob.length
               ? from == path.length
               : glob[at].equals("**")
                 ? matches(glob, at + 1, path, from) || (from < path.length && matches(glob, at, path, from + 1))
                 : from < path.length
                   && segment(glob[at]).matcher(path[from])
                             .matches()
                   && matches(glob, at + 1, path, from + 1);
    }

    private static Pattern segment(String glob) {
        return Pattern.compile(Stream.of(glob.split("\\*", -1))
                                     .map(Pattern::quote)
                                     .collect(Collectors.joining("[^/]*")));
    }

    /// G6, server part: the latest row for the head; none → missing; a base that is not the PR's → base-mismatch;
    /// FIX or REJECT → that verdict.
    static List<Reason> g6(Option<VerdictRow> verdict, String pullBase) {
        return verdict.map(row -> g6Row(row, pullBase))
                      .or(() -> List.of(g6Line("verdict-missing")));
    }

    private static List<Reason> g6Row(VerdictRow row, String pullBase) {
        return ! row.base()
                    .equals(pullBase)
               ? List.of(g6Line("verdict-base-mismatch " + row.base()))
               : row.verdict()
                    .equals(ACCEPT)
                 ? List.of()
                 : List.of(g6Line("verdict-" + row.verdict()));
    }

    private static Reason g6Line(String line) {
        return Reason.reason("G6", line);
    }

    record unused() implements T2Checks {}
}
