package org.pragmatica.temper.domain.mvn;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.temper.domain.mvn.SideEffectAllowlist.Plugin;
import org.pragmatica.temper.domain.mvn.SideEffectAllowlist.Property;
import org.pragmatica.temper.domain.mvn.SideEffectAllowlist.PropertyKind;
import org.pragmatica.temper.domain.mvn.SideEffectAllowlist.ValueShape;
import org.pragmatica.temper.domain.mvn.UserOptions.Written;


/// The external side-effect classifier (m4-design §5.1, ruling R-M4-14, maintainer ruling Q-M4-11), judged over the parsed
/// [MvnStep] the refusals read: an **allowlist**, fail-safe. A step stays inside the unit only when every phase, every
/// goal (prefix `jar:jar` or coordinate `group:artifact[:version]:goal` form, `@execution` stripped) and every `-P`
/// profile of it, and every `-D` property of the user's own argv (exact key; a boolean key takes `true`/`false`; no
/// value holds a control, whitespace or format character, so a shown value never breaks a result line), and every option
/// of the user's argv ([UserOptions]: an exact listed spelling, its value of the listed shape; maintainer ruling Q-M4-11d), is
/// on the parsed [SideEffectAllowlist]; anything else is external, and an element starting with `-` that is no listed
/// spelling is named in the offence. `-fn` / `--fail-never` is refused on every argv: with it
/// a failing build exits 0, the same class as `maven.test.failure.ignore` (Q-M4-11c). When the list does not parse, every step is external
/// and the parse error is the offence. A planned step carries the planner's own `-D` tokens too
/// (`-Dmaven.repo.local`, `-Doutput`), which are not user input: [#plannedStepOffence] judges its goals, phases and
/// profiles only, and the user's properties are judged on the user's argv ([#offence]), before the planner joins them.
///
/// Honest limit: the classifier reads the submitted argv only. A publishing goal bound by the pom or a profile to an
/// allowed phase, a pom `defaultGoal`, a profile the pom activates by default or by a file, and the repository's own
/// `.mvn/maven.config` and `.mvn/jvm.config` are not seen; containment there is structural (no outside code runs on
/// bigboy, and the cloud hosts hold no credentials).
public sealed interface SideEffectRule {
    Result<SideEffectAllowlist> ALLOWLIST = SideEffectAllowlist.shipped();
    /// Selector and path grammar of the option shapes (Q-M4-11d): a name is `[A-Za-z0-9_.-]`, starts with `[A-Za-z0-9_]`
    /// and never holds `..`; a path is names joined by `/` (so no leading `/`, no `.` or `..` segment); a module selector
    /// is `:artifactId`, `groupId:artifactId` or a path.
    String NAME = "[A-Za-z0-9_](?:[A-Za-z0-9_-]|\\.(?!\\.))*";
    String PATH = NAME + "(?:/" + NAME + ")*";
    String SELECTOR = "(?::" + NAME + "|" + NAME + ":" + NAME + "|" + PATH + ")";
    Pattern THREADS = Pattern.compile("[1-9][0-9]*C?");
    Pattern MODULES = Pattern.compile("!?" + SELECTOR + "(?:,!?" + SELECTOR + ")*");
    Pattern MODULE = Pattern.compile(SELECTOR);
    Pattern FILE = Pattern.compile(PATH);

    /// The first option (argv order), then the first goal, then the first `-P` profile, then the first `-fn`, then the
    /// first `-D` property, of the user's `step` that is not on [#ALLOWLIST], described; none for a step that stays inside
    /// the unit.
    static Option<String> offence(MvnStep step) {
        return offence(step, ALLOWLIST);
    }

    static Option<String> offence(MvnStep step, Result<SideEffectAllowlist> allowlist) {
        return judged(allowlist,
                      parsed -> Stream.of(optionOffences(step, parsed),
                                          actionOffences(step, parsed),
                                          propertyOffences(step, parsed)).flatMap(offences -> offences));
    }

