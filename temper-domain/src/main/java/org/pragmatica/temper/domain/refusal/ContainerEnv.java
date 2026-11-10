package org.pragmatica.temper.domain.refusal;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// R-ENV structurally (spec §2.5): the container environment is an explicit allow-list. `build` can only
/// emit [#ALLOWED] keys, whatever the host environment holds; `filter` applies the same list to a full
/// environment (the in-image launcher does the equivalent, because docker cannot unset an image `ENV`).
public sealed interface ContainerEnv {
    Set<String> ALLOWED = Set.of("JAVA_HOME",
                                 "MAVEN_OPTS",
                                 "TESTCONTAINERS_HOST_OVERRIDE",
                                 "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX",
                                 "HOME",
                                 "PATH",
                                 "TZ");

    /// The unit container's environment. `TZ` is always `UTC`; optional values are omitted when absent.
    static Map<String, String> build(EnvInputs in) {
        return Stream.of(Stream.of(Map.entry("JAVA_HOME",
                                             in.javaHome()),
                                   Map.entry("PATH",
                                             in.path()),
                                   Map.entry("HOME",
                                             in.home()),
                                   Map.entry("TZ", "UTC"),
                                   Map.entry("TESTCONTAINERS_HOST_OVERRIDE",
                                             in.testcontainersHostOverride())),
                         optional("MAVEN_OPTS",
                                  in.mavenOpts()),
                         optional("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX",
                                  in.testcontainersHubImagePrefix()))
                     .flatMap(Function.identity())
                     .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Stream<Map.Entry<String, String>> optional(String key, Option<String> value) {
        return value.map(present -> Map.entry(key, present))
                    .stream();
    }

    /// Keeps only the allow-listed keys of `environment`.
    static Map<String, String> filter(Map<String, String> environment) {
        return environment.entrySet()
                          .stream()
                          .filter(allowed -> ALLOWED.contains(allowed.getKey()))
                          .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    record unused() implements ContainerEnv {}
}
