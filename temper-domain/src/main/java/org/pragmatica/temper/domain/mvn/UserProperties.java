package org.pragmatica.temper.domain.mvn;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.mvn.SideEffectAllowlist.Property;
import org.pragmatica.temper.domain.mvn.SideEffectAllowlist.PropertyKind;
import org.pragmatica.temper.domain.mvn.SideEffectAllowlist.ValueShape;
import org.pragmatica.temper.domain.mvn.UserOptions.Written;


/// The `-D` properties, reactor-narrowing options and unlisted options a user wrote into a job's arguments, as the job's
/// result shows them (m4-design §5.1, maintainer rulings Q-M4-11c, Q-M4-11d), in argv order, read by [UserOptions] against the
/// option entries of [SideEffectAllowlist]: every property, marked by what it does to the tests; every option the list
/// marks `narrows` (`-pl`, `-rf`, `-f`, `-N`), with its value, marked [TestEffect#NARROWED]; every element starting with
/// `-` that is no listed spelling (a job stored before the option list, or any job when the list does not parse), as
/// written, marked [TestEffect#UNKNOWN]. So a green run with tests skipped, narrowed or run under an option temper does
/// not know never reads as tested. A `skip` key of [SideEffectAllowlist] set true (or
/// with no value) marks [TestEffect#SKIPPED]; a `selector` key, whatever its value, marks [TestEffect#NARROWED]; any
/// other property is shown unmarked (a `skip` key set false, a `boolean` key, and a key a job stored before the
/// allowlist carried). A key given twice is judged per occurrence, though Maven applies the last: `-DskipTests
/// -DskipTests=false` is marked skipped, which errs toward marking (m4-design §5.1).
public sealed interface UserProperties {
    /// What a property does to the tests the job runs.
    enum TestEffect {
        SKIPPED,
        NARROWED,
        UNKNOWN;
        /// The wire and text form: `skipped`, `narrowed`, `unknown`.
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /// One user argument as shown and its mark: `-D<key>=<value>` (a bare `-Dkey` reads as `=true`, as Maven sets it),
    /// or a narrowing option with its value (`-pl core`).
    record Shown(String argument, Option<TestEffect> effect) {
        public static Shown shown(String argument, Option<TestEffect> effect) {
            return new Shown(argument, effect);
        }

        /// A property as `-Dkey=value`.
        public static Shown define(String key, String value, Option<TestEffect> effect) {
            return shown("-D" + key + "=" + value, effect);
        }
    }

    /// The user's own arguments, judged against [SideEffectRule#ALLOWLIST].
    static List<Shown> shown(List<String> userArgs) {
        return shown(userArgs, SideEffectRule.ALLOWLIST);
    }

    /// With a list that does not parse, no option is listed, so every option is shown [TestEffect#UNKNOWN] (every
    /// submission is refused then anyway).
    static List<Shown> shown(List<String> userArgs, Result<SideEffectAllowlist> allowlist) {
        var listed = allowlist.map(SideEffectAllowlist::properties).or(List.of());

        return UserOptions.written(userArgs,
                                   allowlist.map(SideEffectAllowlist::options).or(List.of()))
                          .stream()
                          .flatMap(written -> shown(written, listed).stream())
                          .toList();
    }

    private static Option<Shown> shown(Written written, List<Property> listed) {
        return switch (written) {
            case Written.Unlisted unlisted -> Option.some(Shown.shown(unlisted.token(), Option.some(TestEffect.UNKNOWN)));
            case Written.Listed option -> listedShown(option, listed);
        };
    }

    /// A `-D` value as its property, a narrowing option with its value (`-pl core`); any other listed option is not shown.
    private static Option<Shown> listedShown(Written.Listed option, List<Property> listed) {
        return option.option()
                     .shape() == ValueShape.DEFINE
               ? option.value()
                       .map(MvnStep::definition)
                       .map(property -> Shown.define(property.key(),
                                                     property.value(),
                                                     effect(listed, property)))
               : narrowed(option);
    }

    private static Option<Shown> narrowed(Written.Listed option) {
        return option.option()
                     .narrows()
               ? Option.some(Shown.shown(Stream.concat(Stream.of(option.name()),
                                                       option.value().stream())
                                               .collect(Collectors.joining(" ")),
                                         Option.some(TestEffect.NARROWED)))
               : Option.none();
    }

    private static Option<TestEffect> effect(List<Property> listed, MvnStep.Property property) {
        return Option.from(listed.stream().filter(entry -> entry.name()
                                                                .equals(property.key())).findFirst()).flatMap(entry -> effect(entry.kind(),
                                                                                                                              property.value()));
    }

    private static Option<TestEffect> effect(PropertyKind kind, String value) {
        return switch (kind) {
            case SKIP -> value.equalsIgnoreCase("true")
                         ? Option.some(TestEffect.SKIPPED)
                         : Option.none();
            case SELECTOR -> Option.some(TestEffect.NARROWED);
            case BOOLEAN -> Option.none();
        };
    }

    record unused() implements UserProperties {}
}
