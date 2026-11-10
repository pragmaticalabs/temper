package org.pragmatica.temper.domain.plan;

import java.util.List;

import org.pragmatica.temper.domain.mvn.MvnStep;


/// Whether a unit requests tests (m0-design §3.1): some step reaches `test` (or names a surefire/failsafe
/// goal) without `-DskipTests`/`-Dmaven.test.skip`. Each step is judged alone. Module-specific skip
/// properties (`-Dember.skipTests`) need the effective POM and are ignored until M1.
public sealed interface TestRequest {
    static boolean requested(List<List<String>> steps) {
        return steps.stream()
                    .map(MvnStep::mvnStep)
                    .anyMatch(MvnStep::requestsTests);
    }

    record unused() implements TestRequest {}
}
