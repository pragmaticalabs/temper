package org.pragmatica.temper.domain.manifest;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Text;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The surefire/failsafe class-selection alphabet, implemented once (spec §5.2, m1-design §2.4 rule 2).
///
/// A class is matched by its **class path**: its binary name with the package dots turned into `/`, without
/// an extension (`org/acme/FooTest`, `org/acme/Outer$Inner`). A glob pattern is normalised as surefire's
/// `ResolvedTest` does: a `.java`/`.class` suffix is dropped, a dotted name without `/` is a fully
/// qualified class (`org.acme.FooTest` → `org/acme/FooTest`), and a pattern not starting with `**/` gets
/// that prefix. In a glob, a whole `**` segment spans directories; `*` (and a `**` inside a segment) and `?`
/// never cross `/`, as in Ant/plexus `matchPath`.
///
/// A `-Dtest` / `-Dit.test` value is a comma list of patterns, each optionally negated with `!`, narrowed
/// to methods with `#method` (method globs joined by `+`), or written as `%regex[class#method]`, whose class
/// regex must match the whole class-file path (`org/acme/FooTest.class`), so `%regex[.*FooTest]` matches nothing.
/// An `includes` element may be negated with `!` too: surefire reads it as one more exclude.
///
/// The M0 refusal matcher (`refusal/SelectionMatcher`, simple names, conservative) is a separate, coarser
/// judgement for refusals and is deliberately not folded in here (WP1 owns it).
public sealed interface SurefirePatterns {
    /// Surefire's default `includes`.
    List<String> SUREFIRE_DEFAULT_INCLUDES = List.of("**/Test*", "**/*Test", "**/*Tests", "**/*TestCase");
    /// Failsafe's default `includes`.
    List<String> FAILSAFE_DEFAULT_INCLUDES = List.of("**/IT*", "**/*IT", "**/*ITCase");
    /// The default `excludes` of both plugins: every nested or anonymous class file.
    List<String> DEFAULT_EXCLUDES = List.of("**/*$*");
    /// The tokens of one glob segment: a run of `*`, a `?`, or a literal run.
    Pattern GLOB_TOKEN = Pattern.compile("\\*+|\\?|[^*?]+");

    /// The default `includes` of `runner`.
    static List<String> defaultIncludes(Runner runner) {
        return switch (runner) {
            case SUREFIRE -> SUREFIRE_DEFAULT_INCLUDES;
            case FAILSAFE -> FAILSAFE_DEFAULT_INCLUDES;
        };
    }

    /// Whether `includes`/`excludes` select the class at `classPath`: some include matches, or every include is
    /// a `!` exclusion; and neither an exclude nor a `!` include matches.
    static boolean isSelected(List<String> includes, List<String> excludes, String classPath) {
        var positives = includes.stream().filter(Predicate.not(SurefirePatterns::isNegated)).toList();
        var negatives = includes.stream()
                                .filter(SurefirePatterns::isNegated)
                                .map(SurefirePatterns::withoutNegation)
                                .toList();

        return (positives.isEmpty() || isMatchedByAny(positives, classPath))
               && !isMatchedByAny(excludes, classPath)
               && !isMatchedByAny(negatives, classPath);
    }

    /// Whether a `-Dtest`/`-Dit.test` value names a method anywhere (`#`, positive or negated). Only then does
    /// surefire filter test methods, and it judges each method by the class that declares it in the test tree
    /// (a `@Nested` class's own name), not by the class it was discovered from.
    static boolean hasMethodPatterns(String selection) {
        return patterns(selection).stream()
                       .anyMatch(pattern -> pattern.methodPattern()
                                                   .isPresent());
    }

    /// Whether the glob or `%regex[]` `pattern` matches the class at `classPath`.
    static boolean isClassMatch(String pattern, String classPath) {
        return isRegex(pattern)
               ? isClassPatternMatch(regexBody(pattern), true, classPath)
               : isClassPatternMatch(pattern, false, classPath);
    }