    /// As [#offence] for a planned step, whose `-D` properties include the planner's own: goals, phases, profiles and
    /// `-fn` only.
    static Option<String> plannedStepOffence(MvnStep step) {
        return plannedStepOffence(step, ALLOWLIST);
    }

    static Option<String> plannedStepOffence(MvnStep step, Result<SideEffectAllowlist> allowlist) {
        return judged(allowlist, parsed -> actionOffences(step, parsed));
    }

    private static Option<String> judged(Result<SideEffectAllowlist> allowlist,
                                         Fn1<Stream<String>, SideEffectAllowlist> offences) {
        return allowlist.fold(cause -> Option.some("the build-only allowlist does not parse (" + cause.message()
                                                  + "), so every step is external"),
                              parsed -> Option.from(offences.apply(parsed).findFirst()));
    }

    private static Stream<String> actionOffences(MvnStep step, SideEffectAllowlist allowlist) {
        return Stream.of(step.goals()
                             .stream()
                             .filter(goal -> !allowsGoal(allowlist, goal))
                             .map(SideEffectRule::goalOffence),
                         step.profiles()
                             .stream()
                             .filter(profile -> !allowlist.profiles()
                                                          .contains(profile))
                             .map(profile -> "profile '" + profile + "' is not on the build-only allowlist"),
                         step.argv()
                             .stream()
                             .skip(1)
                             .filter(SideEffectRule::failsNever)
                             .map(option -> "option '" + option + "' makes a failing build exit 0"))
                     .flatMap(offences -> offences);
    }

    /// The user's options off the list: an unlisted element starting with `-`, named; a listed one without its value or
    /// with a value not of its shape (the value is not echoed); a second `-f`/`--file` (verify6: Maven builds the first,
    /// [MvnStep] judges module inclusion against the last, so a repeat is refused rather than given a position meaning).
    private static Stream<String> optionOffences(MvnStep step, SideEffectAllowlist allowlist) {
        var written = UserOptions.written(step.argv().subList(1,
                                                              step.argv().size()),
                                          allowlist.options());

        return Stream.concat(written.stream().flatMap(option -> optionOffence(option).stream()),
                             repeatedFile(written));
    }

    private static Stream<String> repeatedFile(List<Written> written) {
        return written.stream()
                      .flatMap(option -> option instanceof Written.Listed listed && listed.option()
                                                                                          .shape() == ValueShape.FILE
                                         ? Stream.of(listed.name())
                                         : Stream.empty())
                      .skip(1)
                      .limit(1)
                      .map(name -> "option '" + name
                                  + "' is given more than once (Maven builds the first -f, temper would judge the last)");
    }

    private static Option<String> optionOffence(Written written) {
        return switch (written) {
            case Written.Unlisted unlisted -> Option.some(failsNever(unlisted.token())
                                                          ? "option '" + unlisted.token() + "' makes a failing build exit 0"
                                                          : "option '" + unlisted.token() + "' is not on the build-only allowlist");
            case Written.Listed listed -> listedOffence(listed);
        };
    }

    private static Option<String> listedOffence(Written.Listed listed) {
        return listed.option()
                     .shape() == ValueShape.NONE
               ? Option.none()
               : listed.value()
                       .fold(() -> Option.some("option '" + listed.name() + "' needs a value"),
                             value -> takesValue(listed.option().shape(),
                                                 value)
                                      ? Option.none()
                                      : Option.some("option '" + listed.name()
                                                   + "' takes " + shapeText(listed.option().shape())));
    }

    /// Whether `value` has `shape`; `PROFILES` and `DEFINE` values are judged by the profile and property entries.
    private static boolean takesValue(ValueShape shape, String value) {
        return switch (shape) {
            case THREADS -> THREADS.matcher(value).matches();
            case MODULES -> MODULES.matcher(value).matches();
            case MODULE -> MODULE.matcher(value).matches();
            case FILE -> FILE.matcher(value).matches();
            case NONE, PROFILES, DEFINE -> true;
        };
    }

