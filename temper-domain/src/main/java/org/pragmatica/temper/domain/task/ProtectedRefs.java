package org.pragmatica.temper.domain.task;

import java.util.List;

import org.pragmatica.temper.domain.model.Ref;


/// Branch patterns a task may never name as its `branch` (m3-design §5.4 [P]): a name, or a prefix ending in `*`.
/// [#DEFAULT] is the design's list (`main`, `master`, `release*`, `archive/*`); the `[pipeline] protectedRefs` key that
/// makes it data is not read yet [G].
public record ProtectedRefs(List<String> patterns) {
    public static final ProtectedRefs DEFAULT = new ProtectedRefs(List.of("main", "master", "release*", "archive/*"));

    public ProtectedRefs {
        patterns = List.copyOf(patterns);
    }

    public static ProtectedRefs protectedRefs(List<String> patterns) {
        return new ProtectedRefs(patterns);
    }

    public boolean isProtected(Ref branch) {
        return patterns.stream()
                       .anyMatch(pattern -> isMatch(pattern,
                                                    branch.value()));
    }

    private static boolean isMatch(String pattern, String name) {
        return pattern.endsWith("*")
               ? name.startsWith(pattern.substring(0, pattern.length() - 1))
               : name.equals(pattern);
    }
}
