package org.pragmatica.temper.merge.engine;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import org.pragmatica.temper.merge.engine.GitHubReader.CheckRun;


/// The state of the required checks at one sha, as wait-required.sh judged it: the newest run per name wins (a rerun
/// replaces the run it reran), a required name with no run is pending, and a red required check is final. Only
/// `success` is green (maintainer ruling, #106): a `neutral` or `skipped` required check is refused with its conclusion.
public sealed interface RequiredChecks {
    Set<String> GREEN = Set.of("success");

    record Green(int count) implements RequiredChecks {}

    /// Completed and not green: `name:conclusion` each. A stop, never a retry.
    record Red(List<String> failing) implements RequiredChecks {}

    record Pending(List<String> waiting) implements RequiredChecks {}

    /// No required check was read for the base: refused, since "every required check is green" would hold vacuously.
    record NoneRequired() implements RequiredChecks {}

    static RequiredChecks judged(List<String> required, List<CheckRun> runs) {
        if (required.isEmpty()) {
            return new NoneRequired();
        }

        var latest = runs.stream().collect(Collectors.toMap(CheckRun::name, run -> run, newest()));
        var red = required.stream()
                          .filter(name -> latest.containsKey(name) && isRed(latest.get(name)))
                          .map(name -> name + ":" + latest.get(name)
                                                          .conclusion())
                          .toList();

        if (!red.isEmpty()) {
            return new Red(red);
        }

        var waiting = required.stream().filter(name -> !isGreen(latest, name)).toList();

        return waiting.isEmpty()
               ? new Green(required.size())
               : new Pending(waiting);
    }

    private static BinaryOperator<CheckRun> newest() {
        return (one, other) -> Comparator.comparing(CheckRun::startedAt,
                                                    Comparator.nullsFirst(Comparator.<Instant> naturalOrder()))
                                         .compare(one, other) >= 0
                               ? one
                               : other;
    }

    private static boolean completed(CheckRun run) {
        return "completed".equals(run.status());
    }

    private static boolean isRed(CheckRun run) {
        return completed(run) && !GREEN.contains(run.conclusion());
    }

    private static boolean isGreen(Map<String, CheckRun> latest, String name) {
        return latest.containsKey(name)
               && completed(latest.get(name))
               && GREEN.contains(latest.get(name).conclusion());
    }
}