    private static String shapeText(ValueShape shape) {
        return switch (shape) {
            case THREADS -> "an integer or <n>C";
            case MODULES -> "module selectors ([!]:artifactId, [!]groupId:artifactId or [!]relative/module/dir, comma-separated)";
            case MODULE -> "one module selector (:artifactId, groupId:artifactId or relative/module/dir)";
            case FILE -> "a relative path inside the workspace (no '..', no leading '/')";
            case NONE, PROFILES, DEFINE -> "a value";
        };
    }

    /// `-fn` or `--fail-never`. Maven 3.9.12 rejects abbreviations (`--fail-n`); every other spelling is unlisted anyway.
    private static boolean failsNever(String arg) {
        return arg.equals("-fn") || arg.equals("--fail-never");
    }

    private static Stream<String> propertyOffences(MvnStep step, SideEffectAllowlist allowlist) {
        return step.properties()
                   .stream()
                   .flatMap(property -> propertyOffence(allowlist, property).stream());
    }

    /// The user's `property` off the list: its key is not listed, its value holds a character that could break or hide
    /// text on a result line, or a boolean key has a value other than `true`/`false`.
    private static Option<String> propertyOffence(SideEffectAllowlist allowlist, MvnStep.Property property) {
        return Option.from(allowlist.properties()
                                    .stream()
                                    .filter(listed -> listed.name()
                                                            .equals(property.key()))
                                    .findFirst()).fold(() -> Option.some("property '" + property.key()
                                                                        + "' is not on the build-only allowlist"),
                                                       listed -> valueOffence(listed, property));
    }

    private static Option<String> valueOffence(Property listed, MvnStep.Property property) {
        return property.value()
                       .codePoints()
                       .anyMatch(SideEffectRule::unprintable)
               ? Option.some("property '" + property.key()
                            + "' has a control, whitespace or format character in its value")
               : takes(listed, property.value())
                 ? Option.none()
                 : Option.some("property '" + property.key() + "' takes true or false, got '" + property.value() + "'");
    }

    /// A control (C0, DEL, C1), whitespace, space separator or format (bidi, zero-width) character.
    private static boolean unprintable(int codePoint) {
        return Character.isISOControl(codePoint) || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || Character.getType(codePoint) == Character.FORMAT;
    }

    /// A boolean key takes `true` or `false` (no value reads as `true`); a selector key takes any value.
    private static boolean takes(Property listed, String value) {
        return listed.kind() == PropertyKind.SELECTOR || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
    }

    private static boolean allowsGoal(SideEffectAllowlist allowlist, String goal) {
        var segments = Arrays.asList(withoutExecution(goal).split(":"));

        return segments.size() == 1
               ? allowlist.phases()
                          .contains(segments.getFirst())
               : allowlist.plugins()
                          .stream()
                          .anyMatch(plugin -> names(plugin, segments) && plugin.goals()
                                                                               .contains(segments.getLast()));
    }

    /// The goal's `segments` name `plugin`: `prefix:goal`, or `groupId:artifactId[:version]:goal`.
    private static boolean names(Plugin plugin, List<String> segments) {
        return segments.size() == 2
               ? segments.getFirst()
                         .equals(plugin.prefix())
               : segments.size() > 2 && String.join(":",
                                                    segments.subList(0, 2))
                                              .equals(plugin.coordinates());
    }

    private static String goalOffence(String goal) {
        return (goal.contains(":")
                ? "goal '"
                : "phase '") + goal + "' is not on the build-only allowlist";
    }

    /// `goal@execution` → `goal`.
    private static String withoutExecution(String goal) {
        return goal.split("@", 2) [0];
    }

    record unused() implements SideEffectRule {}
}
