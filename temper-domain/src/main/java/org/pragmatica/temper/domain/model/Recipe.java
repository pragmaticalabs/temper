package org.pragmatica.temper.domain.model;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.mvn.MvnStep;
import org.pragmatica.temper.domain.mvn.SideEffectRule;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// What to run. `canonical()` is the recipe's dedup form (m0-design §3.5): keys sorted, no whitespace,
/// absent options omitted, lists in submitted order (argument order is semantic for mvn).
public sealed interface Recipe {
    /// Wire/DB kind: `mvn`, `module-test`, `gate`, `rerun`, or the unsupported kind's own name.
    String kind();
    /// Canonical JSON used for dedup.
    String canonical();
    /// Whether the recipe's units act outside their workspace (m4-design §5.1): abstract, so every recipe kind declares
    /// it. An `EXTERNAL` unit is never requeued after a host loss.
    SideEffects sideEffects();

    /// One unit running `mvn <args>`.
    record Mvn(List<MvnArg> args) implements Recipe {
        public Mvn {
            args = List.copyOf(args);
        }

        @Override
        public String kind() {
            return "mvn";
        }

        @Override
        public String canonical() {
            return CanonicalJson.object(Map.of("mvn",
                                               CanonicalJson.object(Map.of("args", CanonicalJson.stringArray(values())))));
        }

        /// `EXTERNAL` unless every phase, goal and profile of the arguments is on [SideEffectRule]'s allowlist; such a job is refused at
        /// submit (R-EFFECT, ruling R-M4-14), so an accepted `Mvn` unit is always `NONE`.
        @Override
        public SideEffects sideEffects() {
            return SideEffectRule.offence(MvnStep.mvnStep(Stream.concat(Stream.of("mvn"), values().stream()).toList())).isPresent()
                   ? SideEffects.EXTERNAL
                   : SideEffects.NONE;
        }

        /// The raw argument strings in submitted order.
        public List<String> values() {
            return args.stream()
                       .map(MvnArg::value)
                       .toList();
        }
    }

    /// One unit: `-pl <pl> -am install -DskipTests`, then the selected tests.
    record ModuleTest(List<ModulePath> pl, Option<TestSelection> test, Option<TestSelection> itTest) implements Recipe {
        public ModuleTest {
            pl = List.copyOf(pl);
        }

        @Override
        public String kind() {
            return "module-test";
        }

        @Override
        public SideEffects sideEffects() {
            return SideEffects.NONE;
        }

        @Override
        public String canonical() {
            return CanonicalJson.object(Map.of("moduleTest", CanonicalJson.object(members())));
        }

        /// The `-pl` value: module paths joined with commas.
        public String projectList() {
            return String.join(",",
                               pl.stream().map(ModulePath::value).toList());
        }

        private Map<String, String> members() {
            return Stream.of(Stream.of(Map.entry("pl",
                                                 CanonicalJson.stringArray(pl.stream().map(ModulePath::value).toList()))),
                             selectionMember("test", test),
                             selectionMember("itTest", itTest))
                         .flatMap(Function.identity())
                         .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        private static Stream<Map.Entry<String, String>> selectionMember(String key, Option<TestSelection> selection) {
            return selection.map(present -> Map.entry(key,
                                                      CanonicalJson.string(present.value())))
                            .stream();
        }
    }

    /// The unsharded Gate (m1-design §1.1): one unit running `clean install` over the whole reactor, through
    /// bb-hold while `aether/ember` is in the reactor.
    record Gate() implements Recipe {
        @Override
        public String kind() {
            return "gate";
        }

        @Override
        public SideEffects sideEffects() {
            return SideEffects.NONE;
        }

        @Override
        public String canonical() {
            return CanonicalJson.object(Map.of("gate",
                                               CanonicalJson.object(Map.of())));
        }
    }

    /// `mutate` (M3): parsed so submit can refuse it as `REFUSED(unsupported)`.
    record Unsupported(String kind) implements Recipe {
        /// Refused at submit and never planned.
        @Override
        public SideEffects sideEffects() {
            return SideEffects.NONE;
        }

        @Override
        public String canonical() {
            return CanonicalJson.object(Map.of(kind,
                                               CanonicalJson.object(Map.of())));
        }
    }

    /// An explicit rerun of job `of` (m2-design §5.2, ruling R-M2-4). `targets` are the parent's non-KNOWN failing
    /// classes, each with the module and runner the parent's manifest named, sorted by class; `original` is the parent's
    /// verdict and the target classes' failed rows, copied from its immutable record at submit. Both are part of
    /// [#canonical], so a repeat request deduplicates and the original cannot drift. A refused rerun request is recorded
    /// with neither ([Recipe#refusedRerun]); an accepted one always has both. `bound` is the rerun unit's wall-clock bound
    /// and its derivation (maintainer ruling on the rerun bound), decided at submit; it is NOT part of [#canonical]: it follows
    /// the history at submit time, so an equal request deduplicates whatever it would derive now.
    record Rerun(JobId of, List<Target> targets, Option<Original> original, Option<Bound> bound) implements Recipe {
        /// A selection that runs no class: with no positive pattern every class is chosen, and the negated `*` removes
        /// them all (`manifest.SurefirePatterns`, as surefire 3.5 reads `-Dtest`) [G]. Surefire's selection when only
        /// failsafe classes are rerun; the rerun step tolerates the empty run.
        public static final String NO_CLASS = "!*";

