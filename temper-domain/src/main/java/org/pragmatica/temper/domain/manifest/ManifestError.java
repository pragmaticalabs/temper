package org.pragmatica.temper.domain.manifest;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Failures of the manifest engine. Every one of them makes the unit `ERROR(manifest: <message>)` (Q-M1-7):
/// none is retryable, because the same POM and class files give the same failure.
public sealed interface ManifestError extends Cause {
    /// The effective POM is not well-formed XML; the parser's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record MalformedPom(Cause origin, String message) implements ManifestError, Cause.Wrapped, Cause.Terminal {
        public static final Fn1<MalformedPom, Cause> FACTORY = Causes.forOneValue("Malformed effective POM",
                                                                                  MalformedPom::new);
    }

    /// A project in the effective POM lacks an element the engine needs.
    record MissingElement(String project, String element, String message) implements ManifestError, Cause.Terminal {
        public static final Fn2<MissingElement, String, String> FACTORY = Causes.forTwoValues("Effective POM project %s has no usable <%s>",
                                                                                              MissingElement::new);
    }

    /// A build directory of the effective POM lies outside the workspace.
    record OutsideWorkspace(String path, String message) implements ManifestError, Cause.Terminal {
        public static final Fn1<OutsideWorkspace, String> FACTORY = Causes.forOneValue("Path outside the workspace: %s",
                                                                                       OutsideWorkspace::new);
    }

    /// A class file does not parse; `file` names it, the class-file reader's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record MalformedClass(String file, Cause origin, String message) implements ManifestError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<MalformedClass, String, Cause> FACTORY = Causes.forTwoValues("Malformed class file %s",
                                                                                             MalformedClass::new);
    }

    /// A test-plugin parameter whose effect the rules do not model (`includesFile`, `excludesFile`,
    /// `dependenciesToScan`, `groups`/`excludedGroups` written as a list).
    record UnsupportedParameter(String module, String parameter, String message) implements ManifestError, Cause.Terminal {
        public static final Fn2<UnsupportedParameter, String, String> FACTORY = Causes.forTwoValues("Unsupported test configuration in %s: %s",
                                                                                                    UnsupportedParameter::new);
    }

    /// An `includes`/`excludes` pattern surefire 3.5.4 rejects, failing the build: a `#method` filter in either
    /// ("Method filter prohibited"), or a `!` in an exclude ("Exclamation mark not expected"). Only reached when
    /// `-Dtest` is absent, since `-Dtest` replaces both lists before surefire checks them.
    record RejectedPattern(String module, String parameter, String pattern, String message) implements ManifestError, Cause.Terminal {
        public static final Fn3<RejectedPattern, String, String, String> FACTORY = Causes.forThreeValues("Unsupported test configuration in %s: surefire rejects the %s pattern %s",
                                                                                                         RejectedPattern::new);
    }

    /// A test execution bound to a phase the unit's reach cannot place (outside the default lifecycle, or
    /// `install`/`deploy` under `INTEGRATION_TEST`).
    record UnplaceablePhase(String module, String execution, String phase, String message) implements ManifestError, Cause.Terminal {
        public static final Fn3<UnplaceablePhase, String, String, String> FACTORY = Causes.forThreeValues("Unsupported test configuration in %s: execution %s bound to phase %s",
                                                                                                          UnplaceablePhase::new);
    }

    /// A JUnit 4 category (a class name) in `groups` or `excludedGroups`: not a JUnit 5 tag the rules can match.
    record Junit4Category(String module, String parameter, String category, String message) implements ManifestError, Cause.Terminal {
        public static final Fn3<Junit4Category, String, String, String> FACTORY = Causes.forThreeValues("Unsupported JUnit 4 category in %s %s: %s",
                                                                                                        Junit4Category::new);
    }

    /// A selected module's test class is JUnit 3 or JUnit 4 (ruling R-11: the rules model Jupiter only); `className`
    /// is the first such class by name.
    record LegacyJunit(String module, String className, String message) implements ManifestError, Cause.Terminal {
        public static final Fn2<LegacyJunit, String, String> FACTORY = Causes.forTwoValues("JUnit 3/4 tests are not supported (Jupiter only): module %s, class %s",
                                                                                           LegacyJunit::new);
    }

    /// An expected test class whose own `@DisplayName` is blank (m1-design §2.4 rule 5): surefire would report an empty
    /// `classname`, the protocol refuses a blank entry name, and JUnit treats it as a configuration error; `className`
    /// is the first such class by module and binary name.
    record BlankDisplayName(String module, String className, String message) implements ManifestError, Cause.Terminal {
        public static final Fn2<BlankDisplayName, String, String> FACTORY = Causes.forTwoValues("Blank @DisplayName is not supported: module %s, class %s",
                                                                                                BlankDisplayName::new);
    }

    /// Two or more runnable test classes of the selected modules report under one `<testcase classname>`
    /// (m1-design §2.4 rule 5): a shared `@DisplayName`, or one equal to another class's binary name. Rows cannot tell
    /// them apart, so one class's report would satisfy the other's entry, and across units the shared name would read
    /// as a class reported twice (`ERROR(plan)`). `classNames` are their binary names, sorted and comma-separated.
    record SharedDisplayName(String reportedName, String classNames, String message) implements ManifestError, Cause.Terminal {
        public static final Fn2<SharedDisplayName, String, String> FACTORY = Causes.forTwoValues("Classes share @DisplayName '%s', which is not supported: %s",
                                                                                                 SharedDisplayName::new);
    }

    /// A reached, non-skipped test execution whose plugin version has no measured `<testcase classname>` naming
    /// ([ReportNaming]): absent, unparseable, qualified, or outside the measured bands.
    record UnsupportedPluginVersion(String module, String plugin, String version, String message) implements ManifestError, Cause.Terminal {
        public static final Fn3<UnsupportedPluginVersion, String, String, String> FACTORY = Causes.forThreeValues("Unsupported test plugin version in %s: %s %s",
                                                                                                                  UnsupportedPluginVersion::new);
    }

    /// A JUnit tag expression (`&`, `|`, `!`, parentheses) in `groups` or `excludedGroups`.
    record UnsupportedTagExpression(String module, String parameter, String expression, String message) implements ManifestError, Cause.Terminal {
        public static final Fn3<UnsupportedTagExpression, String, String, String> FACTORY = Causes.forThreeValues("Unsupported tag expression in %s %s: %s",
                                                                                                                  UnsupportedTagExpression::new);
    }
}
