package org.pragmatica.temper.domain.manifest;

import java.util.List;

import org.pragmatica.lang.Option;


/// A test plugin a project declares in `<build><plugins>`, with its version (absent when the effective POM gives
/// none) and its executions. The version decides the reported class name ([ReportNaming]).
public record PluginUse(Runner runner, Option<String> version, List<Execution> executions) {
    public PluginUse {
        executions = List.copyOf(executions);
    }

    public static PluginUse pluginUse(Runner runner, Option<String> version, List<Execution> executions) {
        return new PluginUse(runner, version, executions);
    }
}
