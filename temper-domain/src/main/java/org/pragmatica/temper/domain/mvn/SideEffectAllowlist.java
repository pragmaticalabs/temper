package org.pragmatica.temper.domain.mvn;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The build-only allowlist of [SideEffectRule] (m4-design §5.1, maintainer ruling Q-M4-11): the list as data and its parse into
/// this value, nothing else. The list is private, so the parse is its only reader. A line the parser does not understand
/// fails the whole parse; [SideEffectRule] then judges every step external. Only [#TEXT] changes when the list does.
public record SideEffectAllowlist(Set<String> phases,
                                  List<Plugin> plugins,
                                  Set<String> profiles,
                                  List<Property> properties,
                                  List<MvnOption> options) {
    private static final String TEXT = """
        # The build-only allowlist of SideEffectRule (m4-design §5.1, maintainer ruling Q-M4-11). A `Mvn` argv is NONE only when every
        # phase, goal and -P profile in it, and every option and -D property the user wrote, is listed here; anything else is EXTERNAL and
        # refused at submit (R-EFFECT, §5.3). Properties the planner appends itself (-Dmaven.repo.local, -Doutput, ...) are
        # not user input and are not judged.
        # A line the parser does not understand fails the parse, and a failed parse makes every argv EXTERNAL, the parse error
        # in the refusal (a test parses this text, so a bad edit fails the build). Parsed once in the planner's JVM (the
        # controller). It is a Java text block, not a classpath resource, because temper-domain is pure (jbct.toml: no I/O,
        # no dependency on a read/ adapter package); it is still data: edit the lines, not the rule.
        #
        #   phase <name>
        #   plugin <prefix> <groupId>:<artifactId> <goal,goal,...>   (matches `prefix:goal` and `groupId:artifactId[:version]:goal`)
        #   profile <name>
        #   property <name> skip|boolean|selector   (an exact -D key; skip and boolean take true/false or no value, selector
        #                                           any value)
        #   option <short> <long> <shape> [narrows]   (an option the user may write, maintainer ruling Q-M4-11d; see the options below)
        #
        # Judgement, recorded: a goal is listed when it writes only inside the unit's workspace and local repository and
        # reaches the network only to resolve artifacts. Goals are listed one by one, never "every goal": a goal a plugin
        # gains later, or one not judged yet, is external. Left out of listed plugins: dependency purge-local-repository
        # (deletes from the local repository), copy, unpack, copy-dependencies, unpack-dependencies (write to a configured
        # directory), get (fetches an arbitrary artifact), add and remove (rewrite pom.xml); jbct deploy-slices,
        # install-slices, package-*, generate-blueprint, collect-slice-deps, process, verify-slice (slice publication and
        # tooling, not build steps); jacoco dump (opens a TCP connection to a running agent); javadoc aggregate* and
        # test-aggregate*; source aggregate; install install-file; help effective-settings and system (print settings and
        # the environment into the log, which leaves the host); help evaluate and describe (each needs an unlisted
        # property, -Dexpression or -Dplugin, and evaluate can print ${settings.servers...} into the log; verify3 M1,
        # maintainer-confirmed). effective-pom, active-profiles and all-profiles run without a property and stay. Not listed at all (so EXTERNAL): deploy, release, scm,
        # wagon, site (site:deploy; site:site writes locally but is not a build step temper runs), jib, docker, dockerfile,
        # fabric8, gpg (signing reads a key the build hosts must not hold), nexus-staging, central-publishing, scm-publish,
        # jreleaser, cargo, k8s, invoker (runs nested builds whose goals argv never shows), exec/antrun (R-SHELL refuses
        # them first), versions (rewrites poms).
        # Properties (maintainer rulings Q-M4-11b, Q-M4-11c): exact names only, never a prefix (surefire.* or jbct.* would let in
        # path-valued keys such as surefire.reportsDirectory), and only a key that takes a selector or a boolean, never a path
        # or an address. Kinds: `skip` is a boolean that skips tests when true, `selector` narrows the tests run, `boolean`
        # does neither. Every user -D property is shown on the job's result, `skip` and `selector` ones marked tests-skipped
        # and tests-narrowed (UserProperties), so a green run with tests skipped or narrowed never reads as tested. Accepted
        # (Q-M4-11c): skipTests, maven.test.skip, skipITs, ember.skipTests (skip); test, it.test, groups, excludedGroups,
        # and PIT's targetClasses, targetTests (selector); failIfNoSpecifiedTests, surefire.failIfNoSpecifiedTests,
        # it.failIfNoSpecifiedTests (boolean). Refused (Q-M4-11c): jbct.skip and maven.test.failure.ignore, which turn a
        # gate green while examining nothing (the rerun's own -Dmaven.test.failure.ignore=true is a planner token, not
        # judged); additionalClasspathElements, because classpath jars are code the build executes taken from outside the
        # workspace (allow it later only for paths the planner resolves inside the workspace). Also left out: argLine (JVM
        # arguments, can carry an agent path), output, mdep.outputFile, *.reportsDirectory (paths), maven.repo.local*
        # (R-M2 refuses it first), temper.judge (ops' build-dist only, never submitted), any release/deploy switch
        # (-Drelease=true activates a profile).
        # Options (maintainer ruling Q-M4-11d): only the exact short or long spelling, as one argv element; the value of an option
        # that takes one is the next element. Shapes: none; threads (an integer or <n>C); modules (a comma list of module
        # selectors, each optionally `!`-prefixed: :artifactId, groupId:artifactId, or a relative module directory, names of
        # [A-Za-z0-9_.-] starting with [A-Za-z0-9_] and never holding `..`, separated by `/`, no leading `/`); module (one
        # selector, no `!`); file (a relative path of such names: the unit's cwd is its workspace, DockerRunArgs `-w`, and
        # no name can be `..`, so it resolves inside the workspace); profiles and define (judged by the profile and property
        # lines; -D and -P alone also take the value attached, -Dk=v and -Pname, Maven's documented forms). Refused, so
        # EXTERNAL: every other spelling (-fail-never and -projects single-dash long names, --fail-never=true and -pl=a `=`
        # forms, -fa/pom.xml and -T4 attached values) and every unlisted option: -fn (a failing build exits 0), -s -gs -t
        # -gt (settings and toolchains files), -l (log file), -emp -ep (encryption), -b (builder), and the rest. `narrows`
        # marks the option tests-narrowed on the job's result (-pl, -rf, -f, -N).
        # Known limits (§5.1): only the submitted argv is read. The repository's own .mvn/maven.config and .mvn/jvm.config,
        # pom bindings to a listed phase, a pom defaultGoal, and profiles the pom activates by default or by a file are not
        # seen. Containment there is structural: no outside code runs on bigboy, and the cloud hosts hold no credentials.

        # clean lifecycle
        phase pre-clean
        phase clean
        phase post-clean

        # default lifecycle, validate through install (deploy is not listed)
        phase validate
        phase initialize
        phase generate-sources
        phase process-sources
        phase generate-resources
        phase process-resources
        phase compile
        phase process-classes
        phase generate-test-sources
        phase process-test-sources
        phase generate-test-resources
        phase process-test-resources
        phase test-compile
        phase process-test-classes
        phase test
        phase prepare-package
        phase package
        phase pre-integration-test
        phase integration-test
        phase post-integration-test
        phase verify
        phase install

        # build-only plugin goals
        plugin clean org.apache.maven.plugins:maven-clean-plugin clean
        plugin compiler org.apache.maven.plugins:maven-compiler-plugin compile,testCompile
        plugin resources org.apache.maven.plugins:maven-resources-plugin resources,testResources
        plugin surefire org.apache.maven.plugins:maven-surefire-plugin test
        plugin failsafe org.apache.maven.plugins:maven-failsafe-plugin integration-test,verify
        plugin jar org.apache.maven.plugins:maven-jar-plugin jar,test-jar
        plugin source org.apache.maven.plugins:maven-source-plugin jar,test-jar,jar-no-fork,test-jar-no-fork
        plugin javadoc org.apache.maven.plugins:maven-javadoc-plugin jar,test-jar,javadoc,test-javadoc
        plugin install org.apache.maven.plugins:maven-install-plugin install
        plugin dependency org.apache.maven.plugins:maven-dependency-plugin tree,list,resolve,resolve-plugins,go-offline,analyze,analyze-only,build-classpath,sources,properties
        plugin help org.apache.maven.plugins:maven-help-plugin effective-pom,active-profiles,all-profiles
        plugin enforcer org.apache.maven.plugins:maven-enforcer-plugin enforce,display-info
        plugin jbct org.pragmatica-lite:jbct-maven-plugin check,format,format-check,lint,score
        plugin jacoco org.jacoco:jacoco-maven-plugin prepare-agent,prepare-agent-integration,report,report-integration,report-aggregate,check,merge
        plugin pitest org.pitest:pitest-maven mutationCoverage,report,report-aggregate,report-aggregate-module

        # -P profiles of the repositories temper builds: temper's own acceptance tier, pragmatica's with-e2e
        profile acceptance
        profile with-e2e

        # -D properties the user may set: exact names; skip and selector ones are marked on the job's result
        property skipTests skip
        property maven.test.skip skip
        property skipITs skip
        property ember.skipTests skip
        property test selector
        property it.test selector
        property groups selector
        property excludedGroups selector
        property targetClasses selector
        property targetTests selector
        property failIfNoSpecifiedTests boolean
        property surefire.failIfNoSpecifiedTests boolean
        property it.failIfNoSpecifiedTests boolean

        # options the user may write, with the shape of their value
        option -B --batch-mode none
        option -e --errors none
        option -X --debug none
        option -q --quiet none
        option -o --offline none
        option -U --update-snapshots none
        option -V --show-version none
        option -nsu --no-snapshot-updates none
        option -npu --no-plugin-updates none
        option -C --strict-checksums none
        option -c --lax-checksums none
        option -am --also-make none
        option -amd --also-make-dependents none
        option -fae --fail-at-end none
        option -ff --fail-fast none
        option -T --threads threads
        option -pl --projects modules narrows
        option -rf --resume-from module narrows
        option -f --file file narrows
        option -N --non-recursive none narrows
        option -P --activate-profiles profiles
        option -D --define define
        """;

    private static final Pattern PHASE = Pattern.compile("phase ([a-z][a-z-]*)");
    private static final Pattern PROFILE = Pattern.compile("profile ([A-Za-z0-9][A-Za-z0-9._-]*)");

    private static final Pattern PROPERTY = Pattern.compile("property ([A-Za-z][A-Za-z0-9._-]*) (skip|boolean|selector)");

    private static final Pattern OPTION = Pattern.compile("option (-[A-Za-z]+) (--[a-z]+(?:-[a-z]+)*) (none|threads|modules|module|file|profiles|define)(| narrows)");

    private static final Pattern PLUGIN = Pattern.compile("plugin ([a-z][a-z0-9-]*) ([A-Za-z0-9._-]+:[A-Za-z0-9._-]+) ([A-Za-z][A-Za-z0-9-]*(?:,[A-Za-z][A-Za-z0-9-]*)*)");

    public SideEffectAllowlist {
        phases = Set.copyOf(phases);
        plugins = List.copyOf(plugins);
        profiles = Set.copyOf(profiles);
        properties = List.copyOf(properties);
        options = List.copyOf(options);
    }

    /// One parsed line.
    sealed interface Entry {}

    record Phase(String name) implements Entry {}

    record Profile(String name) implements Entry {}

    /// A build-only plugin: its prefix, its `groupId:artifactId`, and the goals allowed.
    public record Plugin(String prefix, String coordinates, Set<String> goals) implements Entry {
        public Plugin {
            goals = Set.copyOf(goals);
        }
    }

    /// What a listed `-D` key may take and does: `SKIP` and `BOOLEAN` take `true`/`false` (or no value), `SKIP` skipping
    /// tests when true; `SELECTOR` takes any value and narrows the tests run.
    public enum PropertyKind {
        SKIP,
        BOOLEAN,
        SELECTOR
    }

    /// A `-D` key the user may set, by exact name.
    public record Property(String name, PropertyKind kind) implements Entry {}

    /// What an option's value is: `NONE` takes none; the others take the next argv element, `DEFINE` and `PROFILES` also
    /// attached to the short form (`-Dk=v`, `-Pname`). `THREADS` is an integer or `<n>C`; `MODULES` a comma list of module
    /// selectors, each optionally `!`-prefixed; `MODULE` one selector; `FILE` a workspace-relative path; `PROFILES` and
    /// `DEFINE` are judged by the profile and property entries.
    public enum ValueShape {
        NONE,
        THREADS,
        MODULES,
        MODULE,
        FILE,
        PROFILES,
        DEFINE
    }

    /// An option the user may write (maintainer ruling Q-M4-11d): its exact short and long spellings, its value's shape, and
    /// whether it narrows the reactor (marked on the job's result).
    public record MvnOption(String shortName, String longName, ValueShape shape, boolean narrows) implements Entry {}

    /// A line of the list that is neither blank, a `#` comment, nor one of the entry forms.
    record MalformedLine(String line, String message) implements Cause, Cause.Terminal {
        static final Fn1<MalformedLine, String> FACTORY = Causes.forOneValue("Malformed allowlist line '%s'",
                                                                             MalformedLine::new);
    }

    /// The shipped list, parsed.
    public static Result<SideEffectAllowlist> shipped() {
        return sideEffectAllowlist(TEXT.lines().toList());
    }

    /// Lines are `phase <name>`, `plugin <prefix> <groupId:artifactId> <goal,...>`, `profile <name>`,
    /// `property <name> skip|boolean|selector`, `option <-short> <--long> <shape> [narrows]`, fields separated by
    /// any whitespace; blank lines and `#` comment lines are skipped. Any other line fails the parse.
    public static Result<SideEffectAllowlist> sideEffectAllowlist(List<String> lines) {
        return Result.allOf(lines.stream()
                                 .map(line -> String.join(" ",
                                                          line.strip().split("\\s+")))
                                 .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                                 .map(SideEffectAllowlist::entry)).map(SideEffectAllowlist::fromEntries);
    }

    private static Result<Entry> entry(String line) {
        return match(PHASE, line).<Entry> map(match -> new Phase(match.group(1)))
                    .orElse(() -> match(PROFILE, line).map(match -> new Profile(match.group(1))))
                    .orElse(() -> match(PLUGIN, line).map(match -> new Plugin(match.group(1),
                                                                              match.group(2),
                                                                              Set.copyOf(Arrays.asList(match.group(3)
                                                                                                            .split(","))))))
                    .orElse(() -> match(PROPERTY, line).map(match -> new Property(match.group(1),
                                                                                  PropertyKind.valueOf(match.group(2)
                                                                                                            .toUpperCase(Locale.ROOT)))))
                    .orElse(() -> match(OPTION, line).map(match -> new MvnOption(match.group(1),
                                                                                 match.group(2),
                                                                                 ValueShape.valueOf(match.group(3)
                                                                                                         .toUpperCase(Locale.ROOT)),
                                                                                 !match.group(4).isEmpty())))
                    .toResult(MalformedLine.FACTORY.apply(line));
    }

    /// The match of `line` when the whole line matches `form`; every group is mandatory but an option's `narrows`.
    private static Option<MatchResult> match(Pattern form, String line) {
        var matcher = form.matcher(line);

        return matcher.matches()
               ? Option.some(matcher.toMatchResult())
               : Option.none();
    }

    private static SideEffectAllowlist fromEntries(List<Entry> entries) {
        return new SideEffectAllowlist(only(entries, Phase.class).map(Phase::name).collect(Collectors.toSet()),
                                       only(entries, Plugin.class).toList(),
                                       only(entries, Profile.class).map(Profile::name).collect(Collectors.toSet()),
                                       only(entries, Property.class).toList(),
                                       only(entries, MvnOption.class).toList());
    }

    private static <T extends Entry> Stream<T> only(List<Entry> entries, Class<T> kind) {
        return entries.stream()
                      .filter(kind::isInstance)
                      .map(kind::cast);
    }
}
