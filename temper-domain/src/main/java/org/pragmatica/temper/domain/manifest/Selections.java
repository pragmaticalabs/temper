package org.pragmatica.temper.domain.manifest;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Which test-plugin executions a unit reaches, and their resolved parameters (m1-design §2.4 rules 1-3).
///
/// **Rule 1, reached.** A module whose packaging is not `pom` contributes every surefire `test` and failsafe
/// `integration-test` execution whose phase (the execution's own, else the goal's default) is a default
/// lifecycle phase at or before `test` (reach `TEST`) or `verify` (reach `INTEGRATION_TEST`). `none` is
/// unbound. Every phase before the reached one counts, not only `test`/`integration-test`, because Maven runs
/// all of them. A phase outside the default lifecycle, or `install`/`deploy` under `INTEGRATION_TEST` (which
/// a `verify` unit does not run but an `install` unit does), is Unsupported rather than guessed.
///
/// **Rule 2, parameters.** An explicit configuration value wins (the effective POM has already interpolated
/// it; a value that is still exactly `${name}` is evaluated as Maven does at run time, against the user and
/// then the module properties, and is unset when neither defines it). Otherwise the user property applies,
/// then the module's property, then the plugin default.
///
/// **Rule 3, unsupported.** `includesFile`, `excludesFile`, `dependenciesToScan` (as configuration or
/// property), a tag expression in `groups`/`excludedGroups`, and a JUnit 4 category class name there. So is a
/// pattern surefire itself rejects, failing the build, in a non-skipped execution without `test`: `#` in an
/// include or exclude, `!` in an exclude. (`!` in an include is valid: it is one more exclude.) So is a non-skipped
/// execution whose plugin version has no measured class naming ([ReportNaming], rule 5).
///
/// A flag property is `true` only when its value reads `true`. The bare `-DskipTests` reaches the engine as
/// `true` (Maven, and `MvnStep`, give a valueless define that value); an explicit empty `-DskipTests=` is not
/// a skip. Surefire 3.5.4 runs the tests for it, which the oracle fixture confirms.
public sealed interface Selections {
    /// Default-lifecycle phases in order.
    List<String> LIFECYCLE = List.of("validate",
                                     "initialize",
                                     "generate-sources",
                                     "process-sources",
                                     "generate-resources",
                                     "process-resources",
                                     "compile",
                                     "process-classes",
                                     "generate-test-sources",
                                     "process-test-sources",
                                     "generate-test-resources",
                                     "process-test-resources",
                                     "test-compile",
                                     "process-test-classes",
                                     "test",
                                     "prepare-package",
                                     "package",
                                     "pre-integration-test",
                                     "integration-test",
                                     "post-integration-test",
                                     "verify",
                                     "install",
                                     "deploy");

    /// Parameters whose effect the rules do not model, as configuration names.
    List<String> UNSUPPORTED_CONFIG = List.of("includesFile", "excludesFile", "dependenciesToScan");

    /// The same parameters, as the user/module properties the plugins read them from.
    List<String> UNSUPPORTED_PROPERTIES = List.of("surefire.includesFile",
                                                  "surefire.excludesFile",
                                                  "failsafe.includesFile",
                                                  "failsafe.excludesFile",
                                                  "dependenciesToScan");

    /// The tag parameters, scalar comma lists; written as a list element they cannot be evaluated.
    List<String> TAG_PARAMETERS = List.of("groups", "excludedGroups");
    /// A JUnit tag expression operator.
    Pattern TAG_OPERATOR = Pattern.compile("[&|!()]");
    /// A fully qualified class name: a JUnit 4 category rather than a JUnit 5 tag.
    Pattern CATEGORY_CLASS = Pattern.compile("^([a-z_][\\w]*\\.)+[A-Z][\\w$]*$");

    /// Every reached execution of `modules`, with its parameters. The first unsupported configuration fails the
    /// whole resolution with its own cause (fail-fast: one reason is what `ERROR(manifest: <reason>)` reports).
    static Result<List<ModuleSelection>> resolve(List<PomModule> modules, ManifestSpec spec) {
        return Result.firstFailureOf(modules.stream()
                                            .filter(Selections::isTestBearing)
                                            .flatMap(module -> executions(module, spec))
                                            .toList()).map(Selections::present);
    }

