package org.pragmatica.temper.domain.refusal;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.mvn.ReactorModules;

import static org.pragmatica.lang.Option.none;


/// Source facts at the job's SHA, read by the controller for the data-driven rules: simple class name to
/// every file that declares it, and the reactor the root pom builds (m1-design §1.6; none when it could not
/// be read, which keeps the conservative M0 judgement).
public record TreeFacts(Map<String, List<ClassSource>> sourcesByClass, Option<ReactorModules> reactor) {
    public static final TreeFacts EMPTY = new TreeFacts(Map.of(), none());

    public TreeFacts {
        sourcesByClass = sourcesByClass.entrySet()
                                       .stream()
                                       .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                             sources -> List.copyOf(sources.getValue())));
    }

    public static TreeFacts treeFacts(Map<String, List<ClassSource>> sourcesByClass, Option<ReactorModules> reactor) {
        return new TreeFacts(sourcesByClass, reactor);
    }

    /// Files declaring `simpleClassName`; empty when the tree has no such class (the rule then cannot apply).
    public List<ClassSource> sources(String simpleClassName) {
        return sourcesByClass.getOrDefault(simpleClassName, List.of());
    }
}
