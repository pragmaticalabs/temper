package org.pragmatica.temper.domain.refusal;

import org.pragmatica.lang.Option;


/// Tree-specific refusals, held as rows of the `refusals` table so that a refusal ends when its issue does
/// (spec §2.5). Structural refusals are code in [Refusals].
public sealed interface RefusalRule {
    /// The row's code, e.g. `R-1553`.
    String code();

    /// R-1553 (Q-3 ruling): refuse a unit whose selection COULD run `simpleClassName` while the tree's class
    /// is not `@Disabled("<disabledMarker>")`. `runner` names the plugin that runs the class; when absent,
    /// both plugins' selections are judged (the conservative reading).
    record ExcludeTestClass(String code, String simpleClassName, String disabledMarker, Option<TestRunner> runner) implements RefusalRule {
        public static ExcludeTestClass excludeTestClass(String code,
                                                        String simpleClassName,
                                                        String disabledMarker,
                                                        Option<TestRunner> runner) {
            return new ExcludeTestClass(code, simpleClassName, disabledMarker, runner);
        }
    }

    /// R-FAILSAFE: a selection of the module `moduleSegment` with `-Dtest` but no `-Dit.test` runs every IT.
    record ItTestRequired(String code, String moduleSegment) implements RefusalRule {
        public static ItTestRequired itTestRequired(String code, String moduleSegment) {
            return new ItTestRequired(code, moduleSegment);
        }
    }
}
