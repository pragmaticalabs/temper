package org.pragmatica.temper.domain.manifest;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;


/// What the worker needs to compute a unit's manifest (m1-design §2.2), produced by the planner.
///
/// - `pomArgv`: the effective-POM step, `mvn -B` [#EFFECTIVE_POM_GOAL] `-Doutput=…` plus the main step's
///   reactor-selection arguments (`-pl`, `-am`, `-amd`, `-P`, `-D`, `-f`) and the forced repo-local token.
/// - `reach`: the furthest test-bearing phase the main step reaches.
/// - `userProperties`: the main step's `-D` properties (the last occurrence of each key).
/// - `test` / `itTest`: the main step's `-Dtest` / `-Dit.test`, which replace a plugin's includes and excludes.
public record ManifestSpec(List<String> pomArgv,
                           Reach reach,
                           Map<String, String> userProperties,
                           Option<String> test,
                           Option<String> itTest) {
    /// The pinned help-plugin goal. 3.5.1 writes a whole reactor into the single `-Doutput` file, inside a
    /// `<projects>` wrapper (verified 2026-10-13 against temper's own reactor).
    public static final String EFFECTIVE_POM_GOAL = "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom";

    public ManifestSpec {
        pomArgv = List.copyOf(pomArgv);
        userProperties = Map.copyOf(userProperties);
    }

    public static ManifestSpec manifestSpec(List<String> pomArgv,
                                            Reach reach,
                                            Map<String, String> userProperties,
                                            Option<String> test,
                                            Option<String> itTest) {
        return new ManifestSpec(pomArgv, reach, userProperties, test, itTest);
    }
}