    private static boolean isTestBearing(PomModule module) {
        return ! module.packaging()
                       .equals("pom");
    }

    private static Stream<Result<Option<ModuleSelection>>> executions(PomModule module, ManifestSpec spec) {
        return module.testPlugins()
                     .stream()
                     .flatMap(plugin -> pluginSelections(module, plugin, spec));
    }

    private static Stream<Result<Option<ModuleSelection>>> pluginSelections(PomModule module,
                                                                            PluginUse plugin,
                                                                            ManifestSpec spec) {
        return plugin.executions()
                     .stream()
                     .filter(execution -> hasTestGoal(plugin.runner(),
                                                      execution))
                     .map(execution -> selection(Lookup.lookup(module, plugin, execution, spec),
                                                 execution));
    }

    private static List<ModuleSelection> present(List<Option<ModuleSelection>> selections) {
        return selections.stream()
                         .flatMap(Option::stream)
                         .toList();
    }

    private static boolean hasTestGoal(Runner runner, Execution execution) {
        return execution.goals()
                        .contains(runner.goal());
    }

    private static Result<Option<ModuleSelection>> selection(Lookup lookup, Execution execution) {
        return reached(lookup, execution).flatMap(isReached -> selectionIfReached(lookup, isReached));
    }

    private static Result<Option<ModuleSelection>> selectionIfReached(Lookup lookup, boolean isReached) {
        return isReached
               ? params(lookup).flatMap(params -> selection(lookup, params))
               : success(none());
    }

    private static Result<Option<ModuleSelection>> selection(Lookup lookup, TestParams params) {
        return naming(lookup, params).map(naming -> some(ModuleSelection.moduleSelection(lookup.module(),
                                                                                         lookup.runner(),
                                                                                         params,
                                                                                         naming)));
    }

    /// Rule 5's naming by the plugin version ([ReportNaming]). A skipped execution expects no class, so its naming is
    /// never read and its version is not judged: an old plugin in a module the unit skips refuses nothing.
    private static Result<ReportNaming> naming(Lookup lookup, TestParams params) {
        return params.skipped()
               ? success(ReportNaming.BINARY_NAME)
               : ReportNaming.reportNaming(lookup.module().path(),
                                           lookup.runner(),
                                           lookup.plugin().version());
    }

    /// Rule 1: whether the execution's phase runs under the spec's reach.
    private static Result<Boolean> reached(Lookup lookup, Execution execution) {
        var phase = execution.phase().or(lookup.runner().goal());
        var reach = lookup.spec().reach();

        return isPlaceable(phase, reach)
               ? success(isReachedPhase(phase, reach))
               : unsupportedPhase(lookup, execution, phase);
    }

    /// `none`, or a default-lifecycle phase the reach can place: `install`/`deploy` cannot be placed under
    /// `INTEGRATION_TEST`, which covers both `verify` (does not run them) and `install` (runs `install`).
    private static boolean isPlaceable(String phase, Reach reach) {
        return phase.equals("none") || LIFECYCLE.contains(phase)
                                       && !(reach == Reach.INTEGRATION_TEST && LIFECYCLE.indexOf(phase) > LIFECYCLE.indexOf("verify"));
    }

    private static boolean isReachedPhase(String phase, Reach reach) {
        return ! phase.equals("none") && LIFECYCLE.indexOf(phase) <= reachIndex(reach);
    }

    private static Result<Boolean> unsupportedPhase(Lookup lookup, Execution execution, String phase) {
        return ManifestError.UnplaceablePhase.FACTORY.apply(lookup.module().path(),
                                                            execution.id(),
                                                            phase)
                                                     .result();
    }

    private static int reachIndex(Reach reach) {
        return switch (reach) {
            case TEST -> LIFECYCLE.indexOf("test");
            case INTEGRATION_TEST -> LIFECYCLE.indexOf("verify");
        };
    }