        public Rerun {
            targets = List.copyOf(targets);
        }

        /// This rerun with the unit bound decided at submit.
        public Rerun withBound(Bound decided) {
            return new Rerun(of, targets, original, some(decided));
        }

        /// The classes the unit runs, each once, sorted: the distinct outermost classes of the targets (a `@Nested`
        /// target runs its whole outermost class, [#moduleTest]). The rerun bound is derived over these.
        public List<String> outermostClasses() {
            return targets.stream()
                          .map(Target::outermost)
                          .distinct()
                          .sorted()
                          .toList();
        }

        /// The rerun unit's wall-clock bound and how it was derived, as the record prints it
        /// (`rerun-bound=48m (Σ 25.1m ×1.5 + install 5m + slack 5m)`). A derived value is whole minutes; an explicit
        /// `--unit-timeout` is kept as given.
        public record Bound(Duration value, String derivation) {
            public static Bound bound(Duration value, String derivation) {
                return new Bound(value, derivation);
            }

            /// `rerun-bound=<value> (<derivation>)`, the value in whole minutes (`48m`), else whole seconds (`90s`), else
            /// milliseconds (`90500ms`): never rounded, so the record states the unit's timeout exactly.
            public String annotation() {
                return "rerun-bound=" + shown() + " (" + derivation + ")";
            }

            private String shown() {
                return value.toMillis() % 60_000 == 0
                       ? value.toMinutes() + "m"
                       : subMinute();
            }

            private String subMinute() {
                return value.toMillis() % 1000 == 0
                       ? value.toSeconds() + "s"
                       : value.toMillis() + "ms";
            }
        }

        @Override
        public String kind() {
            return "rerun";
        }

        @Override
        public SideEffects sideEffects() {
            return SideEffects.NONE;
        }

        @Override
        public String canonical() {
            return CanonicalJson.object(Map.of("rerun", CanonicalJson.object(members())));
        }

        /// The ModuleTest the one unit (`UnitName.RERUN`, ruling R-M2-16) is shaped as: install the target modules, then
        /// select the target classes, the surefire ones through `-Dtest` and the failsafe ones through `-Dit.test`, each a
        /// comma list of distinct outermost classes (a `@Nested` class runs under its outermost class's selection),
        /// sorted. With failsafe classes only, surefire's selection is [#NO_CLASS], so neither plugin runs its whole suite.
        public Result<ModuleTest> moduleTest() {
            return Result.all(TestSelection.testSelection(selection(Runner.SUREFIRE).or(NO_CLASS)).map(Option::some),
                              Values.optional(selection(Runner.FAILSAFE),
                                              TestSelection::testSelection))
                         .map((test, itTest) -> new ModuleTest(modules(),
                                                               test,
                                                               itTest));
        }

        private List<ModulePath> modules() {
            return targets.stream()
                          .map(Target::module)
                          .distinct()
                          .sorted(Comparator.comparing(ModulePath::value))
                          .toList();
        }

        /// The comma list of `runner`'s outermost classes, sorted; none when no target has that runner.
        private Option<String> selection(Runner runner) {
            return Option.some(outermostOf(runner))
                         .filter(classes -> !classes.isEmpty())
                         .map(Rerun::commaList);
        }

        private static String commaList(Collection<String> classes) {
            return String.join(",", classes);
        }

        private SortedSet<String> outermostOf(Runner runner) {
            return targets.stream()
                          .filter(target -> target.runner() == runner)
                          .map(Target::outermost)
                          .collect(Collectors.toCollection(TreeSet::new));
        }

