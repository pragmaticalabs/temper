package org.pragmatica.temper.domain.manifest;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.manifest.TestClasses.TestMethod;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The manifest rules of spec §5.2 and m1-design §2.4: which compiled test classes the reached executions
/// are expected to report. Pure.
///
/// A class of a selected module is an entry for a selection's runner when all of these hold:
/// - **Candidate (rule 4):** it is concrete and not an interface, and it has at least one effective test
///   method ([TestClasses]).
/// - **Kind:** not private; top-level, a static member class, or a `@Nested` inner class ([Nesting]).
/// - **Naming (rule 5):** a class is identified by its binary name (`Outer$Inner` for a `@Nested` class) and
///   matched by the path of each [Discovery]: its own path (when its enclosing classes are test classes JUnit
///   accepts), and, for a `@Nested` class, the paths of the classes JUnit discovers it from: its enclosing
///   class and every concrete class of the scanned module extending it, up the chain of `@Nested` classes (the
///   default `**/*$*` exclude drops every member class's own path). An outer class whose only tests are in
///   `@Nested` classes has no test method of its own, so it is not an entry. Superclasses are followed across
///   the reactor, so a `@Nested` class of another module that a subclass in this module reaches is expected in
///   this module (R-6); only its discoveries through this module's classes count.
/// - **Reported name (m1-design §2.4 rule 5; T1 2026-10-14 runs 1 and 2):** an
///   entry is the `<testcase classname>` the selection's own plugin writes, which depends on that plugin's effective
///   version ([ReportNaming], per module and per runner, so one reactor may mix both): surefire and failsafe 3.0.0 –
///   3.5.3 and 3.6.0 write the binary name; 3.5.4 – 3.5.6 write the class's own `@DisplayName` raw value when the
///   class itself carries one, else the binary name (their `RunListenerAdapter` reads the `@DisplayName` of the
///   test's nearest class container by reflection and the default `StatelessXmlReporter` writes it as the
///   classname). Under 3.5.4 – 3.5.6 it holds the same for top-level and `@Nested` classes at any depth, failsafe as
///   surefire, parameterized and factory tests; the value is untrimmed and unescaped (dots, `$`, spaces, unicode
///   kept), equal to the simple name or not; it is not inherited, not read through a meta-annotation, and
///   `@DisplayNameGeneration` never changes it. Any other version, or none, makes a non-skipped selection
///   [ManifestOutcome.Unsupported] ([ManifestError.UnsupportedPluginVersion]). Patterns match the binary path under
///   every version; the report file holding a case is not modelled (it differs by version too). Oracle:
///   `ManifestOracleIT` over the fixture's `display` (3.5.4), `display352` (3.5.2, JUnit 5.11.4, pragmatica `jbct`'s
///   shape) and `display353` (3.5.3) modules, and `DisplayIT`. An expected class whose reported name is a blank
///   `@DisplayName` (as JUnit judges it, empty after `trim()`, or as the protocol judges an entry name, all
///   `Character::isWhitespace`) makes the whole manifest [ManifestOutcome.Unsupported]
///   ([ManifestError.BlankDisplayName]). So do two runnable test classes of the selected modules that report under
///   one name ([ManifestError.SharedDisplayName]): rows cannot tell them apart.
/// - **Patterns (rule 2):** without `test`, the includes/excludes select the class; with `test`, it selects
///   at least one of the class's test methods ([SurefirePatterns]). When `test` names a method anywhere,
///   surefire filters every method by its own class's path alone (surefire 3.5.4 `TestMethodFilter`), whichever
///   discovery scanned the class, so `-Dtest=Outer#m` selects nothing in `Outer$Inner`, and neither does
///   `-Dtest=Outer,Other#m`.
/// - **Groups (rule 6):** some selected test method keeps its place: its effective tags miss
///   `excludedGroups` and, when `groups` is set, meet `groups`. A Jupiter method's effective tags include every
///   class-level tag and, for a `@Nested` class, those of the enclosing instances of the discovery that
///   selected it, so "a class-level tag is excluded" and "every test method is excluded" both follow from this
///   one per-method rule.
/// - **Jupiter only (R-11):** a selected module with a JUnit 3 or JUnit 4 test class ([TestClasses#legacyJunit])
///   makes the whole manifest [ManifestOutcome.Unsupported].
///
/// **Optional:** a class whose every kept test method is optional ([TestClasses.TestMethod#optional]) may
/// report nothing, so it is listed in [ManifestOutcome.Computed#optional] rather than in `entries`.
///
/// **notReached (rule 8):** the given modules without compiled tests, restricted to modules a non-skipped
/// selection covers (a skipped module that never compiled its tests was not expected to).
public sealed interface ManifestRules {
    /// The whole engine after parsing: resolves the selections of `modules` under `spec` and computes the
    /// manifest; unsupported configuration or plugin version, a JUnit 3 or JUnit 4 test class (R-11) and a blank
    /// reported `@DisplayName` (rule 5) become [ManifestOutcome.Unsupported] (Q-M1-7).
    static ManifestOutcome manifest(List<PomModule> modules,
                                    ManifestSpec spec,
                                    Map<String, List<ClassInfo>> testClassesByModule,
                                    ClassIndex index,
                                    Set<String> modulesWithTestSourcesButNoClasses) {
        // Not an absorption: Unsupported is the outcome the worker reports as ERROR(manifest: <reason>).
        return Selections.resolve(modules, spec).fold(ManifestOutcome.Unsupported::unsupported,
                                                      selections -> jupiterOnly(selections,
                                                                                testClassesByModule,
                                                                                index,
                                                                                modulesWithTestSourcesButNoClasses));
    }

