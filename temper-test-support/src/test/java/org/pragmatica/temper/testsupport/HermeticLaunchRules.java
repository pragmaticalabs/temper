package org.pragmatica.temper.testsupport;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.conditions.ArchConditions.be;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;


/// The architecture rule behind issue #121: no test class creates a `ProcessBuilder` or calls `Runtime.exec`; a launch goes
/// through `HermeticProcess`. Applied by each module to its own test classes (test classes of one module are not on another's
/// classpath), with `ArchTests.in(HermeticLaunchRules.class)`.
///
/// The exemption is the fixed list `MAY_INHERIT`, not the `@InheritsHostEnvironment` annotation (CTO ruling on #127): an
/// annotation any class can add would let a new class opt itself out of the guard. The annotation stays as documentation, and
/// `onlyTheAllowListCarriesTheAnnotation` fails on any other class that carries it. Adding a class here is a reviewed change.
///
/// What closes the gaps of #128:
/// - Scope: each module's `HermeticLaunchArchTest` imports its own whole test output (`OwnTestOutput`), so a test class in any
///   package is checked, and another module's test-jar classes are not.
/// - Coverage: `HermeticLaunchCoverageTest` fails when a reactor module with tests has neither the ArchTest nor a reasoned
///   exemption, when an ArchTest is anything but the canonical body (whole own output, every rule here, nothing disabled) or
///   its module lacks the ArchUnit engine, and when an exempt module starts naming a launch API.
/// - Reflection: the rules see calls and method references (`ProcessBuilder::new`, `runtime::exec`), and `noLaunchClassLiteral`
///   sees reflection that starts from a class literal. A launch class named by string (`Class.forName`,
///   `MethodHandles.Lookup.findClass`, a descriptor) is no bytecode reference, so `HermeticLaunchCoverageTest` also fails when
///   the contents of any string or text-block literal of a test source (escapes undecoded; comments are not scanned) contain
///   a launch class name in `.` or `/` form (`java.lang.ProcessBuilder`, `java/lang/ProcessBuilder`,
///   `Ljava/lang/ProcessBuilder;`).
///
/// Accepted gap: these launch forms pass every net:
/// - a launch class name assembled at run time or from pieces: concatenated or split literals, including compile-time
///   constants that javac folds into one (`"java.lang." + "ProcessBuilder"`), a name spelled with escapes, a computed name,
///   or one read from a file or resource;
/// - instance reflection that needs neither a class literal nor a name, e.g.
///   `Runtime.getRuntime().getClass().getMethod("exec", String[].class)`;
/// - an array class object, e.g. `ProcessBuilder[].class.getComponentType()`;
/// - an annotation class value, e.g. `@A(v = ProcessBuilder.class)`;
/// - a launch through a non-test helper outside the test output.
/// Reason: the CTO rated deliberate reflection low risk (the rule guards against accidents, not adversaries); bytecode rules
/// cannot see a run-time string value, and the text net reads one literal at a time.
public final class HermeticLaunchRules {
    private HermeticLaunchRules() {}

    /// The classes that build a raw `ProcessBuilder` on purpose: the helper itself, the shared environment contract (it
    /// poisons a builder's inherited environment), and the dist sandbox test (it poisons raw builders to show the sandbox
    /// strips them).
    public static final Set<String> MAY_INHERIT = Set.of("org.pragmatica.temper.testsupport.HermeticProcess",
                                                         "org.pragmatica.temper.testsupport.HermeticChildEnvironmentContract",
                                                         "org.pragmatica.temper.dist.SandboxEnvIT");

    private static final DescribedPredicate<JavaClass> ALLOW_LISTED = describe("on HermeticLaunchRules.MAY_INHERIT",
                                                                               javaClass -> MAY_INHERIT.contains(javaClass.getName()));

    /// Names, not class literals: this class is in the checked test output too, and `noLaunchClassLiteral` bans the literals.
    private static final String LANG = "java.lang.";
    private static final String PROCESS_BUILDER = LANG + "ProcessBuilder";
    private static final String RUNTIME = LANG + "Runtime";

    /// The classes behind a launch, as reflection would name them.
    static final Set<String> LAUNCH_CLASSES = Set.of(PROCESS_BUILDER, RUNTIME, LANG + "ProcessImpl");

    /// A call (`new ProcessBuilder(..)`) or a constructor reference (`ProcessBuilder::new`): both are accesses to the constructor.
    @ArchTest
    static final ArchRule noRawProcessBuilder = noClasses().that(not(ALLOW_LISTED))
                                                         .should()
                                                         .accessTargetWhere(describe("target is a ProcessBuilder constructor",
                                                                                     (JavaAccess<?> access) -> access.getTargetOwner()
                                                                                                                      .isAssignableTo(PROCESS_BUILDER)
                                                                                                             && access.getTarget()
                                                                                                                      .getName()
                                                                                                                      .equals(JavaConstructor.CONSTRUCTOR_NAME)))
                                                         .because("a test child process must start from HermeticProcess.builder or HermeticProcess.prepared");

    /// A call (`runtime.exec(..)`) or a method reference (`runtime::exec`).
    @ArchTest
    static final ArchRule noRuntimeExec = noClasses().that(not(ALLOW_LISTED))
                                                   .should()
                                                   .accessTargetWhere(describe("target is Runtime.exec",
                                                                               (JavaAccess<?> access) -> access.getTargetOwner()
                                                                                                                .isAssignableTo(RUNTIME)
                                                                                                       && access.getTarget()
                                                                                                                .getName()
                                                                                                                .equals("exec")))
                                                   .because("Runtime.exec inherits the whole environment; use HermeticProcess.builder");


    /// Reflection from a class literal (`ProcessBuilder.class.getConstructor(..)`, `lookup.findVirtual(Runtime.class, "exec", ..)`)
    /// is no call the two rules above can see; the class literal itself is in the bytecode, so it is banned outside the list.
    @ArchTest
    static final ArchRule noLaunchClassLiteral = classes().that(not(ALLOW_LISTED))
                                                        .should(new ArchCondition<JavaClass>("not reference a launch class object (ProcessBuilder.class, Runtime.class)") {
                                                            @Override
                                                            public void check(JavaClass javaClass, ConditionEvents events) {
                                                                javaClass.getReferencedClassObjects()
                                                                         .stream()
                                                                         .filter(reference -> LAUNCH_CLASSES.contains(reference.getRawType()
                                                                                                                               .getName()))
                                                                         .forEach(reference -> events.add(SimpleConditionEvent.violated(reference,
                                                                                                                                         reference.getRawType()
                                                                                                                                                  .getName() + " class object referenced "
                                                                                                                                         + reference.getSourceCodeLocation())));
                                                            }
                                                        })
                                                        .because("a launch class reached by reflection inherits the whole environment; use HermeticProcess.builder");

    /// Most modules have no annotated class, so an empty match is allowed here; the two rules above still fail on an empty import.
    @ArchTest
    static final ArchRule onlyTheAllowListCarriesTheAnnotation = classes().that()
                                                                        .areAnnotatedWith(InheritsHostEnvironment.class)
                                                                        .should(be(ALLOW_LISTED))
                                                                        .because("the exemption is the fixed list MAY_INHERIT; annotating a class does not exempt it")
                                                                        .allowEmptyShould(true);
}
