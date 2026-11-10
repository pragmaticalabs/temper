package org.pragmatica.temper.acceptance.stack;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/// Enables a docker-tier class only when `docker info` answers within 15 s (checked once per JVM). A skip is
/// reported as skipped, never as passed.
public final class DockerAvailable implements ExecutionCondition {
    private static volatile Boolean available;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return available()
               ? ConditionEvaluationResult.enabled("docker answers")
               : ConditionEvaluationResult.disabled("no docker daemon: docker-tier test skipped");
    }

    static boolean available() {
        if (available == null) {
            available = probe();
        }
        return available;
    }

    private static boolean probe() {
        try {
            var process = HermeticProcess.builder("docker", "info", "-f", "{{.ServerVersion}}").redirectErrorStream(true).start();
            process.getInputStream().transferTo(OutputStream.nullOutputStream());
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
