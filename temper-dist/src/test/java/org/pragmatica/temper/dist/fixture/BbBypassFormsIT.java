package org.pragmatica.temper.dist.fixture;

import java.util.List;
import java.util.Map;

import org.pragmatica.temper.testsupport.HermeticProcess;


/// FIXTURE for `BbHostIsolationIT` (issue #56): every way a Bb*IT could start a child without `BbFixtures.process`. Never run; the guard
/// must flag each method here. It lives in its own package so the guard's scan of the real Bb*ITs does not see it.
final class BbBypassFormsIT {
    private BbBypassFormsIT() {}

    static Object builderVarargs() {
        return HermeticProcess.builder("true");
    }

    static Object builderList(List<String> command) {
        return HermeticProcess.builder(command);
    }

    static Object builderListEnv(List<String> command) {
        return HermeticProcess.builder(command, Map.of("A", "b"));
    }

    static Object prepared(ProcessBuilder builder) {
        return HermeticProcess.prepared(builder, Map.of());
    }

    static void environmentPut(ProcessBuilder builder) {
        builder.environment().put("A", "b");
    }

    static void environmentPutAll(ProcessBuilder builder) {
        builder.environment().putAll(Map.of());
    }

    static Runnable insideALambda(ProcessBuilder builder) {
        return () -> builder.environment()
                            .clear();
    }
}