    /// Whether a `-Dtest`/`-Dit.test` value has surefire scan the class at `classPath`: some positive pattern's
    /// class part matches (or there is no positive pattern), and no negation without a method does. Method parts
    /// play no role here: surefire filters the methods afterwards, and only when [#hasMethodPatterns].
    static boolean isClassSelected(String selection, String classPath) {
        var patterns = patterns(selection);
        var positives = patterns.stream().filter(Predicate.not(TestPattern::negated)).toList();

        return (positives.isEmpty() || positives.stream()
                                                .anyMatch(pattern -> pattern.isMatchingClass(classPath))) && patterns.stream()
                                                                                                                     .filter(TestPattern::isClassNegation)
                                                                                                                     .noneMatch(pattern -> pattern.isMatchingClass(classPath));
    }

    /// The test methods of the class at `classPath` that a `-Dtest`/`-Dit.test` value selects. Without any
    /// positive pattern every class is chosen (surefire 3.5.4: neither the default includes nor the default
    /// excludes apply once `-Dtest` is set); a negative pattern removes the methods it names, or the whole
    /// class when it names no method.
    static Set<String> selectedMethods(String selection, String classPath, Set<String> methodNames) {
        var patterns = patterns(selection);
        var positives = patterns.stream().filter(Predicate.not(TestPattern::negated)).toList();
        var negatives = patterns.stream().filter(TestPattern::negated).toList();

        return methodNames.stream()
                          .filter(method -> isPositivelySelected(positives, classPath, method))
                          .filter(method -> !isSelectedByAny(negatives, classPath, method))
                          .collect(Collectors.toUnmodifiableSet());
    }

    /// One element of a `-Dtest` value.
    record TestPattern(boolean negated, String classPattern, Option<String> methodPattern, boolean regex) {
        static TestPattern testPattern(String raw) {
            var negated = isNegated(raw);
            var body = negated
                       ? withoutNegation(raw)
                       : raw;

            return isRegex(body)
                   ? testPattern(negated, regexBody(body), true)
                   : testPattern(negated, body, false);
        }

        private static TestPattern testPattern(boolean negated, String body, boolean regex) {
            var hash = body.indexOf('#');

            return hash < 0
                   ? new TestPattern(negated, classPatternOf(body, regex), none(), regex)
                   : new TestPattern(negated,
                                     classPatternOf(body.substring(0, hash), regex),
                                     some(body.substring(hash + 1)),
                                     regex);
        }

        private static String classPatternOf(String classPart, boolean regex) {
            return classPart.isEmpty()
                   ? emptyClassPattern(regex)
                   : classPart;
        }

        private static String emptyClassPattern(boolean regex) {
            return regex
                   ? ".*"
                   : "**/*";
        }

        boolean isSelecting(String classPath, String method) {
            return isMatchingClass(classPath) && methodPattern.map(pattern -> isMatchingMethod(pattern, method))
                                                              .or(true);
        }

        private boolean isMatchingClass(String classPath) {
            return isClassPatternMatch(classPattern, regex, classPath);
        }

        private boolean isClassNegation() {
            return negated && methodPattern.isEmpty();
        }

        private boolean isMatchingMethod(String pattern, String method) {
            return regex
                   ? isRegexMatch(pattern, method)
                   : Arrays.stream(pattern.split("\\+"))
                           .map(String::trim)
                           .anyMatch(glob -> isRegexMatch(methodGlobRegex(glob),
                                                          method));
        }
    }

    private static boolean isPositivelySelected(List<TestPattern> positives, String classPath, String method) {
        return positives.isEmpty() || isSelectedByAny(positives, classPath, method);
    }

    private static boolean isSelectedByAny(List<TestPattern> patterns, String classPath, String method) {
        return patterns.stream()
                       .anyMatch(pattern -> pattern.isSelecting(classPath, method));
    }