        private Map<String, String> members() {
            return Stream.of(Stream.of(Map.entry("of",
                                                 Long.toString(of.value())),
                                       Map.entry("classes",
                                                 canonicalArray(targets.stream().map(Target::canonical)))),
                             original.map(present -> Map.entry("original",
                                                               present.canonical())).stream())
                         .flatMap(Function.identity())
                         .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        private static String canonicalArray(Stream<String> rendered) {
            return rendered.collect(Collectors.joining(",", "[", "]"));
        }

        /// A class to rerun: its report classname, which must be a binary class name `-Dtest`/`-Dit.test` can select
        /// (a `@DisplayName` classname cannot be one), the module's workspace-relative path and the plugin.
        public record Target(String className, ModulePath module, Runner runner) {
            private static final Pattern CLASS_NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");

            /// A target as stored: the runner by its wire name.
            public static Result<Target> target(String className, String module, String runner) {
                return runnerOf(runner).flatMap(parsed -> target(className, module, parsed));
            }

            /// A target from a manifest entry.
            public static Result<Target> target(String className, String module, Runner runner) {
                return Result.all(Values.matching(className, CLASS_NAME, "rerun class"),
                                  ModulePath.modulePath(module))
                             .map((name, path) -> new Target(name, path, runner));
            }

            /// Whether `className` is a binary class name, which `-Dtest` can select.
            public static boolean selectable(String className) {
                return CLASS_NAME.matcher(className).matches();
            }

            /// `surefire` or `failsafe`, as the manifest's wire form names the runner.
            public String runnerName() {
                return runner.name()
                             .toLowerCase(Locale.ROOT);
            }

            /// A `@Nested` class (`Outer$Inner`) runs under its outermost class's selection (m2-design §5.2).
            String outermost() {
                return className.replaceFirst("\\$.*", "");
            }

            String canonical() {
                return CanonicalJson.object(Map.of("class",
                                                   CanonicalJson.string(className),
                                                   "module",
                                                   CanonicalJson.string(module.value()),
                                                   "runner",
                                                   CanonicalJson.string(runnerName())));
            }

            private static Result<Runner> runnerOf(String raw) {
                return switch (option(raw).or("")) {
                    case "surefire" -> success(Runner.SUREFIRE);
                    case "failsafe" -> success(Runner.FAILSAFE);
                    default -> invalid("rerun runner").apply(raw).result();
                };
            }
        }

        /// The parent's outcome as its record had it: its verdict and reason, and its failed rows of the target classes
        /// with their KNOWN marks.
        public record Original(JobId job, String verdict, Option<String> reason, List<Failure> failed) {
            public Original {
                failed = List.copyOf(failed);
            }

            public static Original original(JobId job, String verdict, Option<String> reason, List<Failure> failed) {
                return new Original(job, verdict, reason, failed);
            }

            String canonical() {
                return CanonicalJson.object(present(Stream.of(Map.entry("job",
                                                                        Long.toString(job.value())),
                                                              Map.entry("verdict", CanonicalJson.string(verdict)),
                                                              Map.entry("failed",
                                                                        canonicalArray(failed.stream()
                                                                                             .map(Failure::canonical)))),
                                                    reason.map(present -> Map.entry("reason",
                                                                                    CanonicalJson.string(present)))));
            }
        }

        /// One failed row of the parent: class, name and its KNOWN mark (`#<issue>`) when it had one.
        public record Failure(String className, String name, Option<String> known) {
            public static Failure failure(String className, String name, Option<String> known) {
                return new Failure(className, name, known);
            }

            String canonical() {
                return CanonicalJson.object(present(Stream.of(Map.entry("class", CanonicalJson.string(className)),
                                                              Map.entry("name", CanonicalJson.string(name))),
                                                    known.map(present -> Map.entry("known",
                                                                                   CanonicalJson.string(present)))));
            }
        }

        /// The always-present members and the one optional member when present (absent is omitted, never `null`).
        private static Map<String, String> present(Stream<Map.Entry<String, String>> always,
                                                   Option<Map.Entry<String, String>> optional) {
            return Stream.concat(always,
                                 optional.stream())
                         .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    static Result<Recipe> mvn(List<String> rawArgs) {
        return nonEmpty(rawArgs, "mvn args").flatMap(Recipe::mvnArgs)
                       .map(Mvn::new);
    }

    static Result<Recipe> moduleTest(List<String> rawPl, Option<String> rawTest, Option<String> rawItTest) {
        return Result.all(nonEmpty(rawPl, "pl").flatMap(Recipe::modulePaths),
                          Values.optional(rawTest, TestSelection::testSelection),
                          Values.optional(rawItTest, TestSelection::testSelection))
                     .map(ModuleTest::new);
    }

    static Recipe gate() {
        return new Gate();
    }

    static Recipe unsupported(String kind) {
        return new Unsupported(kind);
    }

    /// An accepted rerun: its targets (sorted by class here) and the parent's original outcome.
    static Rerun rerun(JobId of, List<Rerun.Target> targets, Rerun.Original original) {
        return new Rerun(of,
                         targets.stream().sorted(Comparator.comparing(Rerun.Target::className)).toList(),
                         some(original),
                         none());
    }

    /// A rerun request as a refused job records it: the parent only.
    static Rerun refusedRerun(JobId of) {
        return new Rerun(of, List.of(), none(), none());
    }

    /// A rerun as stored (`jobs.recipe`, the result record): its targets in their stored order; a refused one has none,
    /// and one stored before the derived bound has no bound.
    static Rerun storedRerun(JobId of,
                             List<Rerun.Target> targets,
                             Option<Rerun.Original> original,
                             Option<Rerun.Bound> bound) {
        return new Rerun(of, targets, original, bound);
    }

    private static Result<List<MvnArg>> mvnArgs(List<String> raw) {
        return Result.allOf(raw.stream().map(MvnArg::mvnArg).toList());
    }

    private static Result<List<ModulePath>> modulePaths(List<String> raw) {
        return Result.allOf(raw.stream().map(ModulePath::modulePath).toList());
    }

    private static Result<List<String>> nonEmpty(List<String> raw, String field) {
        return Verify.ensure(option(raw).or(List.of()),
                             Predicate.not(List::isEmpty),
                             invalid(field));
    }
}
