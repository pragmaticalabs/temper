package org.pragmatica.temper.acceptance.trust;

import java.util.Set;


/// What bb-report printed for one checkout (m1-design §8.1): the build's `rc`; the `<testcase>` elements in the
/// report XML newer than the run marker and their failures, errors and skips; the `tests=` attribute sum (the known
/// `@Nested` undercount, diagnostic only); the classes of the files with a failure or an error (`FAILED: <class>`,
/// named after the report file, so a `@Nested` failure names its outermost class); whether it was an EMPTY RUN
/// (exit 3); whether the log had dependency-resolution failures (modules never built or tested); and the run's
/// args words as bb-report echoes them on an EMPTY RUN (empty otherwise).
public record BbReport(int rc,
                       long testcases,
                       long failures,
                       long errors,
                       long skipped,
                       long testsAttr,
                       Set<String> failedClasses,
                       boolean empty,
                       boolean dependencyResolutionWarning,
                       Set<String> argsWords) {
    public BbReport {
        failedClasses = Set.copyOf(failedClasses);
        argsWords = Set.copyOf(argsWords);
    }

    public static BbReport bbReport(int rc,
                                    long testcases,
                                    long failures,
                                    long errors,
                                    long skipped,
                                    long testsAttr,
                                    Set<String> failedClasses,
                                    boolean empty,
                                    boolean dependencyResolutionWarning,
                                    Set<String> argsWords) {
        return new BbReport(rc,
                            testcases,
                            failures,
                            errors,
                            skipped,
                            testsAttr,
                            failedClasses,
                            empty,
                            dependencyResolutionWarning,
                            argsWords);
    }
}
