package org.pragmatica.temper.controller.git;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.mvn.MvnStep;
import org.pragmatica.temper.domain.mvn.ReactorModules;
import org.pragmatica.temper.domain.mvn.read.ReactorPoms;

import static org.pragmatica.lang.Option.some;


/// Reading a tree's reactor (m1-design §1.6) one breadth-first level per round, over the poms fetched so far. Pure.
///
/// [ReactorPoms#parse] reads poms synchronously, while git answers asynchronously, so the reader runs it over a
/// map: a module whose pom has not been fetched yet reads as an empty `<project/>` and is reported [Missing]; the
/// caller fetches every missing pom of that level and tries again. A round discovers the whole next level, so the
/// rounds are as many as the reactor is deep. A module fetched as absent (none) fails the parse, which makes the
/// reactor unknown: the conservative M0 judgement.
sealed interface ReactorRead {
    /// Every pom the reactor needs was available; the reactor, or why it could not be read.
    record Done(Result<ReactorModules> reactor) implements ReactorRead {}

    /// Modules whose poms must be fetched before the next attempt.
    record Missing(Set<String> modules) implements ReactorRead {}

    byte[] EMPTY_POM = "<project/>".getBytes(StandardCharsets.UTF_8);

    /// `poms` maps a module path (`""` is the root) to its pom bytes, or none when the tree has no such pom.
    static ReactorRead attempt(Map<String, Option<byte[]>> poms) {
        var missing = ConcurrentHashMap.<String> newKeySet();
        var reactor = ReactorPoms.parse(module -> lookup(poms, missing, module));

        return missing.isEmpty()
               ? new Done(reactor)
               : new Missing(Set.copyOf(missing));
    }

    /// The precondition [ReactorModules] puts on its caller: Maven prepends `.mvn/maven.config` to every argv, so a
    /// tree whose config adds `-f`, `-P` or `-pl` builds another reactor than its root pom describes. An absent file
    /// adds nothing. The config is read with Maven's own whitespace split, and judged by the step parser every rule
    /// uses.
    static boolean buildsDefaultReactor(Option<String> mavenConfig) {
        return mavenConfig.map(ReactorRead::configStep)
                          .map(ReactorRead::addsNoSelection)
                          .or(true);
    }

    /// The `pom.xml` path of a module directory.
    static String pomPath(String module) {
        return module.isEmpty()
               ? "pom.xml"
               : module + "/pom.xml";
    }

    private static MvnStep configStep(String config) {
        return MvnStep.mvnStep(List.of(("mvn " + config.strip()).split("\\s+")));
    }

    private static boolean addsNoSelection(MvnStep step) {
        return Stream.of(step.pomFiles(),
                         step.profiles(),
                         step.projects())
                     .allMatch(List::isEmpty);
    }

    private static Option<byte[]> lookup(Map<String, Option<byte[]>> poms, Set<String> missing, String module) {
        return poms.containsKey(module)
               ? poms.get(module)
               : placeholder(missing, module);
    }

    private static Option<byte[]> placeholder(Set<String> missing, String module) {
        missing.add(module);

        return some(EMPTY_POM);
    }
}
