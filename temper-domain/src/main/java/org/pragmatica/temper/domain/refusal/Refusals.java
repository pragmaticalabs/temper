package org.pragmatica.temper.domain.refusal;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Text;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.mvn.MvnStep;
import org.pragmatica.temper.domain.mvn.ReactorModules;
import org.pragmatica.temper.domain.mvn.SideEffectRule;
import org.pragmatica.temper.domain.plan.Planner;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Runner-level refusals (spec §2.5, m0-design §3.4). One named function per code, each `Result<Unit>`.
///
/// `checkSubmit` lists every submit-time check in one list, so a mutation probe that deletes one element
/// (or makes one function return `unitResult()`) turns exactly that code's test red (A1). The first failing
/// check, in list order, is the refusal.
///
/// M1 (m1-design §1.6): port-group units are no longer refused (bb-hold holds the group), so `port-group`
/// is not in the list; `RefusalCode.PORT_GROUP` stays for historic REFUSED rows. R-M2, R-SHELL and R-EFFECT also judge a
/// unit's effective-POM step; the data-driven rules judge the steps that run tests, with the tree's reactor.
public sealed interface Refusals {
    /// R-M2: settings files can redirect `localRepository` (Q-7).
    Pattern SETTINGS = Pattern.compile("^(-s|-gs|--settings|--global-settings)(=.*|.+)?$");

    /// R-SHELL: plugin goals that can run an arbitrary executable (Q-7).
    Pattern SHELL_GOAL = Pattern.compile("^(exec:(exec|java)|antrun:run|(.+:)?exec-maven-plugin(:[^:]+)?:(exec|java)|(.+:)?maven-antrun-plugin(:[^:]+)?:run)(@.+)?$");

    /// Composes every submit-time refusal: structural checks first, then the data-driven rules in row order.
    static Result<Unit> checkSubmit(SubmitShape shape, List<RefusalRule> rules, TreeFacts tree) {
        return Result.firstFailureOf(Stream.concat(Stream.of(env(shape),
                                                             m2(shape.units()),
                                                             shell(shape.units()),
                                                             effect(shape)),
                                                   rules.stream().map(rule -> rule(rule,
                                                                                   shape.units(),
                                                                                   tree)))
                                           .toList()).mapToUnit();
    }

    /// R-ENV: the container environment is an allow-list ([ContainerEnv]); a request `env` field is refused.
    static Result<Unit> env(SubmitShape shape) {
        return refuseIf(shape.envFieldPresent()
                        ? some("the container environment is a fixed allow-list; the env field is refused")
                        : none(),
                        RefusalCode.R_ENV);
    }

    /// R-M2: a user-supplied `maven.repo.local` or settings file is refused. The planner's forced token is
    /// exempt only as the LAST element of an argv, which is where the planner puts it. Judges every argv of a
    /// unit, the effective-POM step included.
    static Result<Unit> m2(List<UnitSpec> units) {
        return refuseIf(firstOffence(argvs(units), Refusals::m2Offence), RefusalCode.R_M2);
    }

    /// R-SHELL: every argv runs `mvn`, and no argument names a shell-capable plugin goal or `exec.executable`.
    /// Judges every argv of a unit, the effective-POM step included.
    static Result<Unit> shell(List<UnitSpec> units) {
        return refuseIf(firstOffence(argvs(units), Refusals::shellOffence), RefusalCode.R_SHELL);
    }

    /// R-EFFECT (m4-design §5.3, rulings R-M4-14, Q-M4-11, Q-M4-11b): every phase, goal and `-P` profile of every argv,
    /// and every `-D` property of the user's own arguments, is on [SideEffectRule]'s build-only allowlist (so no deploy,
    /// release or push). After R-SHELL in [#checkSubmit], so a shell goal is R-SHELL. Goals and profiles are judged on
    /// every argv of a unit, the planner's own included, so a plan that ever emitted `deploy` would be refused, not run.
    /// Properties are judged on [SubmitShape#userArgs] only, before the planner appends its own (`-Dmaven.repo.local`,
    /// `-Doutput`, the rerun's tolerance switches), which are not user input.
    static Result<Unit> effect(SubmitShape shape) {
        return refuseIf(firstOffence(argvs(shape.units()), Refusals::effectOffence).orElse(() -> userArgsOffence(shape.userArgs())),
                        RefusalCode.R_EFFECT);
    }

    /// A data-driven rule: R-1553 (`ExcludeTestClass`) or R-FAILSAFE (`ItTestRequired`), judged with the
    /// tree's reactor.
    static Result<Unit> rule(RefusalRule rule, List<UnitSpec> units, TreeFacts tree) {
        return switch (rule) {
            case RefusalRule.ExcludeTestClass exclude -> excludeTestClass(exclude, units, tree);
            case RefusalRule.ItTestRequired itTest -> itTestRequired(itTest, units, tree);
        };
    }

