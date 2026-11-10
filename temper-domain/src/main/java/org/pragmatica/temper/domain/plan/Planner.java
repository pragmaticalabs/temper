package org.pragmatica.temper.domain.plan;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.model.UnitTimeout;
import org.pragmatica.temper.domain.mvn.MvnStep;
import org.pragmatica.temper.domain.mvn.ReactorModules;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Turns a recipe into units (m0-design §3.1, m1-design §1.1). Pure.
///
/// | Recipe | Unit | Steps | Manifest |
/// |---|---|---|---|
/// | `Mvn` | `main`, `MVN` | the submitted args | none (Q-M1-8) |
/// | `ModuleTest` | `main`, `MODULE_TEST` | `-pl … -am install -DskipTests`, then the selection | from the selection step |
/// | `Gate` | `gate`, `GATE_UNSHARDED`, [PlanDefaults#gateMemory] | `clean install` | from that step |
/// | `Rerun` | `rerun`, `RERUN` | as `ModuleTest` over the target modules and classes, plus [#RERUN_SELECTION] (m2-design §5.2) | from the selection step |
///
/// `reactor` is the reactor read from the tree at submit (m1-design §1.6); none keeps the conservative M0
/// port-group judgement.
public sealed interface Planner {
    /// Placeholder for the unit workspace. The worker substitutes the absolute workspace path in the planner's
    /// constant tokens only ([#FORCED_REPO_LOCAL] and [#effectivePomOutput]); it is never request data.
    String WS_PLACEHOLDER = "${ws}";
    /// The planner's forced Maven repository: always the LAST element of every planned step. R-M2 refuses
    /// any user-supplied `maven.repo.local`, and exempts only this exact trailing token.
    String FORCED_REPO_LOCAL = "-Dmaven.repo.local=" + WS_PLACEHOLDER + "/.m2-local";
    /// The effective-POM output token without its unit suffix; see [#effectivePomOutput].
    String EFFECTIVE_POM_OUTPUT = "-Doutput=" + WS_PLACEHOLDER + "/.home/.temper/effective-pom-";

    /// A rerun's selection step (ruling R-M2-16) runs one `-Dtest`/`-Dit.test` over every target module, so a module
    /// without one of the selected classes must not fail the run (surefire and failsafe each fail on "no tests matching"
    /// by default). It also ignores test failures [G]: every class's own rows decide its outcome, and a red class must not
    /// stop the build before another target class runs (a later module, or failsafe after surefire); the job's verdict
    /// still reads the failed rows (`FAILED(tests)`).
    List<String> RERUN_SELECTION = List.of("-Dsurefire.failIfNoSpecifiedTests=false",
                                           "-Dit.failIfNoSpecifiedTests=false",
                                           "-Dmaven.test.failure.ignore=true");

    static Result<List<UnitSpec>> plan(Recipe recipe,
                                       RecipeOptions options,
                                       PlanDefaults defaults,
                                       Option<ReactorModules> reactor) {
        return switch (recipe) {
            case Recipe.Mvn mvn -> success(List.of(unit(mvnShape(mvn, defaults),
                                                        mvn.sideEffects(),
                                                        options,
                                                        defaults,
                                                        reactor)));
            case Recipe.ModuleTest moduleTest -> success(List.of(unit(moduleTestShape(moduleTest, defaults),
                                                                      moduleTest.sideEffects(),
                                                                      options,
                                                                      defaults,
                                                                      reactor)));
            case Recipe.Gate gate -> success(List.of(unit(gateShape(defaults),
                                                          gate.sideEffects(),
                                                          options,
                                                          defaults,
                                                          reactor)));
            case Recipe.Rerun rerun -> rerunUnits(rerun, options, defaults, reactor);
            case Recipe.Unsupported unsupported -> Refusal.refusal(RefusalCode.UNSUPPORTED,
                                                                   "recipe " + unsupported.kind() + " is not supported yet").result();
        };
    }

    /// The `-Doutput=` token of `unit`'s effective-POM step: `${ws}/.home/.temper/effective-pom-<unit>.xml`
    /// (m1-design §2.2). Its only variable part is the unit name, which the planner itself chose.
    static String effectivePomOutput(UnitName unit) {
        return EFFECTIVE_POM_OUTPUT + unit.value() + ".xml";
    }

    private static UnitSpec unit(UnitShape shape,
                                 SideEffects sideEffects,
                                 RecipeOptions options,
                                 PlanDefaults defaults,
                                 Option<ReactorModules> reactor) {
        return unit(shape,
                    sideEffects,
                    Timeouts.effective(shape.kind(), options.timeout()),
                    defaults,
                    reactor);
    }

    /// The recipe's `sideEffects` is copied onto the unit as declared (m4-design §5.1).
    private static UnitSpec unit(UnitShape shape,
                                 SideEffects sideEffects,
                                 Duration timeout,
                                 PlanDefaults defaults,
                                 Option<ReactorModules> reactor) {
        return UnitSpec.unitSpec(shape.name(),
                                 shape.kind(),
                                 shape.steps(),
                                 TestRequest.requested(shape.steps()),
                                 timeout,
                                 shape.memory(),
                                 PortGroupRule.needs(shape.steps(), defaults.portGroupModules(), reactor),
                                 shape.manifest(),
                                 sideEffects);
    }

    private static UnitShape mvnShape(Recipe.Mvn mvn, PlanDefaults defaults) {
        return UnitShape.unitShape(UnitName.MAIN,
                                   UnitKind.MVN,
                                   List.of(step(mvn.values())),
                                   defaults.memory(),
                                   none());
    }

    private static UnitShape moduleTestShape(Recipe.ModuleTest recipe, PlanDefaults defaults) {
        return moduleTestShape(UnitName.MAIN, UnitKind.MODULE_TEST, recipe, List.of(), defaults);
    }