    /// Unsupported when a selected module holds a JUnit 3 or JUnit 4 test class; computed otherwise ([#compute]).
    private static ManifestOutcome jupiterOnly(List<ModuleSelection> selections,
                                               Map<String, List<ClassInfo>> testClassesByModule,
                                               ClassIndex index,
                                               Set<String> modulesWithTestSourcesButNoClasses) {
        return legacyClass(selections, testClassesByModule, index).<ManifestOutcome> map(ManifestOutcome.Unsupported::unsupported)
                          .or(() -> compute(selections, testClassesByModule, index, modulesWithTestSourcesButNoClasses));
    }

    /// The first JUnit 3 or JUnit 4 test class of a module some non-skipped selection covers, by module and name.
    private static Option<ManifestError.LegacyJunit> legacyClass(List<ModuleSelection> selections,
                                                                 Map<String, List<ClassInfo>> testClassesByModule,
                                                                 ClassIndex index) {
        return Option.from(selections.stream()
                                     .filter(selection -> !selection.params()
                                                                    .skipped())
                                     .flatMap(selection -> legacyClassesOf(selection, testClassesByModule, index))
                                     .sorted(MODULE_CLASS_ORDER)
                                     .findFirst()).map(found -> ManifestError.LegacyJunit.FACTORY.apply(found.module(),
                                                                                                        found.className()));
    }

    /// A class of a module, by module path and binary name: a JUnit 3/4 class (R-11) or a blank `@DisplayName` (rule 5).
    record ModuleClass(String module, String className) {
        static ModuleClass moduleClass(String module, String className) {
            return new ModuleClass(module, className);
        }
    }

    Comparator<ModuleClass> MODULE_CLASS_ORDER = Comparator.comparing(ModuleClass::module).thenComparing(ModuleClass::className);

    private static Stream<ModuleClass> legacyClassesOf(ModuleSelection selection,
                                                       Map<String, List<ClassInfo>> testClassesByModule,
                                                       ClassIndex index) {
        return classesOf(selection, testClassesByModule).filter(type -> TestClasses.legacyJunit(type, index))
                        .map(type -> ModuleClass.moduleClass(modulePath(selection),
                                                             type.binaryName()));
    }

    /// The expected classes of `selections`. `testClassesByModule` holds each module's `test-classes`, keyed
    /// by [PomModule#path]; `modulesWithTestSourcesButNoClasses` are the modules the build never reached.
    /// Unsupported when an expected class's own `@DisplayName` is blank, or when two test classes share a reported
    /// name (rule 5).
    private static ManifestOutcome compute(List<ModuleSelection> selections,
                                           Map<String, List<ClassInfo>> testClassesByModule,
                                           ClassIndex index,
                                           Set<String> modulesWithTestSourcesButNoClasses) {
        var active = selections.stream().filter(selection -> !selection.params()
                                                                       .skipped()).toList();
        var expectations = expectations(active, testClassesByModule, index);

        return blankDisplayName(expectations).<ManifestOutcome> map(ManifestOutcome.Unsupported::unsupported)
                               .orElse(() -> sharedDisplayName(active, testClassesByModule, index))
                               .or(() -> computed(active,
                                                  expectations,
                                                  testClassesByModule,
                                                  index,
                                                  modulesWithTestSourcesButNoClasses));
    }

