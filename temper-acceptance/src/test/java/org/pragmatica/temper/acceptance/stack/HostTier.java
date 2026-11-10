package org.pragmatica.temper.acceptance.stack;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/// Enables a host-tier class only with the explicit host flag `-Dtemper.host=<name>` (README.md "Host tier"); the
/// `host` tag already keeps it out of every other run, and this keeps it out even when the tag filter is dropped.
public final class HostTier implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        var host = System.getProperty("temper.host");

        return host == null || host.isBlank()
               ? ConditionEvaluationResult.disabled("host tier: -Dtemper.host is not set")
               : ConditionEvaluationResult.enabled("host tier on " + host);
    }
}
