"""build_rules -- what ops/dev/build-args (on the command line and its channels) and ops/dev/build-report (on the
system properties a test JVM recorded in its surefire/failsafe XML) make of a property: one list, two readers.
Not run on its own; imported by both from this directory.
"""
import os
import re
import xml.etree.ElementTree as ET

# property name prefix -> why it is refused
REFUSED_PROPERTIES = (
    ("jbct.skip", "the jbct gate examines nothing"),
    ("maven.test.failure.ignore", "a test failure exits 0"),
    ("surefire.skip", "tests silently not run"),
    ("skipITs", "integration tests silently not run"),
    ("maven.main.skip", "main sources are not compiled: tests run against stale target/classes"),
    ("maven.resources.skip", "resources are not copied: tests run against stale target/classes"),
    ("maven.compiler.failOnError", "a compile error does not fail the build"),
    ("surefire.rerunFailingTestsCount", "a test that fails and then passes on a rerun counts as passed"),
    ("failsafe.rerunFailingTestsCount", "a test that fails and then passes on a rerun counts as passed"),
)
# properties and profiles that change nothing about what a run tests -> why (anything else -D / -P is select_other)
ALLOWED_PROPERTIES = {
    "maven.repo.local": "where artifacts resolve, and only as <tree>/.m2-local (judge() refuses any other path)",
    "skipTests": "judged by build-report: the verdict is COMPILE-ONLY",
    "maven.test.skip": "judged by build-report: the verdict is COMPILE-ONLY",
}
ALLOWED_PROFILES = {
    "acceptance": "adds temper-acceptance to the reactor (root pom); selects nothing in the other modules",
}

# exact property names that select tests (surefire, failsafe, and this repo's acceptance groups)
TEST_SELECTION = {
    "test", "it.test", "groups", "excludedGroups", "surefire.failIfNoSpecifiedTests",
    "failsafe.failIfNoSpecifiedTests", "surefire.includesFile", "surefire.excludesFile", "failsafe.includesFile",
    "failsafe.excludesFile", "surefire.includeJUnit5Engines", "surefire.excludeJUnit5Engines",
    "failsafe.includeJUnit5Engines", "failsafe.excludeJUnit5Engines", "acceptance.groups",
    "acceptance.excludedGroups",
}
# surefire/failsafe parameter names a profile's <properties> can set directly (besides TEST_SELECTION): a profile
# setting one selects tests, whatever activates it
PROFILE_TEST_SELECTION = ("includes", "excludes")



def local(tag):
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


def text_of(element, name):
    return next(((c.text or "").strip() for c in element if local(c.tag) == name), "")