    /// The first expected class by module and binary name whose reported name, its own `@DisplayName`, is blank.
    private static Option<ManifestError.BlankDisplayName> blankDisplayName(Set<Expectation> expectations) {
        return Option.from(expectations.stream()
                                       .filter(ManifestRules::hasBlankReportedName)
                                       .map(expectation -> ModuleClass.moduleClass(expectation.entry().module(),
                                                                                   expectation.type().binaryName()))
                                       .sorted(MODULE_CLASS_ORDER)
                                       .findFirst()).map(found -> ManifestError.BlankDisplayName.FACTORY.apply(found.module(),
                                                                                                               found.className()));
    }

    /// Blank as either side judges it, so a name the protocol would refuse never reaches the wire: JUnit 5.13
    /// `StringUtils.isBlank` (empty after `trim()`, which strips every char up to U+0020) or the protocol's
    /// `Verify.Is::present` on an entry name (every char `Character::isWhitespace`, which also covers U+2003, U+3000,
    /// U+2028). A name only the protocol judges blank (an em space) is a valid JUnit name surefire would report; it
    /// is Unsupported here rather than computed, because the controller refuses such an entry and drops the whole
    /// manifest (`computed: false`), losing coverage silently (verification F-A, 2026-10-14).
    ///
    /// Judged on the reported name, so only a plugin version that reports the display name refuses it; under
    /// [ReportNaming#BINARY_NAME] the class reports its binary name and a blank `@DisplayName` is harmless.
    private static boolean hasBlankReportedName(Expectation expectation) {
        return isBlankName(expectation.entry().className());
    }

    private static boolean isBlankName(String name) {
        return name.trim()
                   .isEmpty() || Verify.Is.blank(name);
    }

    /// Unsupported for the first reported name, by name, that two or more runnable test classes of the active modules
    /// share, naming their binary names. Judged over every such class, not only the expected ones: a shard unit whose
    /// `test` selects one of them would otherwise compute a manifest that its sibling unit's rows contradict
    /// (`ERROR(plan)` for two distinct classes). Two classes with one binary name (one class reached twice) are not a
    /// collision.
    private static Option<ManifestOutcome> sharedDisplayName(List<ModuleSelection> active,
                                                             Map<String, List<ClassInfo>> testClassesByModule,
                                                             ClassIndex index) {
        return Option.from(classesByReportedName(active, testClassesByModule, index).entrySet()
                                                .stream()
                                                .filter(ManifestRules::isShared)
                                                .findFirst()).map(ManifestRules::sharedDisplayName);
    }

    private static boolean isShared(Map.Entry<String, Set<String>> namedClasses) {
        var classNames = namedClasses.getValue();

        return classNames.size() > 1;
    }

    private static ManifestOutcome sharedDisplayName(Map.Entry<String, Set<String>> namedClasses) {
        var classNames = String.join(", ", namedClasses.getValue());

        return ManifestOutcome.Unsupported.unsupported(ManifestError.SharedDisplayName.FACTORY.apply(namedClasses.getKey(),
                                                                                                     classNames));
    }

    /// Reported name → the binary names of the runnable classes with a test method reporting under it, both sorted.
    private static Map<String, Set<String>> classesByReportedName(List<ModuleSelection> active,
                                                                  Map<String, List<ClassInfo>> testClassesByModule,
                                                                  ClassIndex index) {
        return active.stream()
                     .flatMap(selection -> reportedClasses(selection, testClassesByModule, index))
                     .collect(Collectors.groupingBy(Map.Entry::getKey,
                                                    TreeMap::new,
                                                    Collectors.mapping(Map.Entry::getValue,
                                                                       Collectors.toCollection(TreeSet::new))));
    }

    /// Reported name → binary name of each runnable class of `selection` with a test method.
    private static Stream<Map.Entry<String, String>> reportedClasses(ModuleSelection selection,
                                                                     Map<String, List<ClassInfo>> testClassesByModule,
                                                                     ClassIndex index) {
        return classesOf(selection, testClassesByModule).filter(type -> isTestClass(type, index))
                        .map(type -> Map.entry(reportedName(type,
                                                            selection.naming()),
                                               type.binaryName()));
    }

