package org.pragmatica.temper.worker.docker;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/// Enables a test class only when `docker info` answers within 15 s (checked once per JVM).
public final class DockerAvailable implements ExecutionCondition {
    private static volatile Boolean available;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return available()
               ? ConditionEvaluationResult.enabled("docker answers")
               : ConditionEvaluationResult.disabled("no docker daemon: docker-tagged test skipped");
    }

    public static boolean available() {
        if (available == null) {
            available = probe();
        }
        return available;
    }

    private static boolean probe() {
        try {
            var process = HermeticProcess.builder("docker", "info", "-f", "{{.ServerVersion}}").redirectErrorStream(true)
                                                                                         .start();

            process.getInputStream()
                   .transferTo(java.io.OutputStream.nullOutputStream());
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
