package org.pragmatica.kiln.core.conflict;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.conflict.Prediction.Kind;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.kiln.core.model.ModelError.invalid;
import static org.pragmatica.lang.Option.none;


/// One events.log line (temper #100): a prediction that became `NEW` or was `CLEARED`. Tab-separated, in the merge
/// runner's events.log spirit: `time  kind  state  prs  paths  tip  orders`, for example
/// `2026-11-08T10:15:00Z	pair	new	2711+2770	*aether/src/AetherKey.java,docs/x.md	2e44f757...	2770>2711`.
/// `orders` ([Prediction#orders], PR #108 review F4) names which landing order conflicts; empty for a tip line. A
/// six-field line (no orders) is still read.
///
/// `paths` are comma-separated, each hot one ([Prediction#hot]) with a leading `*`. A path's control characters, tabs
/// and commas are written as `?`, so a hostile file name can never split a line or a field; the paths of a parsed line
/// are therefore display text, never input to git.
public record ConflictEvent(Instant at, State state, Prediction prediction) {
    /// A new prediction, or one no longer predicted.
    public enum State {
        NEW,
        CLEARED;
        String word() {
            return this == NEW
                   ? "new"
                   : "cleared";
        }
    }

    private static final String TAB = "\t";
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc},\\u2028\\u2029]");
    private static final int FIELDS = 7;
    private static final int FIELDS_WITHOUT_ORDERS = 6;

    public static ConflictEvent conflictEvent(Instant at, State state, Prediction prediction) {
        return new ConflictEvent(at, state, prediction);
    }

    /// The line, without its newline.
    public String line() {
        return String.join(TAB,
                           at.toString(),
                           prediction.kind().word(),
                           state.word(),
                           Prediction.prs(prediction.prs()),
                           paths(prediction.paths()),
                           prediction.tip().value(),
                           prediction.orders());
    }

    private static String paths(List<String> paths) {
        return paths.stream()
                    .map(ConflictEvent::marked)
                    .collect(Collectors.joining(","));
    }

    private static String marked(String path) {
        var safe = UNSAFE.matcher(path).replaceAll("?");

        return Prediction.hot(path)
               ? "*" + safe
               : safe;
    }

    /// The event a line records; none for a line that is not one (a torn last line, a foreign line).
    public static Option<ConflictEvent> parsed(String line) {
        var fields = line.split(TAB, -1);

        return fields.length == FIELDS || fields.length == FIELDS_WITHOUT_ORDERS
               ? fromFields(fields).option()
               : none();
    }

    private static Result<ConflictEvent> fromFields(String[] fields) {
        return Result.all(DateTime.parseInstant(fields[0]),
                          state(fields[2]),
                          kind(fields[1]),
                          prs(fields[3]),
                          Sha.sha(fields[5]),
                          firsts(fields))
                     .flatMap((at, state, kind, prs, tip, firsts) -> event(at,
                                                                           state,
                                                                           kind,
                                                                           prs,
                                                                           unmarked(fields[4]),
                                                                           tip,
                                                                           firsts));
    }

    private static Result<ConflictEvent> event(Instant at,
                                               State state,
                                               Kind kind,
                                               List<PrNumber> prs,
                                               List<String> paths,
                                               Sha tip,
                                               List<PrNumber> firsts) {
        var expected = kind == Kind.TIP
                       ? 1
                       : 2;

        return prs.size() == expected && prs.containsAll(firsts)
               ? Result.success(conflictEvent(at, state, new Prediction(kind, prs, paths, tip, firsts)))
               : invalid("events.log prs").apply(prs)
                        .result();
    }

    private static Result<State> state(String raw) {
        return Arrays.stream(State.values())
                     .filter(state -> state.word()
                                           .equals(raw))
                     .findFirst()
                     .map(Result::success)
                     .orElseGet(() -> invalid("events.log state").apply(raw)
                                             .result());
    }

    private static Result<Kind> kind(String raw) {
        return Arrays.stream(Kind.values())
                     .filter(kind -> kind.word()
                                         .equals(raw))
                     .findFirst()
                     .map(Result::success)
                     .orElseGet(() -> invalid("events.log kind").apply(raw)
                                             .result());
    }

    /// The PRs that land first in each order the line names (`2711>2770,2770>2711`); none on a six-field line.
    private static Result<List<PrNumber>> firsts(String[] fields) {
        return fields.length == FIELDS && !fields[6].isEmpty()
               ? Result.allOf(Arrays.stream(fields[6].split(",", -1))
                                    .map(order -> Number.parseLong(order.substring(0,
                                                                                   Math.max(0,
                                                                                            order.indexOf('>')))).flatMap(PrNumber::prNumber))
                                    .toList())
               : Result.success(List.of());
    }

    private static Result<List<PrNumber>> prs(String raw) {
        return Result.allOf(Arrays.stream(raw.split("\\+", -1))
                                  .map(one -> Number.parseLong(one).flatMap(PrNumber::prNumber))
                                  .toList());
    }

    private static List<String> unmarked(String raw) {
        return raw.isEmpty()
               ? List.of()
               : Arrays.stream(raw.split(",", -1))
                       .map(path -> path.startsWith("*")
                                    ? path.substring(1)
                                    : path)
                       .toList();
    }
}