    /// `extraSelection` follows the selection step's own arguments (a rerun's tolerance of unmatched modules).
    private static UnitShape moduleTestShape(UnitName name,
                                             UnitKind kind,
                                             Recipe.ModuleTest recipe,
                                             List<String> extraSelection,
                                             PlanDefaults defaults) {
        var selection = step(Stream.concat(selectionArgs(recipe).stream(), extraSelection.stream()).toList());

        return UnitShape.unitShape(name,
                                   kind,
                                   List.of(step(List.of("-pl", recipe.projectList(), "-am", "install", "-DskipTests")),
                                           selection),
                                   defaults.memory(),
                                   manifestSpec(name, selection));
    }

    /// The rerun's one `RERUN` unit (`UnitName.RERUN`, ruling R-M2-16), a ModuleTest-shaped plan in the rerun job's own
    /// checkout, its selection step followed by [#RERUN_SELECTION]. A rerun with no class (only a refused request is
    /// recorded so) plans nothing to run, so it is refused here too.
    private static Result<List<UnitSpec>> rerunUnits(Recipe.Rerun rerun,
                                                     RecipeOptions options,
                                                     PlanDefaults defaults,
                                                     Option<ReactorModules> reactor) {
        return rerun.targets()
                    .isEmpty()
               ? Refusal.refusal(RefusalCode.RERUN, "a rerun names no class").result()
               : rerunUnit(rerun, options, defaults, reactor).map(Planner::single);
    }

    private static List<UnitSpec> single(UnitSpec unit) {
        return List.of(unit);
    }

    private static Result<UnitSpec> rerunUnit(Recipe.Rerun rerun,
                                              RecipeOptions options,
                                              PlanDefaults defaults,
                                              Option<ReactorModules> reactor) {
        return rerun.moduleTest()
                    .map(recipe -> unit(moduleTestShape(UnitName.RERUN,
                                                        UnitKind.RERUN,
                                                        recipe,
                                                        RERUN_SELECTION,
                                                        defaults),
                                        rerun.sideEffects(),
                                        rerunTimeout(rerun, options),
                                        defaults,
                                        reactor));
    }

    /// An explicit `timeout` wins; otherwise the bound derived at submit; a rerun stored without one keeps the RERUN
    /// kind's default.
    private static Duration rerunTimeout(Recipe.Rerun rerun, RecipeOptions options) {
        return options.timeout()
                      .map(UnitTimeout::value)
                      .orElse(() -> boundOf(rerun))
                      .or(() -> Timeouts.defaultFor(UnitKind.RERUN));
    }

    private static Option<Duration> boundOf(Recipe.Rerun rerun) {
        return rerun.bound()
                    .map(Recipe.Rerun.Bound::value);
    }

    private static UnitShape gateShape(PlanDefaults defaults) {
        var build = step(List.of("clean", "install"));

        return UnitShape.unitShape(UnitName.GATE,
                                   UnitKind.GATE_UNSHARDED,
                                   List.of(build),
                                   defaults.gateMemory(),
                                   manifestSpec(UnitName.GATE, build));
    }

    private static List<String> selectionArgs(Recipe.ModuleTest recipe) {
        return Stream.of(Stream.of("-pl",
                                   recipe.projectList()),
                         phaseArgs(recipe),
                         recipe.test().map(test -> "-Dtest=" + test.value()).stream())
                     .flatMap(Function.identity())
                     .toList();
    }

    /// `verify -Dit.test=<itTest>` when an IT selection is given (Q-5: literal), otherwise `test`.
    private static Stream<String> phaseArgs(Recipe.ModuleTest recipe) {
        return recipe.itTest()
                     .map(itTest -> Stream.of("verify",
                                              "-Dit.test=" + itTest.value()))
                     .or(() -> Stream.of("test"));
    }

    private static List<String> step(List<String> args) {
        return Stream.of(Stream.of("mvn", "-B"),
                         args.stream(),
                         Stream.of(FORCED_REPO_LOCAL))
                     .flatMap(part -> part)
                     .toList();
    }

    /// The manifest of a unit whose main step is `mainStep` (m1-design §2.2); none when the step reaches no
    /// test phase.
    private static Option<ManifestSpec> manifestSpec(UnitName unit, List<String> mainStep) {
        var step = MvnStep.mvnStep(mainStep);

        return step.reach()
                   .map(reach -> ManifestSpec.manifestSpec(pomArgv(unit, step),
                                                           reach,
                                                           userProperties(step),
                                                           step.property("test"),
                                                           step.property("it.test")));
    }

    /// `mvn -B <help:effective-pom> -Doutput=… <main step's reactor selection> <forced repo>`: the effective
    /// POM then holds exactly the main step's reactor. The main step's own forced token is dropped from the
    /// copied `-D`s, so the forced token stays the single, last one (R-M2).
    private static List<String> pomArgv(UnitName unit, MvnStep step) {
        return Stream.of(Stream.of("mvn",
                                   "-B",
                                   ManifestSpec.EFFECTIVE_POM_GOAL,
                                   effectivePomOutput(unit)),
                         step.reactorSelectionArgs().stream().filter(arg -> !arg.equals(FORCED_REPO_LOCAL)),
                         Stream.of(FORCED_REPO_LOCAL))
                     .flatMap(part -> part)
                     .toList();
    }

    /// The main step's `-D` properties, the last occurrence of each key (as Maven applies them).
    private static Map<String, String> userProperties(MvnStep step) {
        return step.properties()
                   .stream()
                   .collect(Collectors.toUnmodifiableMap(MvnStep.Property::key,
                                                         MvnStep.Property::value,
                                                         (_, last) -> last));
    }

    record unused() implements Planner {}
}