    private static List<TestPattern> patterns(String selection) {
        return Arrays.stream(selection.split(","))
                     .map(String::trim)
                     .filter(Predicate.not(String::isEmpty))
                     .map(TestPattern::testPattern)
                     .toList();
    }

    private static boolean isMatchedByAny(List<String> patterns, String classPath) {
        return patterns.stream()
                       .anyMatch(pattern -> isClassMatch(pattern, classPath));
    }

    /// A class regex is matched against the whole class-file path, a glob against the class path.
    private static boolean isClassPatternMatch(String classPattern, boolean regex, String classPath) {
        return regex
               ? isRegexMatch(classPattern, classPath + ".class")
               : isRegexMatch(globRegex(normalise(classPattern)), classPath);
    }

    private static boolean isNegated(String pattern) {
        return pattern.startsWith("!");
    }

    private static String withoutNegation(String pattern) {
        return pattern.substring(1)
                      .trim();
    }

    private static boolean isRegex(String pattern) {
        return pattern.startsWith("%regex[") && pattern.endsWith("]");
    }

    private static String regexBody(String pattern) {
        return pattern.substring("%regex[".length(), pattern.length() - 1);
    }

    /// Whole-input match. A regex that does not compile selects nothing: surefire itself rejects it, so the
    /// build fails and no class of it runs.
    private static boolean isRegexMatch(String regex, String input) {
        return Text.compilePattern(regex)
                   .map(compiled -> compiled.matcher(input)
                                            .matches())
                   .or(false);
    }

    /// Surefire's `ResolvedTest` normalisation of a class glob.
    private static String normalise(String pattern) {
        var withoutSuffix = pattern.replaceAll("\\.(java|class)$", "");
        var asPath = withoutSuffix.contains("/")
                     ? withoutSuffix
                     : withoutSuffix.replace('.', '/');

        return asPath.startsWith("**/")
               ? asPath
               : "**/" + asPath;
    }

    /// `**/` is zero or more directories, `**` anything, `*` a run within one segment, `?` one character of
    /// a segment; everything else is literal.
    private static String globRegex(String glob) {
        var segments = List.of(glob.split("/", -1));

        return IntStream.range(0,
                               segments.size())
                        .mapToObj(index -> segmentRegex(segments.get(index),
                                                        index == segments.size() - 1))
                        .collect(Collectors.joining());
    }

    /// A whole `**` segment spans directories (zero or more before a further segment, anything at the end);
    /// any other segment matches within one directory level, followed by its `/`.
    private static String segmentRegex(String segment, boolean isLast) {
        if (segment.equals("**")) {
            return isLast
                   ? ".*"
                   : "(?:[^/]*/)*";
        }

        return isLast
               ? withinSegment(segment)
               : withinSegment(segment) + "/";
    }

    /// Inside a segment a run of `*` (Ant reads `**` there as `*`) never crosses `/`, nor does `?`.
    private static String withinSegment(String segment) {
        return GLOB_TOKEN.matcher(segment)
                         .results()
                         .map(MatchResult::group)
                         .map(SurefirePatterns::segmentToken)
                         .collect(Collectors.joining());
    }

    private static String segmentToken(String token) {
        if (token.startsWith("*")) {
            return "[^/]*";
        }

        return token.equals("?")
               ? "[^/]"
               : Pattern.quote(token);
    }

    /// A method glob: `*` is any run, `?` one character.
    private static String methodGlobRegex(String glob) {
        return GLOB_TOKEN.matcher(glob)
                         .results()
                         .map(MatchResult::group)
                         .map(SurefirePatterns::methodGlobToken)
                         .collect(Collectors.joining());
    }

    private static String methodGlobToken(String token) {
        if (token.startsWith("*")) {
            return ".*";
        }

        return token.equals("?")
               ? "."
               : Pattern.quote(token);
    }

    record unused() implements SurefirePatterns {}
}
