package org.pragmatica.temper.domain.manifest;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;


/// One plugin execution as the effective POM binds it. `config` is the plugin-level configuration merged
/// with the execution's own (the execution wins per parameter); `phase` is absent when the POM leaves it to
/// the goal's default.
public record Execution(String id, Option<String> phase, List<String> goals, Map<String, ConfigValue> config) {
    public Execution {
        goals = List.copyOf(goals);
        config = Map.copyOf(config);
    }

    public static Execution execution(String id,
                                      Option<String> phase,
                                      List<String> goals,
                                      Map<String, ConfigValue> config) {
        return new Execution(id, phase, goals, config);
    }
}
