package org.pragmatica.temper.domain.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// What JUnit sees in a compiled class (m1-design §2.4 rules 4-6): its effective test methods with their
/// effective tags, its class-level tags, and the annotation types the analysis could not resolve.
///
/// - **Jupiter test annotations** are `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory` and
///   `@TestTemplate`, used directly or through an annotation type in the [ClassIndex] that carries one
///   (meta-annotation, any depth).
/// - **Effective Jupiter test methods** (Q-M1-6, "effectively declares"): the class's own methods, then its
///   superclasses', then its interfaces' default methods level by level, the most specific declaration of
///   each name-and-descriptor winning, so an override without a test annotation is not a test. `static` and
///   `private` methods are never tests and never override one. A method is a test only when its result type
///   suits an annotation it carries (Jupiter 5.13 `IsTestMethod`, `IsTestTemplateMethod`,
///   `IsTestFactoryMethod`): `void` for `@Test`, `@TestTemplate` and the templates `@ParameterizedTest` and
///   `@RepeatedTest`; a dynamic-node container for `@TestFactory` ([TestFactoryResults]). Jupiter skips any
///   other with a discovery warning, so it makes no class a candidate.
/// - **JUnit 3 and JUnit 4 are not modelled** (ruling R-11, Jupiter only): [#legacyJunit] names a class whose
///   superclass chain reaches `junit.framework.TestCase`, or that carries an `org.junit.Test` method or `@RunWith`
///   on its chain, and the rules refuse such a module ([ManifestError.LegacyJunit]) instead of guessing. The R-11
///   limit (m1-design §14): the chain is followed only through the [ClassIndex], the classes compiled in the
///   reactor. A superclass from a jar or test-jar that reaches `TestCase` or carries JUnit 4 annotations is not
///   followed, and a JUnit 3 `suite()` class is not detected; the manifest is then Computed without such a class.
/// - **Optional methods**: a test method only `@TestFactory`/`@TestTemplate` makes may produce no test at all
///   (an empty dynamic stream), so it reports nothing. Such a method is [TestMethod#optional].
/// - **Class tags** ([#classTags]): `@Tag`/`@Tags` on the class, on its superclasses and interfaces (JUnit
///   searches the whole hierarchy), and on the annotation types used there. The tags a `@Nested` class takes
///   from the test instances enclosing it depend on the class JUnit discovers it through, so the rules add them
///   per discovery ([ManifestRules]).
/// - **Unresolved**: an annotation type neither in the index nor a JUnit (`org.junit.`) or JDK (`java.`)
///   type, met on the class, a method, or a meta-annotation of either.
public sealed interface TestClasses {
    /// Type namespaces whose members are known not to be composed test annotations.
    List<String> KNOWN_NAMESPACES = List.of("org.junit.", "junit.", "java.", "javax.", "jdk.", "kotlin.");
    /// The JUnit 3 base class: a class whose superclass chain reaches it is refused (R-11).
    String LEGACY_TEST_CASE = "junit.framework.TestCase";
    /// The JUnit 4 test annotation and runner annotation: a class whose chain carries either is refused (R-11).
    Set<String> LEGACY_ANNOTATIONS = Set.of("org.junit.Test", "org.junit.runner.RunWith");

    /// The field descriptor of a `void` result.
    String VOID = "V";

    /// A Jupiter test annotation type: whether its method always reports at least one test (a failure, at
    /// worst), and which result types Jupiter accepts for it (`@ParameterizedTest` and `@RepeatedTest` are
    /// test templates).
    enum JupiterKind {
        TEST("org.junit.jupiter.api.Test", true),
        REPEATED("org.junit.jupiter.api.RepeatedTest", true),
        PARAMETERIZED("org.junit.jupiter.params.ParameterizedTest", true),
        TEMPLATE("org.junit.jupiter.api.TestTemplate", false),
        FACTORY("org.junit.jupiter.api.TestFactory", false);
        private final String annotation;
        private final boolean reporting;
        JupiterKind(String annotation, boolean reporting) {
            this.annotation = annotation;
            this.reporting = reporting;
        }
        public String annotation() {
            return annotation;
        }
        public boolean reporting() {
            return reporting;
        }
        /// `void` for a test or a template; a dynamic-node container for a factory.
        public boolean accepts(MethodInfo method) {
            return this == FACTORY
                   ? TestFactoryResults.isCompatible(method)
                   : method.resultDescriptor()
                           .equals(VOID);
        }
    }

    /// One test method and its effective tags (class tags plus the method's own); and whether it may report no
    /// test at all (`optional`).
    record TestMethod(String name, Set<String> tags, boolean optional) {
        public TestMethod {
            tags = Set.copyOf(tags);
        }

        static TestMethod testMethod(String name, Set<String> tags, boolean optional) {
            return new TestMethod(name, tags, optional);
        }
    }

    /// The Jupiter test methods of `type`.
    static List<TestMethod> testMethods(ClassInfo type, ClassIndex index) {
        return jupiterTests(type, index).toList();
    }

