package org.pragmatica.temper.domain.manifest;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// Every class the reactor compiled (`test-classes` and `classes` of each module), by binary name. The rules
/// resolve superclasses, interfaces and meta-annotations through it; a name it lacks came from a jar.
public record ClassIndex(Map<String, ClassInfo> byName) {
    public ClassIndex {
        byName = Map.copyOf(byName);
    }

    /// Indexes `classes` by binary name; a name compiled twice keeps its first occurrence.
    public static ClassIndex classIndex(Collection<ClassInfo> classes) {
        return new ClassIndex(classes.stream()
                                     .collect(Collectors.toMap(ClassInfo::binaryName,
                                                               Function.identity(),
                                                               (first, _) -> first)));
    }

    public Option<ClassInfo> find(String binaryName) {
        return option(byName.get(binaryName));
    }
}
