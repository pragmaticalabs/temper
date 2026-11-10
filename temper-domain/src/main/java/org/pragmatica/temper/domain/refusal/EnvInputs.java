package org.pragmatica.temper.domain.refusal;

import org.pragmatica.lang.Option;


/// The values a unit container's environment is built from. Each field is one allow-listed key, so a value
/// that is not here (a host `*_TOKEN`, a request field) has no way into the container.
public record EnvInputs(String javaHome,
                        String path,
                        String home,
                        String testcontainersHostOverride,
                        Option<String> mavenOpts,
                        Option<String> testcontainersHubImagePrefix) {
    public static EnvInputs envInputs(String javaHome,
                                      String path,
                                      String home,
                                      String testcontainersHostOverride,
                                      Option<String> mavenOpts,
                                      Option<String> testcontainersHubImagePrefix) {
        return new EnvInputs(javaHome, path, home, testcontainersHostOverride, mavenOpts, testcontainersHubImagePrefix);
    }
}
