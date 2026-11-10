package org.pragmatica.temper.domain.refusal;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Text;
import org.pragmatica.temper.domain.mvn.MvnStep;


/// "Could this step run the class?" (Q-3 ruling), judged from a `-Dtest` / `-Dit.test` value with plain
/// glob matching on the simple class name.
///
/// - No selection (absent or empty): the plugin runs everything, so the class could run.
/// - A `!pattern` that matches the class excludes it, whatever else the selection says.
/// - Otherwise, with at least one positive pattern, the class could run iff a positive pattern matches.
/// - With only exclusions, none matching, the plugin's default includes apply: the class could run.
///
/// Patterns are reduced to their class part: `#method` suffixes, `.java`/`.class` suffixes and any
/// package or `**/` path prefix are dropped. A `%regex[...]` pattern cannot be judged here, so it counts as
/// a possible match and never as an exclusion (conservative in both directions).
public sealed interface SelectionMatcher {
    /// Whether `step` could run `simpleClassName` through `runner`'s plugin, or through either plugin when
    /// the runner is unknown.
    static boolean couldRun(MvnStep step, String simpleClassName, Option<TestRunner> runner) {
        return runner.map(known -> couldRunUnder(step, simpleClassName, known))
                     .or(() -> couldRunUnder(step, simpleClassName, TestRunner.SUREFIRE) || couldRunUnder(step,
                                                                                                          simpleClassName,
                                                                                                          TestRunner.FAILSAFE));
    }

    /// Whether `runner` runs in `step` and its selection could include `simpleClassName`.
    static boolean couldRunUnder(MvnStep step, String simpleClassName, TestRunner runner) {
        return runner.runsIn(step) && selects(step.property(runner.selectionProperty()),
                                              simpleClassName);
    }

    /// Whether a selection value could include `simpleClassName`.
    static boolean selects(Option<String> selection, String simpleClassName) {
        var patterns = selection.map(SelectionMatcher::patterns).or(List.of());
        var positives = patterns.stream().filter(Predicate.not(SelectionMatcher::isExclusion)).toList();

        return ! excluded(patterns, simpleClassName) && (positives.isEmpty() || anyPositiveMatch(positives,
                                                                                                 simpleClassName));
    }

    private static boolean anyPositiveMatch(List<String> positives, String simpleClassName) {
        return positives.stream()
                        .anyMatch(pattern -> positiveMatch(pattern, simpleClassName));
    }

    private static boolean isExclusion(String pattern) {
        return pattern.startsWith("!");
    }

    private static boolean excluded(List<String> patterns, String simpleClassName) {
        return patterns.stream()
                       .filter(SelectionMatcher::isExclusion)
                       .map(pattern -> pattern.substring(1))
                       .anyMatch(pattern -> negativeMatch(pattern, simpleClassName));
    }

    private static boolean positiveMatch(String pattern, String simpleClassName) {
        return isRegex(pattern) || globMatches(outerClass(classPart(pattern)), simpleClassName);
    }

    /// An exclusion counts only when it names the WHOLE class: `!Outer$Inner` excludes a nested class, not
    /// `Outer`; `!Name#method` excludes one method while the class's other methods still run; a regex
    /// exclusion cannot be judged.
    private static boolean negativeMatch(String pattern, String simpleClassName) {
        var classPart = classPart(pattern);

        return ! pattern.contains("#")
               && !isRegex(pattern)
               && !classPart.contains("$")
               && globMatches(classPart, simpleClassName);
    }

    private static List<String> patterns(String selection) {
        return Arrays.stream(selection.split(","))
                     .map(String::trim)
                     .filter(Predicate.not(String::isEmpty))
                     .toList();
    }

    private static boolean isRegex(String pattern) {
        return pattern.startsWith("%regex[");
    }

    private static String classPart(String pattern) {
        var withoutMethod = pattern.contains("#")
                            ? pattern.substring(0, pattern.indexOf('#'))
                            : pattern;
        var withoutSuffix = withoutMethod.replaceAll("\\.(java|class)$", "");
        var simple = withoutSuffix.substring(Math.max(withoutSuffix.lastIndexOf('/'), withoutSuffix.lastIndexOf('.')) + 1);

        return simple.isEmpty()
               ? "*"
               : simple;
    }

    private static String outerClass(String classPart) {
        return classPart.contains("$")
               ? classPart.substring(0, classPart.indexOf('$'))
               : classPart;
    }

    /// Whole-name glob match: `*` is any run, `?` one character, everything else literal. A pattern that
    /// cannot be compiled (not reachable: every literal is quoted) is read as a possible match.
    private static boolean globMatches(String glob, String simpleClassName) {
        return Text.compilePattern(globRegex(glob))
                   .map(compiled -> compiled.matcher(simpleClassName)
                                            .matches())
                   .or(true);
    }

    private static String globRegex(String glob) {
        return Arrays.stream(glob.split("", -1))
                     .map(SelectionMatcher::globToken)
                     .reduce("", String::concat);
    }

    private static String globToken(String ch) {
        return switch (ch) {
            case "*" -> ".*";
            case "?" -> ".";
            default -> Pattern.quote(ch);
        };
    }

    record unused() implements SelectionMatcher {}
}
