package org.pragmatica.temper.domain.mvn;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.Reach;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Structured, read-only view of one `mvn` argv: system properties, project selection, reactor flags and
/// lifecycle phases/goals. Every rule that judges a step (test request, port group, refusals) reads it from
/// here, so they agree on how an argv is understood.
///
/// The parser is deliberately conservative: an option it does not know is skipped without consuming a
/// value, so an unknown option can never hide a following phase from the rules. Skipping is not accepting: the user's
/// options are judged by [UserOptions] against the option list (R-EFFECT, maintainer ruling Q-M4-11d), which names every
/// element starting with `-` that is no listed spelling.
public record MvnStep(List<String> argv,
                      List<Property> properties,
                      List<String> projects,
                      List<String> pomFiles,
                      List<String> profiles,
                      List<String> goals,
                      boolean alsoMake,
                      boolean alsoMakeDependents) {
    /// A `-Dkey=value` system property; `-Dkey` alone means `true`, as in Maven.
    public record Property(String key, String value) {
        public static Property property(String key, String value) {
            return new Property(key, value);
        }
    }

    /// Lifecycle phases at or past `test`: reaching any of them runs surefire.
    public static final Set<String> TEST_PHASES = Set.of("test",
                                                         "prepare-package",
                                                         "package",
                                                         "pre-integration-test",
                                                         "integration-test",
                                                         "post-integration-test",
                                                         "verify",
                                                         "install",
                                                         "deploy");

    /// Lifecycle phases at or past `integration-test`: reaching any of them runs failsafe where it is bound.
    public static final Set<String> INTEGRATION_PHASES = Set.of("integration-test",
                                                                "post-integration-test",
                                                                "verify",
                                                                "install",
                                                                "deploy");

    private static final Pattern SUREFIRE_GOAL = Pattern.compile("^(surefire|(.+:)?maven-surefire-plugin(:[^:]+)?):test(@.+)?$");

    private static final Pattern FAILSAFE_GOAL = Pattern.compile("^(failsafe|(.+:)?maven-failsafe-plugin(:[^:]+)?):integration-test(@.+)?$");

    private static final Set<String> VALUE_OPTIONS = Set.of("-pl",
                                                            "--projects",
                                                            "-f",
                                                            "--file",
                                                            "-s",
                                                            "--settings",
                                                            "-gs",
                                                            "--global-settings",
                                                            "-t",
                                                            "--toolchains",
                                                            "-gt",
                                                            "--global-toolchains",
                                                            "-T",
                                                            "--threads",
                                                            "-rf",
                                                            "--resume-from",
                                                            "-l",
                                                            "--log-file",
                                                            "-b",
                                                            "--builder",
                                                            "-P",
                                                            "--activate-profiles");

    /// Options that shape the reactor or its interpolation: the effective-POM step reuses them (m1-design §2.2).
    private static final Set<String> SELECTION_OPTIONS = Set.of("-pl",
                                                                "--projects",
                                                                "-am",
                                                                "--also-make",
                                                                "-amd",
                                                                "--also-make-dependents",
                                                                "-P",
                                                                "--activate-profiles",
                                                                "-f",
                                                                "--file",
                                                                "-D",
                                                                "--define");

    /// Joined forms of [#SELECTION_OPTIONS] that carry their value in the same element.
    private static final List<String> SELECTION_PREFIXES = List.of("--define=",
                                                                   "--projects=",
                                                                   "--file=",
                                                                   "--activate-profiles=");

    public MvnStep {
        argv = List.copyOf(argv);
        properties = List.copyOf(properties);
        projects = List.copyOf(projects);
        pomFiles = List.copyOf(pomFiles);
        profiles = List.copyOf(profiles);
        goals = List.copyOf(goals);
    }

    /// Parses one step's argv. Element 0 is the executable and is not interpreted.
    public static MvnStep mvnStep(List<String> argv) {
        var tokens = tokens(argv);

        return new MvnStep(argv,
                           collect(tokens,
                                   Token.Define.class,
                                   define -> Stream.of(define.property())),
                           collect(tokens,
                                   Token.Projects.class,
                                   projects -> projects.entries()
                                                       .stream()),
                           collect(tokens,
                                   Token.PomFile.class,
                                   pomFile -> Stream.of(pomFile.path())),
                           collect(tokens,
                                   Token.Profiles.class,
                                   profiles -> profiles.entries()
                                                       .stream()),
                           collect(tokens,
                                   Token.Goal.class,
                                   goal -> Stream.of(goal.name())),
                           tokens.stream().anyMatch(Token.AlsoMake.class::isInstance),
                           tokens.stream().anyMatch(Token.AlsoMakeDependents.class::isInstance));
    }

    /// Value of the LAST occurrence of a system property, as Maven applies them.
    public Option<String> property(String key) {
        return Option.from(properties.stream()
                                     .filter(property -> property.key()
                                                                 .equals(key))
                                     .reduce((_, last) -> last)
                                     .map(Property::value));
    }

    /// True when a boolean system property is set and Maven would read it as true.
    public boolean flag(String key) {
        return property(key).map(value -> value.toLowerCase(Locale.ROOT)
                                               .equals("true"))
                       .or(false);
    }

    /// `-DskipTests` / `-Dmaven.test.skip`: both test plugins are skipped.
    public boolean skipsTests() {
        return flag("skipTests") || flag("maven.test.skip");
    }

    /// Surefire runs: a phase at or past `test`, or an explicit surefire goal, and tests are not skipped.
    public boolean runsSurefire() {
        return ! skipsTests() && goals.stream()
                                      .anyMatch(MvnStep::surefireGoal);
    }

    /// Failsafe may run: a phase at or past `integration-test`, or an explicit failsafe goal, and neither
    /// tests nor ITs are skipped. Whether the module binds failsafe needs the effective POM (M1), so a
    /// reached phase is read as "may run".
    public boolean runsFailsafe() {
        return ! skipsTests()
               && !flag("skipITs")
               && goals.stream()
                       .anyMatch(MvnStep::failsafeGoal);
    }

    /// The step requests tests (m0-design §3.1 `TestRequest`).
    public boolean requestsTests() {
        return runsSurefire() || runsFailsafe();
    }

    /// The `-pl` entries that select a module (excluding `!x` / `-x` exclusions).
    public List<String> selectedProjects() {
        return projects.stream()
                       .filter(Predicate.not(MvnStep::excluded))
                       .toList();
    }

    /// The `-pl` exclusion entries (`!x` / `-x`), without their prefix.
    public List<String> excludedProjects() {
        return projects.stream()
                       .filter(MvnStep::excluded)
                       .map(exclusion -> exclusion.substring(1))
                       .toList();
    }

    /// The furthest test-bearing phase the step reaches (m1-design §2.2): `INTEGRATION_TEST` for a phase at or
    /// past `integration-test` or an explicit failsafe goal, `TEST` for one at or past `test` or an explicit
    /// surefire goal, none for a step that reaches neither. Skip flags are not read here: the manifest rules
    /// judge them per module.
    public Option<Reach> reach() {
        return reached(MvnStep::failsafeGoal, Reach.INTEGRATION_TEST).orElse(() -> reached(MvnStep::surefireGoal,
                                                                                           Reach.TEST));
    }

    /// The arguments that select the reactor or shape its interpolation, in argv order and with their values:
    /// `-pl`, `-am`, `-amd`, `-P`, `-f` and every `-D`, in each form Maven accepts (m1-design §2.2).
    public List<String> reactorSelectionArgs() {
        return Stream.iterate(1,
                              index -> index < argv.size(),
                              index -> index + width(argv, index))
                     .flatMap(index -> selectionArgsAt(argv, index).stream())
                     .toList();
    }

    /// Whether this step's reactor may contain the module at `modulePath` (repository-relative directory).
    ///
    /// The selection (`-pl`, `-am`, `-amd`, exclusions, the `-f` base) is judged as in M0: a whole-reactor run,
    /// `-am` and `-amd` may include any module, because the module graph is not known without the POMs. On top
    /// of that, a step that builds the root pom's default reactor (no `-f`, no `-P`) cannot include a module
    /// outside `reactor` when the reactor is known: Maven selects `-pl`/`-am`/`-amd` projects only from the
    /// reactor (m1-design §1.6). An unknown reactor keeps the M0 answer.
    public boolean mayInclude(String modulePath, Option<ReactorModules> reactor) {
        return selectable(modulePath) && inReactor(reactor, known -> known.contains(modulePath));
    }

    /// Whether this step's reactor may contain a module whose directory is `segment` (R-FAILSAFE, Q-M1-5): a
    /// selected `-pl` entry or `-f` path names it (M0), or the step is reactor-wide (whole reactor, `-am`,
    /// `-amd`) and does not exclude it. As in [#mayInclude], a step on the known default reactor cannot build a
    /// module outside it, whatever its selection; an unknown reactor may hold any module.
    public boolean mayIncludeSegment(String segment, Option<ReactorModules> reactor) {
        return inReactor(reactor, known -> known.hasSegment(segment))
               && (namesModuleSegment(segment) || isReactorWide() && !excludesSegment(segment));
    }

    /// Whether a selected `-pl` entry (path or `:artifactId`) or a `-f` path names a module whose directory
    /// is `segment` (R-FAILSAFE's `moduleSegment`).
    public boolean namesModuleSegment(String segment) {
        return projectNamesSegment(segment) || pomFileNamesSegment(segment);
    }

    private boolean projectNamesSegment(String segment) {
        return selectedProjects().stream()
                               .map(ModuleMatch::artifactOrPath)
                               .map(ModuleMatch::lastSegment)
                               .anyMatch(segment::equals);
    }

    private boolean pomFileNamesSegment(String segment) {
        return pomFiles.stream()
                       .anyMatch(path -> ModuleMatch.hasSegment(path, segment));
    }

    /// The M0 judgement: the selection may include `modulePath`, whatever the reactor holds.
    private boolean selectable(String modulePath) {
        return ! excludes(modulePath) && (alsoMake || alsoMakeDependents || selects(modulePath));
    }

    /// `check` against the reactor when the step builds the root pom's default reactor and that reactor is
    /// known; true otherwise (conservative).
    private boolean inReactor(Option<ReactorModules> reactor, Fn1<Boolean, ReactorModules> check) {
        return reactor.filter(_ -> buildsDefaultReactor())
                      .map(check)
                      .or(true);
    }

    /// No `-f` and no `-P` on the argv: the reactor is the root pom's, with only auto-activated profiles.
    /// Precondition on the caller: the tree's `.mvn/maven.config` (which Maven prepends to every argv) adds no
    /// `-f`, `-P` or `-pl`, and the runner image's settings activate no profile; otherwise it must pass an
    /// unknown reactor ([ReactorModules]).
    private boolean buildsDefaultReactor() {
        return pomFiles.isEmpty() && profiles.isEmpty();
    }

    /// A whole-reactor run, `-am` or `-amd`: the step may build modules no `-pl` entry names.
    private boolean isReactorWide() {
        return alsoMake || alsoMakeDependents || selectedProjects().isEmpty();
    }

    private boolean excludesSegment(String segment) {
        return excludedProjects().stream()
                               .map(ModuleMatch::artifactOrPath)
                               .map(ModuleMatch::lastSegment)
                               .anyMatch(segment::equals);
    }

    private Option<Reach> reached(Fn1<Boolean, String> goal, Reach reach) {
        return goals.stream()
                    .anyMatch(goal::apply)
               ? some(reach)
               : none();
    }

    private boolean excludes(String modulePath) {
        return excludedProjects().stream()
                               .anyMatch(project -> ModuleMatch.matches(project,
                                                                        baseDir(),
                                                                        modulePath));
    }

    private boolean selects(String modulePath) {
        return selectedProjects().isEmpty()
               ? ModuleMatch.under(baseDir(), modulePath)
               : selectedProjects().stream()
                                 .anyMatch(project -> ModuleMatch.matches(project,
                                                                          baseDir(),
                                                                          modulePath));
    }

    private String baseDir() {
        return pomFiles.isEmpty()
               ? ""
               : ModuleMatch.directoryOf(pomFiles.getLast());
    }

    private static boolean excluded(String entry) {
        return entry.startsWith("!") || entry.startsWith("-");
    }

    private static boolean surefireGoal(String goal) {
        return TEST_PHASES.contains(goal) || SUREFIRE_GOAL.matcher(goal).matches();
    }

    private static boolean failsafeGoal(String goal) {
        return INTEGRATION_PHASES.contains(goal) || FAILSAFE_GOAL.matcher(goal).matches();
    }

    /// The selection argument at `index` with its value, or nothing when it is not one.
    private static List<String> selectionArgsAt(List<String> argv, int index) {
        return SELECTION_OPTIONS.contains(argv.get(index))
               ? argv.subList(index,
                              Math.min(index + width(argv, index), argv.size()))
               : joinedSelection(argv.get(index));
    }

    private static List<String> joinedSelection(String arg) {
        return isJoinedSelection(arg)
               ? List.of(arg)
               : List.of();
    }

    private static boolean isJoinedSelection(String arg) {
        return (arg.startsWith("-D") || arg.startsWith("-P")) && arg.length() > 2 || SELECTION_PREFIXES.stream().anyMatch(arg::startsWith);
    }

    private static boolean consumesValue(String arg) {
        return arg.equals("-D") || arg.equals("--define") || VALUE_OPTIONS.contains(arg);
    }

    /// One recognised argv element (with its value, for options that take one).
    private sealed interface Token {
        record Define(Property property) implements Token {}

        record Projects(List<String> entries) implements Token {}

        record PomFile(String path) implements Token {}

        record Profiles(List<String> entries) implements Token {}

        record Goal(String name) implements Token {}

        record AlsoMake() implements Token {}

        record AlsoMakeDependents() implements Token {}
    }

    private static List<Token> tokens(List<String> argv) {
        return Stream.iterate(1,
                              index -> index < argv.size(),
                              index -> index + width(argv, index))
                     .map(index -> tokenAt(argv, index))
                     .flatMap(Option::stream)
                     .toList();
    }

    /// Elements an option occupies: 2 when it takes the next element as its value.
    private static int width(List<String> argv, int index) {
        return consumesValue(argv.get(index))
               ? 2
               : 1;
    }

    private static Option<Token> tokenAt(List<String> argv, int index) {
        return token(argv.get(index), valueAfter(argv, index));
    }

    private static Option<String> valueAfter(List<String> argv, int index) {
        return index + 1 < argv.size()
               ? some(argv.get(index + 1))
               : none();
    }

    private static Option<Token> token(String arg, Option<String> next) {
        return switch (arg) {
            case "-D", "--define" -> next.map(value -> new Token.Define(definition(value)));
            case "-pl", "--projects" -> next.map(value -> new Token.Projects(split(value)));
            case "-f", "--file" -> next.map(Token.PomFile::new);
            case "-P", "--activate-profiles" -> next.map(value -> new Token.Profiles(split(value)));
            case "-am", "--also-make" -> some(new Token.AlsoMake());
            case "-amd", "--also-make-dependents" -> some(new Token.AlsoMakeDependents());
            default -> joinedForm(arg);
        };
    }

    private static Option<Token> joinedForm(String arg) {
        if (arg.startsWith("--define=")) {
            return some(new Token.Define(definition(arg.substring("--define=".length()))));
        }

        if (arg.startsWith("-D") && arg.length() > 2) {
            return some(new Token.Define(definition(arg.substring(2))));
        }

        if (arg.startsWith("--projects=")) {
            return some(new Token.Projects(split(arg.substring("--projects=".length()))));
        }

        if (arg.startsWith("--file=")) {
            return some(new Token.PomFile(arg.substring("--file=".length())));
        }

        if (arg.startsWith("--activate-profiles=")) {
            return some(new Token.Profiles(split(arg.substring("--activate-profiles=".length()))));
        }

        if (arg.startsWith("-P") && arg.length() > 2) {
            return some(new Token.Profiles(split(arg.substring(2))));
        }

        return arg.startsWith("-")
               ? none()
               : some(new Token.Goal(arg));
    }

    private static <T extends Token, R> List<R> collect(List<Token> tokens, Class<T> type, Fn1<Stream<R>, T> values) {
        return tokens.stream()
                     .filter(type::isInstance)
                     .map(type::cast)
                     .flatMap(values::apply)
                     .toList();
    }

    /// Maven trims the property name, so `"-D test=X"` (one argv element) defines `test`.
    static Property definition(String definition) {
        var equals = definition.indexOf('=');

        return equals < 0
               ? Property.property(definition.trim(), "true")
               : Property.property(definition.substring(0, equals).trim(),
                                   definition.substring(equals + 1));
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split(","))
                     .map(String::trim)
                     .filter(Predicate.not(String::isEmpty))
                     .toList();
    }
}