    /// Whether `type` is a JUnit 3 or JUnit 4 test class (R-11): its superclass chain reaches
    /// `junit.framework.TestCase` (in the index or not), or a class on the chain carries `@RunWith` or a method
    /// annotated `org.junit.Test`. Only superclasses in the index are followed (the R-11 limit, see the type doc).
    static boolean legacyJunit(ClassInfo type, ClassIndex index) {
        return reachesTestCase(type, index, Set.of()) || superclassChain(type, index, Set.of()).anyMatch(TestClasses::carriesLegacyAnnotation);
    }

    private static boolean carriesLegacyAnnotation(ClassInfo type) {
        return hasLegacyAnnotation(type.annotations()) || type.methods()
                                                              .stream()
                                                              .anyMatch(method -> hasLegacyAnnotation(method.annotations()));
    }

    private static boolean hasLegacyAnnotation(Set<String> annotations) {
        return annotations.stream()
                          .anyMatch(LEGACY_ANNOTATIONS::contains);
    }

    /// The superclass chain meets `junit.framework.TestCase` by name (a reactor may compile its own copy, and the
    /// chain usually leaves the index there); a cycle never does.
    private static boolean reachesTestCase(ClassInfo type, ClassIndex index, Set<String> seen) {
        var next = with(seen, type.binaryName());

        return type.superName()
                   .filter(name -> isTestCaseAt(name, index, next))
                   .isPresent();
    }

    private static boolean isTestCaseAt(String name, ClassIndex index, Set<String> seen) {
        return name.equals(LEGACY_TEST_CASE) || unseen(name, index, seen).filter(superclass -> reachesTestCase(superclass,
                                                                                                               index,
                                                                                                               seen))
                                                      .isPresent();
    }

    /// The class-level tags of `type`: on it, its supertypes and the annotation types used there.
    static Set<String> classTags(ClassInfo type, ClassIndex index) {
        return tagSources(type, index, Set.of()).collect(Collectors.toUnmodifiableSet());
    }