    /// A runnable class with at least one effective test method: one that can report rows.
    private static boolean isTestClass(ClassInfo type, ClassIndex index) {
        return isRunnableClass(type) && !TestClasses.testMethods(type, index).isEmpty();
    }

    private static ManifestOutcome.Computed computed(List<ModuleSelection> active,
                                                     Set<Expectation> expectations,
                                                     Map<String, List<ClassInfo>> testClassesByModule,
                                                     ClassIndex index,
                                                     Set<String> modulesWithTestSourcesButNoClasses) {
        var required = required(expectations);
        var optional = optional(expectations, required);

        return ManifestOutcome.Computed.computed(required,
                                                 optional,
                                                 notReached(active, modulesWithTestSourcesButNoClasses),
                                                 unresolvedAnnotations(active, testClassesByModule, index));
    }

    /// One class a selection expects, and whether it must report (some kept test method is not optional).
    record Expectation(ManifestEntry entry, ClassInfo type, boolean required) {
        static Expectation expectation(ManifestEntry entry, ClassInfo type, boolean required) {
            return new Expectation(entry, type, required);
        }
    }

    private static Set<Expectation> expectations(List<ModuleSelection> selections,
                                                 Map<String, List<ClassInfo>> testClassesByModule,
                                                 ClassIndex index) {
        return selections.stream()
                         .flatMap(selection -> expectationsOf(selection, testClassesByModule, index))
                         .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<ManifestEntry> required(Set<Expectation> expectations) {
        return expectations.stream()
                           .filter(Expectation::required)
                           .map(Expectation::entry)
                           .collect(Collectors.toUnmodifiableSet());
    }

    /// A class some selection requires is not optional, even when another selection would not require it.
    private static Set<ManifestEntry> optional(Set<Expectation> expectations, Set<ManifestEntry> required) {
        return expectations.stream()
                           .map(Expectation::entry)
                           .filter(Predicate.not(required::contains))
                           .collect(Collectors.toUnmodifiableSet());
    }

    /// The scan is built once per selection: every `@Nested` class it reaches looks its hosts up there.
    private static Stream<Expectation> expectationsOf(ModuleSelection selection,
                                                      Map<String, List<ClassInfo>> testClassesByModule,
                                                      ClassIndex index) {
        var scan = ModuleScan.moduleScan(moduleClasses(selection, testClassesByModule), index);

        return scan.classes()
                   .stream()
                   .filter(ManifestRules::isRunnableClass)
                   .flatMap(type -> expectation(type,
                                                selection,
                                                keptMethods(type, selection, index, scan)).stream());
    }

    /// A class with a kept test method is expected; it is required when some kept method always reports.
    private static Option<Expectation> expectation(ClassInfo type, ModuleSelection selection, List<TestMethod> kept) {
        return kept.isEmpty()
               ? none()
               : some(Expectation.expectation(ManifestEntry.manifestEntry(selection.module().path(),
                                                                          reportedName(type, selection.naming()),
                                                                          selection.runner()),
                                              type,
                                              kept.stream().anyMatch(Predicate.not(TestMethod::optional))));
    }

    /// The class's `<testcase classname>` under the selection's plugin version ([ReportNaming]).
    private static String reportedName(ClassInfo type, ReportNaming naming) {
        return switch (naming) {
            case BINARY_NAME -> type.binaryName();
            case OWN_DISPLAY_NAME -> type.displayName().or(type.binaryName());
        };
    }

    private static Stream<ClassInfo> classesOf(ModuleSelection selection,
                                               Map<String, List<ClassInfo>> testClassesByModule) {
        return moduleClasses(selection, testClassesByModule).stream();
    }

    private static List<ClassInfo> moduleClasses(ModuleSelection selection,
                                                 Map<String, List<ClassInfo>> testClassesByModule) {
        return testClassesByModule.getOrDefault(selection.module().path(),
                                                List.of());
    }

    private static boolean isRunnableClass(ClassInfo type) {
        return isConcrete(type) && isRunnableKind(type);
    }

    private static boolean isConcrete(ClassInfo type) {
        return ! type.isInterface() && !type.isAbstract();
    }

    /// JUnit runs a top-level or static member class, and an inner class only when it is `@Nested`; never a
    /// private, local or anonymous class.
    private static boolean isRunnableKind(ClassInfo type) {
        return ! type.isPrivate() && switch (type.nesting()) {
            case TOP_LEVEL, STATIC_MEMBER -> true;
            case INNER -> type.nested();
            case LOCAL -> false;
        };
    }

    /// One way JUnit reaches a class: the class path surefire selects (the class's own, or that of a class
    /// JUnit discovers it from), and the tags the test instances enclosing it there add.
    record Discovery(String path, Set<String> tags) {
        public Discovery {
            tags = Set.copyOf(tags);
        }

        static Discovery discovery(String path, Set<String> tags) {
            return new Discovery(path, tags);
        }

        /// This discovery, one enclosing instance further out: the host's class tags added.
        Discovery enclosedBy(Set<String> hostTags) {
            return discovery(path, union(tags, hostTags));
        }
    }

    /// What one module's surefire run can reach. `scanned` are the module's own `test-classes`, the only classes
    /// surefire selects by their own path. `classes` adds the `@Nested` classes of other modules that JUnit reaches
    /// from them through a superclass in another module's test-jar, and from those in turn (R-6). `hostsByAncestor`
    /// holds the runnable ones of `classes` by each class on their superclass chain (themselves included), followed
    /// through the whole reactor: the hosts JUnit discovers a `@Nested` class of that ancestor through.
    record ModuleScan(Set<String> scanned, List<ClassInfo> classes, Map<String, List<ClassInfo>> hostsByAncestor) {
        public ModuleScan {
            scanned = Set.copyOf(scanned);
            classes = List.copyOf(classes);
            hostsByAncestor = Map.copyOf(hostsByAncestor);
        }

        static ModuleScan moduleScan(List<ClassInfo> moduleClasses, ClassIndex index) {
            var classes = withReachedNested(moduleClasses, index);

            return new ModuleScan(names(moduleClasses), classes, byAncestor(classes, index));
        }

        /// The hosts whose superclass chain includes `outer` (`outer` among them).
        Stream<ClassInfo> hostsOf(ClassInfo outer) {
            return hostsByAncestor.getOrDefault(outer.binaryName(),
                                                List.of())
                                  .stream();
        }

        boolean isScanned(ClassInfo type) {
            return scanned.contains(type.binaryName());
        }

        /// `classes` and every `@Nested` class whose enclosing class is an ancestor of one of their runnable
        /// classes, until no more are reached.
        private static List<ClassInfo> withReachedNested(List<ClassInfo> classes, ClassIndex index) {
            var ancestors = byAncestor(classes, index).keySet();
            var known = names(classes);
            var reached = index.byName()
                               .values()
                               .stream()
                               .filter(type -> isReachedNested(type, ancestors, known))
                               .toList();

            return reached.isEmpty()
                   ? classes
                   : withReachedNested(Stream.concat(classes.stream(), reached.stream()).toList(),
                                       index);
        }

        private static boolean isReachedNested(ClassInfo type, Set<String> ancestors, Set<String> known) {
            return type.nested()
                   && !known.contains(type.binaryName())
                   && type.outerName()
                          .filter(ancestors::contains)
                          .isPresent();
        }

        private static Set<String> names(List<ClassInfo> classes) {
            return classes.stream()
                          .map(ClassInfo::binaryName)
                          .collect(Collectors.toUnmodifiableSet());
        }

        private static Map<String, List<ClassInfo>> byAncestor(List<ClassInfo> classes, ClassIndex index) {
            return classes.stream()
                          .filter(ManifestRules::isRunnableClass)
                          .flatMap(host -> ancestorEntries(host, index))
                          .collect(Collectors.groupingBy(Map.Entry::getKey,
                                                         Collectors.mapping(Map.Entry::getValue,
                                                                            Collectors.toList())));
        }

        private static Stream<Map.Entry<String, ClassInfo>> ancestorEntries(ClassInfo host, ClassIndex index) {
            return ancestry(host, index, Set.of()).map(ancestor -> Map.entry(ancestor, host));
        }

        /// `type`, then each superclass the index holds, until the chain leaves the index or loops back.
        private static Stream<String> ancestry(ClassInfo type, ClassIndex index, Set<String> seen) {
            var next = with(seen, type.binaryName());

            return Stream.concat(Stream.of(type.binaryName()),
                                 type.superName()
                                     .filter(name -> !next.contains(name))
                                     .flatMap(index::find)
                                     .stream()
                                     .flatMap(superclass -> ancestry(superclass, index, next)));
        }
    }

    /// The test methods that survive the patterns and the groups under some discovery of the class.
    private static List<TestMethod> keptMethods(ClassInfo type,
                                                ModuleSelection selection,
                                                ClassIndex index,
                                                ModuleScan scan) {
        var tests = TestClasses.testMethods(type, index);
        var ownPath = classPath(type.binaryName());

        return reach(type,
                     index,
                     scan,
                     Set.of(type.binaryName())).flatMap(discovery -> keptAt(discovery, ownPath, selection, tests))
                    .distinct()
                    .toList();
    }

    /// The methods selected under `discovery` whose tags, with the discovery's, survive the groups.
    private static Stream<TestMethod> keptAt(Discovery discovery,
                                             String ownPath,
                                             ModuleSelection selection,
                                             List<TestMethod> tests) {
        return named(tests,
                     selectedAt(selection,
                                discovery.path(),
                                ownPath,
                                tests)).stream()
                    .filter(method -> isKeptByGroups(union(method.tags(),
                                                           discovery.tags()),
                                                     selection.params()));
    }

    private static boolean isKeptByGroups(Set<String> tags, TestParams params) {
        return ! hasAny(tags, params.excludedGroups()) && (params.groups()
                                                                 .isEmpty() || hasAny(tags, params.groups()));
    }

    /// The names of the methods run when surefire scans the class at `path`.
    private static Set<String> selectedAt(ModuleSelection selection,
                                          String path,
                                          String ownPath,
                                          List<TestMethod> tests) {
        var params = selection.params();

        return params.test()
                     .map(test -> selectedByTest(test, path, ownPath, tests))
                     .or(() -> patternSelected(params, path, tests));
    }

    /// When `test` has surefire scan the class at `path`: its methods, filtered by the class's own path when
    /// `test` names a method (surefire 3.5.4 `TestMethodFilter` judges each method by the class that declares
    /// it in the test tree, a `@Nested` class's own name, not by the class it was discovered from).
    private static Set<String> selectedByTest(String test, String path, String ownPath, List<TestMethod> tests) {
        return SurefirePatterns.isClassSelected(test, path)
               ? filteredMethods(test, ownPath, tests)
               : Set.of();
    }

    private static Set<String> filteredMethods(String test, String ownPath, List<TestMethod> tests) {
        return SurefirePatterns.hasMethodPatterns(test)
               ? SurefirePatterns.selectedMethods(test, ownPath, names(tests))
               : names(tests);
    }

    private static List<TestMethod> named(List<TestMethod> tests, Set<String> names) {
        return tests.stream()
                    .filter(method -> names.contains(method.name()))
                    .toList();
    }

    private static Set<String> patternSelected(TestParams params, String path, List<TestMethod> tests) {
        return SurefirePatterns.isSelected(params.includes(), params.excludes(), path)
               ? names(tests)
               : Set.of();
    }

    /// Every discovery of `type`: its own, then, for a `@Nested` class, each discovery of a host of its enclosing
    /// class. JUnit expands a test class into the `@Nested` classes declared in it and in its superclasses
    /// (`ReflectionUtils.findNestedClasses`), so a host is the enclosing class or any class of the module extending
    /// it ([ModuleScan]), and must itself be a test class JUnit accepts (an abstract one is not). The report names the
    /// `@Nested` class by its own reported name whichever host discovered it (oracle: `N2Base$In` reports inside
    /// `TEST-o.N2InhNestedTest.xml`). A static member class is not discovered from its enclosing class. Only a class
    /// of the scanned module has a discovery of its own: surefire selects no other module's class by its path.
    /// `seen` stops an inner-class cycle, which JUnit refuses.
    private static Stream<Discovery> reach(ClassInfo type, ClassIndex index, ModuleScan scan, Set<String> seen) {
        return Stream.concat(ownDiscovery(type, index).filter(_ -> scan.isScanned(type)).stream(),
                             hostedDiscoveries(type, index, scan, seen));
    }

    private static Stream<Discovery> hostedDiscoveries(ClassInfo type,
                                                       ClassIndex index,
                                                       ModuleScan scan,
                                                       Set<String> seen) {
        return type.outerName()
                   .filter(_ -> type.nested())
                   .stream()
                   .flatMap(outer -> discoveriesThrough(outer, index, scan, seen));
    }

    /// The discoveries through the hosts of `outer`; an enclosing class the index lacks is assumed a test class
    /// selected by its own path.
    private static Stream<Discovery> discoveriesThrough(String outer,
                                                        ClassIndex index,
                                                        ModuleScan scan,
                                                        Set<String> seen) {
        return index.find(outer)
                    .map(found -> discoveriesThroughHostsOf(found, index, scan, seen))
                    .or(() -> Stream.of(Discovery.discovery(classPath(outer),
                                                            Set.of())));
    }

    private static Stream<Discovery> discoveriesThroughHostsOf(ClassInfo outer,
                                                               ClassIndex index,
                                                               ModuleScan scan,
                                                               Set<String> seen) {
        return scan.hostsOf(outer)
                   .filter(host -> !seen.contains(host.binaryName()))
                   .flatMap(host -> hostDiscoveries(host, index, scan, seen));
    }

    private static Stream<Discovery> hostDiscoveries(ClassInfo host,
                                                     ClassIndex index,
                                                     ModuleScan scan,
                                                     Set<String> seen) {
        var hostTags = TestClasses.classTags(host, index);

        return reach(host, index, scan, with(seen, host.binaryName())).map(discovery -> discovery.enclosedBy(hostTags));
    }

    /// The class selected by its own path, under its declared enclosing classes: JUnit resolves a `@Nested`
    /// class's enclosing class first, so none when an enclosing class is not a test class JUnit accepts (oracle:
    /// `-Dtest=N3AbsBaseTest$In` reports nothing, its enclosing class is abstract).
    private static Option<Discovery> ownDiscovery(ClassInfo type, ClassIndex index) {
        return enclosingTags(type, index, Set.of()).map(tags -> Discovery.discovery(classPath(type.binaryName()), tags));
    }

    /// The class tags of `type`'s declared enclosing test instances; none when one is not a test class JUnit
    /// accepts, or when the chain loops back. An enclosing class the index lacks adds no tags.
    private static Option<Set<String>> enclosingTags(ClassInfo type, ClassIndex index, Set<String> seen) {
        return type.outerName()
                   .filter(_ -> type.nested())
                   .map(outer -> outerTags(outer,
                                           index,
                                           with(seen,
                                                type.binaryName())))
                   .or(() -> some(Set.of()));
    }

    private static Option<Set<String>> outerTags(String outer, ClassIndex index, Set<String> seen) {
        return seen.contains(outer)
               ? none()
               : index.find(outer)
                      .map(found -> enclosingClassTags(found, index, seen))
                      .or(() -> some(Set.of()));
    }

    private static Option<Set<String>> enclosingClassTags(ClassInfo outer, ClassIndex index, Set<String> seen) {
        return isRunnableClass(outer)
               ? enclosingTags(outer, index, seen).map(tags -> union(tags, TestClasses.classTags(outer, index)))
               : none();
    }

    private static Set<String> with(Set<String> seen, String name) {
        return union(seen, Set.of(name));
    }

    private static String classPath(String binaryName) {
        return binaryName.replace('.', '/');
    }

    private static Set<String> names(List<TestMethod> tests) {
        return tests.stream()
                    .map(TestMethod::name)
                    .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        return Stream.concat(left.stream(),
                             right.stream())
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean hasAny(Set<String> tags, Set<String> wanted) {
        return tags.stream()
                   .anyMatch(wanted::contains);
    }

    private static List<String> notReached(List<ModuleSelection> selections, Set<String> withoutClasses) {
        return List.copyOf(selections.stream()
                                     .map(ManifestRules::modulePath)
                                     .filter(withoutClasses::contains)
                                     .collect(Collectors.toCollection(TreeSet::new)));
    }

    private static Set<String> unresolvedAnnotations(List<ModuleSelection> selections,
                                                     Map<String, List<ClassInfo>> testClassesByModule,
                                                     ClassIndex index) {
        return selections.stream()
                         .flatMap(selection -> classesOf(selection, testClassesByModule))
                         .flatMap(type -> TestClasses.unresolvedAnnotations(type, index).stream())
                         .collect(Collectors.toUnmodifiableSet());
    }

    private static String modulePath(ModuleSelection selection) {
        return selection.module()
                        .path();
    }

    record unused() implements ManifestRules {}
}