    /// Rules 2 and 3 for one reached execution.
    private static Result<TestParams> params(Lookup lookup) {
        return supported(lookup).flatMap(_ -> tags(lookup, "groups"))
                        .flatMap(groups -> withExcludedGroups(lookup, groups))
                        .flatMap(params -> accepted(lookup, params));
    }

    /// The parameters, unless surefire would reject one of the patterns it is about to use.
    private static Result<TestParams> accepted(Lookup lookup, TestParams params) {
        return rejection(lookup, params).map(error -> error.<TestParams> result())
                        .or(() -> success(params));
    }

    /// Surefire checks `includes`/`excludes` only when it runs and `test` does not replace them.
    private static Option<ManifestError.RejectedPattern> rejection(Lookup lookup, TestParams params) {
        return params.skipped() || params.test()
                                         .isPresent()
               ? none()
               : Option.from(Stream.concat(rejected(lookup,
                                                    "includes",
                                                    params.includes(),
                                                    "#"),
                                           rejected(lookup,
                                                    "excludes",
                                                    params.excludes(),
                                                    "#",
                                                    "!"))
                                   .findFirst());
    }

    private static Stream<ManifestError.RejectedPattern> rejected(Lookup lookup,
                                                                  String parameter,
                                                                  List<String> patterns,
                                                                  String... prohibited) {
        return patterns.stream()
                       .filter(pattern -> isProhibited(pattern, prohibited))
                       .map(pattern -> ManifestError.RejectedPattern.FACTORY.apply(lookup.module().path(),
                                                                                   parameter,
                                                                                   pattern));
    }

    private static boolean isProhibited(String pattern, String... prohibited) {
        return Arrays.stream(prohibited).anyMatch(pattern::contains);
    }

    private static Result<TestParams> withExcludedGroups(Lookup lookup, Set<String> groups) {
        return tags(lookup, "excludedGroups").map(excludedGroups -> testParams(lookup, groups, excludedGroups));
    }

    private static TestParams testParams(Lookup lookup, Set<String> groups, Set<String> excludedGroups) {
        return TestParams.testParams(patterns(lookup, "includes").or(() -> SurefirePatterns.defaultIncludes(lookup.runner())),
                                     patterns(lookup, "excludes").or(() -> SurefirePatterns.DEFAULT_EXCLUDES),
                                     groups,
                                     excludedGroups,
                                     isSkipped(lookup),
                                     lookup.value("test",
                                                  lookup.runner().selectionProperty())
                                           .filter(Predicate.not(String::isBlank)));
    }

    private static Result<Unit> supported(Lookup lookup) {
        return firstUnsupported(lookup).map(parameter -> unsupported(lookup, parameter))
                               .or(Result::unitResult);
    }

    private static Option<String> firstUnsupported(Lookup lookup) {
        return Option.from(Stream.of(UNSUPPORTED_CONFIG.stream().filter(lookup::isConfigured),
                                     UNSUPPORTED_PROPERTIES.stream().filter(lookup::hasProperty),
                                     TAG_PARAMETERS.stream().filter(lookup::isListValued))
                                 .flatMap(Function.identity())
                                 .findFirst());
    }

    private static Result<Unit> unsupported(Lookup lookup, String parameter) {
        return ManifestError.UnsupportedParameter.FACTORY.apply(lookup.module().path(),
                                                                parameter)
                                                         .result();
    }

    /// `skip`, `skipTests`, `skipExec`; failsafe also `skipITs`.
    private static boolean isSkipped(Lookup lookup) {
        return lookup.isFlagSet("skip", "maven.test.skip") || lookup.isFlagSet("skipTests", "skipTests") || lookup.isFlagSet("skipExec",
                                                                                                                             "maven.test.skip.exec") || isIntegrationSkip(lookup);
    }

    private static boolean isIntegrationSkip(Lookup lookup) {
        return lookup.runner() == Runner.FAILSAFE && lookup.isFlagSet("skipITs", "skipITs");
    }