    /// The annotation types met on `type` and its methods that the index cannot resolve.
    static Set<String> unresolvedAnnotations(ClassInfo type, ClassIndex index) {
        return Stream.concat(type.annotations().stream(),
                             type.methods().stream().flatMap(method -> method.annotations()
                                                                             .stream()))
                     .flatMap(annotation -> unresolvedFrom(annotation,
                                                           index,
                                                           Set.of()))
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static Stream<TestMethod> jupiterTests(ClassInfo type, ClassIndex index) {
        var classTags = classTags(type, index);

        return effectiveMethods(type, index).stream()
                               .flatMap(method -> jupiterTest(method, classTags, index).stream());
    }

    /// A test when some test annotation the method carries accepts its result type; optional unless one of
    /// those always reports.
    private static Option<TestMethod> jupiterTest(MethodInfo method, Set<String> classTags, ClassIndex index) {
        var kinds = acceptedKinds(method, index);

        return kinds.isEmpty()
               ? none()
               : some(TestMethod.testMethod(method.name(),
                                            union(classTags, methodTags(method, index)),
                                            kinds.stream().noneMatch(JupiterKind::reporting)));
    }

    private static List<JupiterKind> acceptedKinds(MethodInfo method, ClassIndex index) {
        return carriedKinds(method, index).filter(kind -> kind.accepts(method))
                           .toList();
    }

    /// The kinds of the Jupiter test annotations `method` carries.
    private static Stream<JupiterKind> carriedKinds(MethodInfo method, ClassIndex index) {
        return Stream.of(JupiterKind.values()).filter(kind -> carries(method, kind.annotation(), index));
    }

    /// Whether `method` carries the annotation type `annotation`, directly or as a meta-annotation.
    private static boolean carries(MethodInfo method, String annotation, ClassIndex index) {
        return method.annotations()
                     .stream()
                     .anyMatch(used -> isAnnotationOf(Set.of(annotation),
                                                      used,
                                                      index,
                                                      Set.of()));
    }

    /// Whether `annotation` is one of `kinds`, directly or through its meta-annotations in the index.
    private static boolean isAnnotationOf(Set<String> kinds, String annotation, ClassIndex index, Set<String> seen) {
        return kinds.contains(annotation) || metaAnnotations(annotation, index, seen).anyMatch(meta -> isAnnotationOf(kinds,
                                                                                                                      meta,
                                                                                                                      index,
                                                                                                                      with(seen,
                                                                                                                           annotation)));
    }

    /// The annotations on the annotation type `annotation`, when the index holds it and it was not seen yet.
    private static Stream<String> metaAnnotations(String annotation, ClassIndex index, Set<String> seen) {
        return unseen(annotation, index, seen).stream()
                     .flatMap(type -> type.annotations()
                                          .stream());
    }

    private static Option<ClassInfo> unseen(String name, ClassIndex index, Set<String> seen) {
        return index.find(name)
                    .filter(_ -> !seen.contains(name));
    }

    /// The methods of `type`'s hierarchy, most derived first, one per name and descriptor.
    /// Static and private methods neither are tests nor override one, so they are dropped before the most
    /// derived declaration of each signature is chosen.
    private static List<MethodInfo> effectiveMethods(ClassInfo type, ClassIndex index) {
        return List.copyOf(overridable(type, index).collect(Collectors.toMap(MethodInfo::signature,
                                                                             Function.identity(),
                                                                             (derived, _) -> derived,
                                                                             LinkedHashMap::new))
                                      .values());
    }

    private static Stream<MethodInfo> overridable(ClassInfo type, ClassIndex index) {
        return hierarchy(type, index).flatMap(member -> member.methods()
                                                              .stream())
                        .filter(method -> !method.isStaticOrPrivate());
    }

    /// `type` and its superclass chain, then their interfaces level by level, most specific first: a class
    /// method beats any interface default, and a nearer interface's default beats a farther one's.
    private static Stream<ClassInfo> hierarchy(ClassInfo type, ClassIndex index) {
        var chain = superclassChain(type, index, Set.of()).toList();
        var chainNames = chain.stream().map(ClassInfo::binaryName).collect(Collectors.toUnmodifiableSet());

        return Stream.concat(chain.stream(), interfaceLevels(interfacesOf(chain), index, chainNames));
    }

    private static Stream<ClassInfo> superclassChain(ClassInfo type, ClassIndex index, Set<String> seen) {
        var next = with(seen, type.binaryName());

        return Stream.concat(Stream.of(type),
                             type.superName()
                                 .flatMap(name -> unseen(name, index, next))
                                 .stream()
                                 .flatMap(superclass -> superclassChain(superclass, index, next)));
    }

    /// One level of interfaces (in declaration order), then the level of their superinterfaces.
    private static Stream<ClassInfo> interfaceLevels(List<String> names, ClassIndex index, Set<String> seen) {
        var level = names.stream().distinct().flatMap(name -> unseen(name, index, seen).stream()).toList();
        var next = Stream.concat(seen.stream(),
                                 level.stream().map(ClassInfo::binaryName))
                         .collect(Collectors.toUnmodifiableSet());

        return level.isEmpty()
               ? Stream.empty()
               : Stream.concat(level.stream(), interfaceLevels(interfacesOf(level), index, next));
    }

    private static List<String> interfacesOf(List<ClassInfo> types) {
        return types.stream()
                    .flatMap(type -> type.interfaces()
                                         .stream())
                    .toList();
    }

    private static Stream<String> supertypes(ClassInfo type) {
        return Stream.concat(type.superName().stream(),
                             type.interfaces().stream());
    }

    /// Tags on `type` and its annotations, and on its supertypes.
    private static Stream<String> tagSources(ClassInfo type, ClassIndex index, Set<String> seen) {
        return Stream.concat(ownTags(type, index), inheritedTags(type, index, with(seen, type.binaryName())));
    }

    private static Stream<String> ownTags(ClassInfo type, ClassIndex index) {
        return Stream.concat(type.tags().stream(),
                             type.annotations().stream().flatMap(annotation -> metaTags(annotation, index, Set.of())));
    }

    private static Stream<String> inheritedTags(ClassInfo type, ClassIndex index, Set<String> seen) {
        return supertypes(type).flatMap(name -> unseen(name, index, seen).stream())
                         .flatMap(supertype -> tagSources(supertype, index, seen));
    }

    private static Set<String> methodTags(MethodInfo method, ClassIndex index) {
        return Stream.concat(method.tags().stream(),
                             method.annotations().stream().flatMap(annotation -> metaTags(annotation,
                                                                                          index,
                                                                                          Set.of())))
                     .collect(Collectors.toUnmodifiableSet());
    }

    /// The tags an annotation type in the index carries, directly or through its own annotations.
    private static Stream<String> metaTags(String annotation, ClassIndex index, Set<String> seen) {
        return unseen(annotation, index, seen).stream()
                     .flatMap(type -> annotationTags(type,
                                                     index,
                                                     with(seen, annotation)));
    }

    private static Stream<String> annotationTags(ClassInfo annotationType, ClassIndex index, Set<String> seen) {
        return Stream.concat(annotationType.tags().stream(),
                             annotationType.annotations().stream().flatMap(meta -> metaTags(meta, index, seen)));
    }

    private static Stream<String> unresolvedFrom(String annotation, ClassIndex index, Set<String> seen) {
        return isKnown(annotation) || seen.contains(annotation)
               ? Stream.empty()
               : index.find(annotation)
                      .map(type -> unresolvedMeta(type,
                                                  index,
                                                  with(seen, annotation)))
                      .or(() -> Stream.of(annotation));
    }

    private static Stream<String> unresolvedMeta(ClassInfo annotationType, ClassIndex index, Set<String> seen) {
        return annotationType.annotations()
                             .stream()
                             .flatMap(meta -> unresolvedFrom(meta, index, seen));
    }

    private static boolean isKnown(String name) {
        return KNOWN_NAMESPACES.stream().anyMatch(name::startsWith);
    }

    private static Set<String> with(Set<String> seen, String name) {
        return Stream.concat(seen.stream(),
                             Stream.of(name))
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        return Stream.concat(left.stream(),
                             right.stream())
                     .collect(Collectors.toUnmodifiableSet());
    }

    record unused() implements TestClasses {}
}
