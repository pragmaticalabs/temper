package org.pragmatica.temper.domain.mvn;

import java.util.Set;
import java.util.stream.Collectors;


/// The modules of the reactor that a step without `-f` and `-P` builds (m1-design §1.6, Q-M1-4): the root pom's
/// `<modules>`, recursively through aggregator poms, plus the modules of every `<profile>` that has an
/// `<activation>` element (conservative: such a profile may be active without `-P`). Paths are
/// repository-relative module directories; `""` is the root project, which is always a member.
///
/// Read from the tree by `mvn.read.ReactorPoms`; an unknown reactor is `none()` at every use, which keeps the
/// conservative M0 answer. Precondition on the caller: a tree whose `.mvn/maven.config` carries `-f`, `-P` or
/// `-pl` builds a different reactor than its root pom describes, so its reactor is passed as unknown.
public record ReactorModules(Set<String> paths) {
    public ReactorModules {
        paths = paths.stream().map(ModuleMatch::normalize).collect(Collectors.toUnmodifiableSet());
    }

    public static ReactorModules reactorModules(Set<String> paths) {
        return new ReactorModules(paths);
    }

    /// Whether the module at `modulePath` (repository-relative directory) is in the reactor.
    public boolean contains(String modulePath) {
        return paths.contains(ModuleMatch.normalize(modulePath));
    }

    /// Whether some module of the reactor has the directory name `segment` (R-FAILSAFE's `moduleSegment`).
    public boolean hasSegment(String segment) {
        return paths.stream()
                    .map(ModuleMatch::lastSegment)
                    .anyMatch(segment::equals);
    }
}