    /// R-FAILSAFE over the steps that run the build, with the tree's reactor.
    private static Result<Unit> itTestRequired(RefusalRule.ItTestRequired rule, List<UnitSpec> units, TreeFacts tree) {
        return refuseIf(firstOffence(steps(units), argv -> itTestOffence(rule, argv, tree.reactor())), rule.code());
    }

    /// R-DISK (worker, at unit start): free space on the docker root or the workspace filesystem is below the
    /// floor. The worker reports it as `VOID(disk)`.
    static Result<Unit> disk(long freeDockerRoot, long freeWorkspace, long floorBytes) {
        return refuseIf(freeDockerRoot < floorBytes || freeWorkspace < floorBytes
                        ? some("free space below the floor of " + floorBytes
                              + " bytes (docker root " + freeDockerRoot
                              + ", workspace " + freeWorkspace
                              + ")")
                        : none(),
                        RefusalCode.R_DISK);
    }

    private static Result<Unit> refuseIf(Option<String> offence, RefusalCode code) {
        return refuseIf(offence, code.wire());
    }

    private static Result<Unit> refuseIf(Option<String> offence, String code) {
        return offence.map(detail -> Refusal.refusal(code, detail).<Unit> result())
                      .or(Result::unitResult);
    }

    private static Option<String> firstOffence(Stream<List<String>> argvs, Fn1<Option<String>, List<String>> check) {
        return Option.from(argvs.map(check::apply).flatMap(Option::stream).findFirst());
    }

    /// The steps that run the build: what the data-driven rules judge.
    private static Stream<List<String>> steps(List<UnitSpec> units) {
        return units.stream()
                    .flatMap(unit -> unit.steps()
                                         .stream());
    }

    /// Every argv a unit runs, its effective-POM step included: what R-M2, R-SHELL and R-EFFECT judge.
    private static Stream<List<String>> argvs(List<UnitSpec> units) {
        return units.stream()
                    .flatMap(unit -> unit.argvs()
                                         .stream());
    }

    // ---- R-M2 ----------------------------------------------------------------------------------------------
    private static Option<String> m2Offence(List<String> step) {
        var argv = withoutForcedRepo(step);

        return repoLocalOffence(argv).orElse(() -> settingsOffence(argv));
    }

    /// Any `maven.repo.local*` system property, in every define form Maven accepts (read through [MvnStep]).
    private static Option<String> repoLocalOffence(List<String> argv) {
        return Option.from(MvnStep.mvnStep(argv)
                                  .properties()
                                  .stream()
                                  .filter(property -> property.key()
                                                              .startsWith("maven.repo.local"))
                                  .map(property -> "property '" + property.key()
                                                  + "' is refused: maven.repo.local is forced to the unit workspace")
                                  .findFirst());
    }

    private static Option<String> settingsOffence(List<String> argv) {
        return Option.from(argv.stream()
                               .skip(1)
                               .filter(arg -> SETTINGS.matcher(arg).matches())
                               .map(arg -> "argument '" + arg
                                          + "' is refused: a settings file can redirect the local repository")
                               .findFirst());
    }

    private static List<String> withoutForcedRepo(List<String> step) {
        return endsWithForcedRepo(step)
               ? step.subList(0, step.size() - 1)
               : step;
    }

    private static boolean endsWithForcedRepo(List<String> step) {
        return ! step.isEmpty() && step.getLast()
                                       .equals(Planner.FORCED_REPO_LOCAL);
    }

    // ---- R-SHELL -------------------------------------------------------------------------------------------
    private static Option<String> shellOffence(List<String> step) {
        return startsWithMvn(step)
               ? shellGoalOffence(step).orElse(() -> execExecutableOffence(step))
               : some("step executable must be mvn, got " + step);
    }

    private static boolean startsWithMvn(List<String> step) {
        return ! step.isEmpty() && step.getFirst()
                                       .equals("mvn");
    }

    private static Option<String> shellGoalOffence(List<String> step) {
        return Option.from(step.stream()
                               .skip(1)
                               .filter(arg -> SHELL_GOAL.matcher(arg).matches())
                               .map(arg -> "goal '" + arg + "' can run a shell")
                               .findFirst());
    }

    private static Option<String> execExecutableOffence(List<String> step) {
        return MvnStep.mvnStep(step)
                      .property("exec.executable")
                      .map(value -> "exec.executable=" + value + " can run a shell");
    }