    /// A configured pattern list; an empty one is no configuration, and the plugin default applies.
    private static Option<List<String>> patterns(Lookup lookup, String name) {
        return lookup.config(name)
                     .map(Selections::values)
                     .filter(Predicate.not(List::isEmpty));
    }

    private static List<String> values(ConfigValue value) {
        return switch (value) {
            case ConfigValue.Text text -> commaSeparated(text.value());
            case ConfigValue.Items items -> items.values().stream().map(Selections::commaSeparated).flatMap(List::stream).toList();
        };
    }

    private static Result<Set<String>> tags(Lookup lookup, String parameter) {
        var tags = lookup.value(parameter, parameter).map(Selections::commaSeparated).or(List.of());

        return Result.firstFailureOf(tags.stream().map(tag -> plainTag(lookup, parameter, tag)).toList()).map(Set::copyOf);
    }

    private static Result<String> plainTag(Lookup lookup, String parameter, String tag) {
        if (TAG_OPERATOR.matcher(tag).find()) {
            return ManifestError.UnsupportedTagExpression.FACTORY.apply(lookup.module().path(),
                                                                        parameter,
                                                                        tag)
                                                                 .result();
        }

        return CATEGORY_CLASS.matcher(tag).matches()
               ? ManifestError.Junit4Category.FACTORY.apply(lookup.module().path(),
                                                            parameter,
                                                            tag)
                                                     .result()
               : success(tag);
    }

    private static List<String> commaSeparated(String value) {
        return Arrays.stream(value.split(","))
                     .map(String::trim)
                     .filter(Predicate.not(String::isEmpty))
                     .toList();
    }

    /// The parameter sources of one execution, in precedence order.
    record Lookup(PomModule module, PluginUse plugin, Execution execution, ManifestSpec spec) {
        static Lookup lookup(PomModule module, PluginUse plugin, Execution execution, ManifestSpec spec) {
            return new Lookup(module, plugin, execution, spec);
        }

        Runner runner() {
            return plugin.runner();
        }

        Option<ConfigValue> config(String name) {
            return option(execution.config().get(name));
        }

        boolean isListValued(String name) {
            return config(name).filter(ConfigValue.Items.class::isInstance)
                         .isPresent();
        }

        boolean isConfigured(String name) {
            return execution.config()
                            .containsKey(name);
        }

        boolean hasProperty(String property) {
            return userProperty(property).orElse(() -> moduleProperty(property))
                               .isPresent();
        }

        /// Rule 2: explicit configuration, else user property, else module property.
        Option<String> value(String name, String property) {
            return isConfigured(name)
                   ? config(name).flatMap(this::evaluate)
                   : userProperty(property).orElse(() -> moduleProperty(property));
        }

        boolean isFlagSet(String name, String property) {
            return value(name, property).map(text -> text.toLowerCase(Locale.ROOT)
                                                         .equals("true"))
                        .or(false);
        }

        /// A configured value as the mojo receives it: a whole `${name}` that interpolation left behind is
        /// evaluated against the user and module properties; a list value is not a scalar.
        private Option<String> evaluate(ConfigValue value) {
            return switch (value) {
                case ConfigValue.Text text -> evaluateText(text.value());
                case ConfigValue.Items _ -> none();
            };
        }

        private Option<String> property(String name) {
            return userProperty(name).orElse(() -> moduleProperty(name));
        }

        private Option<String> userProperty(String property) {
            return selectionOverride(property).orElse(() -> option(spec.userProperties().get(property)));
        }

        /// `ManifestSpec.test`/`itTest` carry the main step's `-Dtest`/`-Dit.test`.
        private Option<String> selectionOverride(String property) {
            return switch (property) {
                case "test" -> spec.test();
                case "it.test" -> spec.itTest();
                default -> none();
            };
        }

        private Option<String> moduleProperty(String property) {
            return option(module.properties().get(property));
        }

        private Option<String> evaluateText(String text) {
            return isExpression(text)
                   ? property(text.substring(2, text.length() - 1))
                   : some(text);
        }

        private static boolean isExpression(String text) {
            return text.startsWith("${") && text.indexOf('}') == text.length() - 1;
        }
    }

    record unused() implements Selections {}
}
