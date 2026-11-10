package org.pragmatica.temper.domain.manifest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;

/// Test fixture: effective-POM modules as [org.pragmatica.temper.domain.manifest.read.EffectivePom] yields them.
final class Poms {
    private Poms() {}

    static PomModule module(String path, Map<String, String> properties, PluginUse... plugins) {
        return PomModule.pomModule("org.acme:" + path.replace('/', '-'),
                                   path,
                                   "jar",
                                   path + "/target/test-classes",
                                   path + "/target/classes",
                                   path + "/src/test/java",
                                   properties,
                                   List.of(plugins));
    }

    static PomModule module(String path, PluginUse... plugins) {
        return module(path, Map.of(), plugins);
    }

    /// The plugin version every helper declares unless given one: the oracle fixture's default.
    static final String VERSION = "3.5.4";

    /// Surefire bound as the jar lifecycle binds it: `default-test`, phase `test`.
    static PluginUse surefire(Map<String, ConfigValue> config) {
        return surefireAt(VERSION, config);
    }

    static PluginUse surefireAt(String version, Map<String, ConfigValue> config) {
        return PluginUse.pluginUse(Runner.SUREFIRE,
                                   Option.some(version),
                                   List.of(Execution.execution("default-test", Option.some("test"), List.of("test"), config)));
    }

    static PluginUse surefireAt(String version) {
        return surefireAt(version, Map.of());
    }

    static PluginUse surefire() {
        return surefire(Map.of());
    }

    /// Failsafe as a POM binds it: no phase (the goals' defaults), `integration-test` and `verify`.
    static PluginUse failsafe(Map<String, ConfigValue> config) {
        return failsafeAt(VERSION, config);
    }

    static PluginUse failsafeAt(String version, Map<String, ConfigValue> config) {
        return PluginUse.pluginUse(Runner.FAILSAFE,
                                   Option.some(version),
                                   List.of(Execution.execution("default",
                                                               Option.none(),
                                                               List.of("integration-test", "verify"),
                                                               config)));
    }

    static PluginUse failsafe() {
        return failsafe(Map.of());
    }

    static PluginUse failsafeAt(String version) {
        return failsafeAt(version, Map.of());
    }

    static PluginUse plugin(Runner runner, Execution... executions) {
        return PluginUse.pluginUse(runner, Option.some(VERSION), List.of(executions));
    }

    /// Text configuration from `name, value, name, value, ...`.
    static Map<String, ConfigValue> config(String... pairs) {
        var config = new HashMap<String, ConfigValue>();
        for (var i = 0; i < pairs.length; i += 2) {
            config.put(pairs[i], ConfigValue.Text.text(pairs[i + 1]));
        }
        return config;
    }

    static Map<String, ConfigValue> items(String name, String... values) {
        return Map.of(name, ConfigValue.Items.items(List.of(values)));
    }

    static ManifestSpec spec(Reach reach, Map<String, String> userProperties) {
        return ManifestSpec.manifestSpec(List.of("mvn"), reach, userProperties, Option.none(), Option.none());
    }

    static ManifestSpec spec(Reach reach) {
        return spec(reach, Map.of());
    }

    static ManifestSpec install() {
        return spec(Reach.INTEGRATION_TEST);
    }
}
