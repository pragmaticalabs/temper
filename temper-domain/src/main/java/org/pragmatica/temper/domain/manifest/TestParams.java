package org.pragmatica.temper.domain.manifest;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;


/// The resolved selection parameters of one test-plugin execution. When `test` is present it replaces
/// `includes` and `excludes` (surefire semantics), which then hold the values that would apply without it.
public record TestParams(List<String> includes,
                         List<String> excludes,
                         Set<String> groups,
                         Set<String> excludedGroups,
                         boolean skipped,
                         Option<String> test) {
    public TestParams {
        includes = List.copyOf(includes);
        excludes = List.copyOf(excludes);
        groups = Set.copyOf(groups);
        excludedGroups = Set.copyOf(excludedGroups);
    }

    public static TestParams testParams(List<String> includes,
                                        List<String> excludes,
                                        Set<String> groups,
                                        Set<String> excludedGroups,
                                        boolean skipped,
                                        Option<String> test) {
        return new TestParams(includes, excludes, groups, excludedGroups, skipped, test);
    }
}