class Reactor:
    """What the poms of the reactor (root and every module it or a profile names) make of properties and profiles."""
    PLACEHOLDER = re.compile(r"\$\{([^}]+)\}")
    BUILT_IN = re.compile(r"^(project|pom|settings|env|basedir|maven)\b")
    ENV = re.compile(r"^env\.(.+)$")
    ARG_LINE_PROPERTY = re.compile(r"(?:^|\s)-D([^=\s]+)")

    def __init__(self, root):
        self.test_properties = set()
        self.jvm_set = set()  # names the poms' own systemPropertyVariables / argLine give a test JVM
        self.activations = {}  # property -> profile it activates (only profiles that select tests)
        self.env_activations = []  # (variable, profile, condition, holds(value or None)): env.* activations (test-selecting only)
        self.standing = {}  # profile id -> its activation that can hold with nothing in the run naming it (test-selecting only)
        self.test_profiles = {}  # profile id -> why it selects tests
        self.readers = {}  # property -> the value each pom whose surefire/failsafe configuration reads it declares
        self.env_reads = set()  # environment variables test configuration reads (${env.X}, also through a property)
        self.profile_ids = set()  # every profile id the reactor poms declare
        poms, aggregators, parents = self.reactor_poms(root)
        reads = [self.read_by_tests(pom) for pom in poms]
        for pom in poms:
            for plugin in self.test_plugins(pom):
                for e in plugin.iter():
                    if local(e.tag) in ("systemPropertyVariables", "systemProperties"):
                        self.jvm_set.update(text_of(p, "name") if local(p.tag) == "property" else local(p.tag)
                                            for p in e if local(p.tag))
                    elif local(e.tag) == "argLine":
                        self.jvm_set.update(self.ARG_LINE_PROPERTY.findall(e.text or ""))
        self.test_properties = set().union(*reads) if reads else set()
        effective, inherited = [], []
        for index, pom in enumerate(poms):
            # a module's effective value: its own <properties>, else its parent's (the aggregator naming it, when it
            # has a <parent>); every aggregator's surefire/failsafe configuration above it is counted as reaching it
            # (with or without <parent>: more readers make a declared value harder to claim, never easier)
            aggregator = aggregators[index]
            effective.append(dict(effective[aggregator]) if parents[index] else {})
            effective[index].update(self.declared(pom))
            inherited.append(reads[index] | (inherited[aggregator] if aggregator is not None else set()))
            for name in inherited[index]:
                self.readers.setdefault(name, []).append(effective[index].get(name))
                self.env_reads.update(self.env_names(effective[index].get(name) or ""))
        for pom in poms:
            for profile in (e for e in pom.iter() if local(e.tag) == "profile"):
                profile_id = text_of(profile, "id")
                self.profile_ids.add(profile_id)
                sets = [local(p.tag) for e in profile if local(e.tag) == "properties" for p in e
                        if local(p.tag) in self.test_properties or selects_tests(local(p.tag))]
                if any(True for _ in self.test_plugins(profile)):
                    self.test_profiles[profile_id] = "configures surefire/failsafe"
                elif sets:
                    self.test_profiles[profile_id] = "sets " + ", ".join(sets)
                else:
                    continue
                for activation in (e for e in profile if local(e.tag) == "activation"):
                    for condition in (e for e in activation if local(e.tag)):
                        self.activation(profile_id, condition)

    def activation(self, profile_id, condition):
        """Record one activation condition of a test-selecting profile: a property one the run sets, an env.* one the
        environment decides; any other (file, os, jdk, activeByDefault true, a negated or JVM-defined property, ...) can
        hold with nothing in the run naming it and goes to `standing`, refused whether it holds now or not."""
        kind, text = local(condition.tag), (condition.text or "").strip()
        if kind == "activeByDefault" and text != "true":
            return
        if kind != "property":
            inner = text or "".join("<%s>%s</%s>" % (local(e.tag), (e.text or "").strip(), local(e.tag))
                                    for e in condition if local(e.tag))
            self.standing.setdefault(profile_id, "<%s>%s</%s>" % (kind, inner, kind))
            return
        name, value = text_of(condition, "name"), text_of(condition, "value")
        shown = "%s%s" % (name, "=" + value if value else "")
        if name.startswith("!env.") or name.startswith("env."):
            negated = name.startswith("!")
            expected = value[1:] if value.startswith("!") else None
            # a condition holds: !env.X while X is unset; env.X=!v while X is not v (unset too); env.X (any value) while
            # X is set, even empty -- a value it does not name is refused as well (fail-safe)
            holds = ((lambda current: current is None) if negated
                     else (lambda current: current != expected) if expected is not None
                     else (lambda current: current is not None))
            self.env_activations.append((name.lstrip("!")[4:], profile_id, shown, holds))
        elif name.startswith("!") or value.startswith("!") or name.startswith(JVM_DEFINED):
            self.standing.setdefault(profile_id, "<property>%s</property>" % shown)
        elif name:
            self.activations[name] = profile_id

    def reactor_poms(self, root):
        """The root pom and, recursively, every pom a <module> of an aggregator names (profiles' modules too); with each,
        the index of the aggregator naming it (None for the root) and whether it declares a <parent> (taken to be that
        aggregator)."""
        poms, aggregators, parents, pending, seen = [], [], [], [(root, None)], set()
        while pending:
            directory, aggregator = pending.pop(0)
            directory = os.path.normpath(directory)
            pom = None if directory in seen else self.parse(os.path.join(directory, "pom.xml"))
            seen.add(directory)
            if pom is not None:
                aggregators.append(aggregator)
                parents.append(aggregator is not None and any(local(e.tag) == "parent" for e in pom))
                poms.append(pom)
                pending += [(os.path.join(directory, m.text.strip()), len(poms) - 1) for m in pom.iter()
                            if local(m.tag) == "module" and m.text]
        return poms, aggregators, parents

    def read_by_tests(self, pom):
        """The properties surefire/failsafe configuration in this pom (profiles and pluginManagement too) reads."""
        found = set()
        for plugin in self.test_plugins(pom):
            for text in (e.text or "" for e in plugin.iter()):
                found.update(n for n in self.PLACEHOLDER.findall(text) if not self.BUILT_IN.match(n))
                self.env_reads.update(self.env_names(text))
        return found

    def env_names(self, text):
        return [m.group(1) for m in map(self.ENV.match, self.PLACEHOLDER.findall(text)) if m]

    @staticmethod
    def declared(pom):
        """The <properties> a pom declares outside its profiles."""
        return dict((local(p.tag), (p.text or "").strip())
                    for e in pom if local(e.tag) == "properties" for p in e if isinstance(p.tag, str))

    def is_declared(self, name, value):
        """`value` is what every pom that reads `name` declares as its effective value: a run as without it."""
        values = self.readers.get(name, [])
        return bool(values) and all(v == value for v in values)

    @staticmethod
    def parse(path):
        try:
            return ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            return None

    @staticmethod
    def test_plugins(element):
        return (p for p in element.iter() if local(p.tag) == "plugin"
                and re.search("surefire|failsafe", text_of(p, "artifactId")))