    // ---- R-EFFECT ------------------------------------------------------------------------------------------
    private static Option<String> effectOffence(List<String> step) {
        return SideEffectRule.plannedStepOffence(MvnStep.mvnStep(step)).map(offence -> offence + " in step " + step);
    }

    private static Option<String> userArgsOffence(List<String> userArgs) {
        return SideEffectRule.offence(MvnStep.mvnStep(Stream.concat(Stream.of("mvn"), userArgs.stream()).toList())).map(offence -> offence
                                                                                                                                  + " in the submitted arguments " + userArgs);
    }

    // ---- R-1553 --------------------------------------------------------------------------------------------
    private static Result<Unit> excludeTestClass(RefusalRule.ExcludeTestClass rule,
                                                 List<UnitSpec> units,
                                                 TreeFacts tree) {
        return disabledPattern(rule).flatMap(disabled -> refuseIf(excludeOffence(rule, disabled, units, tree),
                                                                  rule.code()));
    }

    private static Option<String> excludeOffence(RefusalRule.ExcludeTestClass rule,
                                                 Pattern disabled,
                                                 List<UnitSpec> units,
                                                 TreeFacts tree) {
        return Option.from(enabledSources(rule, disabled, tree).flatMap(source -> runningSteps(rule,
                                                                                               units,
                                                                                               source,
                                                                                               tree.reactor()))
                                         .findFirst());
    }

    private static Stream<ClassSource> enabledSources(RefusalRule.ExcludeTestClass rule,
                                                      Pattern disabled,
                                                      TreeFacts tree) {
        return tree.sources(rule.simpleClassName())
                   .stream()
                   .filter(source -> !disabled.matcher(source.content())
                                              .find());
    }

    private static Stream<String> runningSteps(RefusalRule.ExcludeTestClass rule,
                                               List<UnitSpec> units,
                                               ClassSource source,
                                               Option<ReactorModules> reactor) {
        return steps(units).map(MvnStep::mvnStep)
                    .filter(step -> couldRunHere(rule, source, step, reactor))
                    .map(_ -> rule.simpleClassName()
                             + " (" + source.path()
                             + ") is not @Disabled(\"" + rule.disabledMarker()
                             + "\") and the unit selection could run it");
    }

    /// The step's reactor may hold the class's module (resolved reactor, m1-design §1.6) and its selection
    /// could run the class.
    private static boolean couldRunHere(RefusalRule.ExcludeTestClass rule,
                                        ClassSource source,
                                        MvnStep step,
                                        Option<ReactorModules> reactor) {
        return step.mayInclude(source.module(), reactor) && SelectionMatcher.couldRun(step,
                                                                                      rule.simpleClassName(),
                                                                                      rule.runner());
    }

    /// `@Disabled("<marker>")` on the class declaration itself (other annotations and modifiers may follow).
    private static Result<Pattern> disabledPattern(RefusalRule.ExcludeTestClass rule) {
        return Text.compilePattern("(?m)^\\s*@(?:[\\w.]+\\.)?Disabled\\(\\s*(?:value\\s*=\\s*)?\"" + Pattern.quote(rule.disabledMarker())
                                  + "\"\\s*\\)(?:\\s*@[\\w.]+(?:\\([^)]*\\))?)*\\s*"
                                  + "(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|strictfp)\\s+)*"
                                  + "class\\s+" + Pattern.quote(rule.simpleClassName())
                                  + "\\b");
    }

    // ---- R-FAILSAFE ----------------------------------------------------------------------------------------
    /// Literal spec §2.5 / m0-design §3.4 reading (kept by the m0 §12.5 ruling): a step that may build the
    /// module, with `-Dtest` and no `-Dit.test`, whatever the phase. "May build" is a `-pl`/`-f` selection of
    /// the module (M0) or, since Q-M1-5, a whole-reactor, `-am` or `-amd` step whose resolved reactor may hold
    /// it ([MvnStep#mayIncludeSegment]; an unknown reactor may hold any module).
    private static Option<String> itTestOffence(RefusalRule.ItTestRequired rule,
                                                List<String> argv,
                                                Option<ReactorModules> reactor) {
        return selectsWithoutItTest(MvnStep.mvnStep(argv), rule.moduleSegment(), reactor)
               ? some(rule.moduleSegment() + " may be built with -Dtest but no -Dit.test, which runs every IT")
               : none();
    }

    private static boolean selectsWithoutItTest(MvnStep step, String moduleSegment, Option<ReactorModules> reactor) {
        return step.mayIncludeSegment(moduleSegment, reactor)
               && step.property("test")
                      .isPresent()
               && step.property("it.test")
                      .isEmpty();
    }

    record unused() implements Refusals {}
}