# property names the JVM or maven defines in every run: an activation on one holds with nothing in the run naming it
JVM_DEFINED = ("java.", "os.", "user.", "file.", "line.", "path.", "sun.", "jdk.", "native.", "stdout.", "stderr.",
               "maven.", "library.")


# set in the environment (non-empty), each feeds maven or its JVM options build-args cannot read: refused
LAUNCHER_ENVIRONMENT = {
    "JDK_JAVA_OPTIONS": "the java launcher adds it to maven's JVM unseen here",
    "_JAVA_OPTIONS": "the java launcher adds it to maven's JVM unseen here",
    "MAVEN_DEBUG_OPTS": "the mvn script adds it to maven's JVM options unseen here",
    "MAVEN_BASEDIR": "the mvn script reads that directory's .mvn/maven.config and jvm.config in place of the tree's",
}


def selects_tests(name):
    """A property name that, set by a profile, selects tests or hides what the run did."""
    return (name in TEST_SELECTION or name in PROFILE_TEST_SELECTION or name.startswith("skip")
            or any(name.startswith(prefix) for prefix, _ in REFUSED_PROPERTIES))


def repo_ok(value, tree):
    """maven.repo.local names the tree's own repo, <tree>/.m2-local, itself: not a symlink to elsewhere, no other path."""
    root = os.path.realpath(tree)
    return bool(value) and os.path.realpath(os.path.join(root, value)) == os.path.join(root, ".m2-local")


def judge(name, value, tree, reactor):
    """(kind, why) for one property: kind "refused", "tests" (selects tests), "other" (outside the allowlist) or None
    (allowed). `value` is None when the property has none (-Dname)."""
    for prefix, why in REFUSED_PROPERTIES:
        if name.startswith(prefix):
            return "refused", why
    if name == "maven.repo.local":
        if repo_ok(value, tree):
            return None, None
        return "refused", "resolves plugins and dependencies outside %s/.m2-local" % os.path.realpath(tree)
    if name in reactor.test_properties and value is not None and reactor.is_declared(name, value):
        return None, None  # the effective value of every module that reads it: as a run without it
    if name in TEST_SELECTION:
        return "tests", None
    if name in reactor.test_properties:
        return "tests", "is read by surefire/failsafe configuration"
    if name in reactor.activations:
        return "tests", "activates profile %s" % reactor.activations[name]
    if name in ALLOWED_PROPERTIES:
        return None, None
    return "other", None
