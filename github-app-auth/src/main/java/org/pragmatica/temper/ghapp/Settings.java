package org.pragmatica.temper.ghapp;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// The one [AppAuthConfig]: package-private, with the canonical constructor reachable only from
/// [AppAuthConfig#appAuthConfig], which has validated every value. It keeps immutable copies of the collections.
record Settings(String identity,
                String clientId,
                long installationId,
                Path keyFile,
                List<String> repositories,
                Map<String, String> permissions,
                URI apiBase) implements AppAuthConfig {
    Settings {
        repositories = List.copyOf(repositories);
        permissions = Map.copyOf(permissions);
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    /// The `iss` of the JWT: a client id (`Iv23…`) or an app id, never a character that could leave a JSON string.
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9.]+$");

    /// The ceiling of names any App config may request, shared by the kiln App and the controller App
    /// (which asks for `pull_requests` only); never above `read`. A guard against a typo or an over-reach, not an
    /// authority. `administration` is deliberately absent: `read` does not show a ruleset's `bypass_actors` (LIVE.10,
    /// 2026-11-06), and `write` would give an App power over repository settings (maintainer ruling).
    private static final Set<String> PERMISSION_NAMES = Set.of("pull_requests",
                                                               "checks",
                                                               "statuses",
                                                               "actions",
                                                               "contents",
                                                               "issues",
                                                               "metadata",
                                                               "members");

    private static final Set<String> PERMISSION_LEVELS = Set.of("read");

    static Result<AppAuthConfig> settings(String identity,
                                          String clientId,
                                          long installationId,
                                          Path keyFile,
                                          List<String> repositories,
                                          Map<String, String> permissions,
                                          URI apiBase) {
        var problem = problem(identity, clientId, installationId, keyFile, repositories, permissions, apiBase);

        return problem.isEmpty()
               ? success(new Settings(identity, clientId, installationId, keyFile, repositories, permissions, apiBase))
               : AppAuthError.ConfigInvalid.FACTORY.apply(problem).result();
    }

    private static String problem(String identity,
                                  String clientId,
                                  long installationId,
                                  Path keyFile,
                                  List<String> repositories,
                                  Map<String, String> permissions,
                                  URI apiBase) {
        if (missing(identity, clientId, keyFile, repositories, permissions, apiBase)) {
            return "identity, clientId, keyFile, repositories, permissions and apiBase are required";
        }

        if (!NAME.matcher(identity).matches()) {
            return "identity must match [A-Za-z0-9_.-]+";
        }

        if (!CLIENT_ID.matcher(clientId).matches()) {
            return "clientId must match [A-Za-z0-9.]+";
        }

        if (installationId <= 0) {
            return "installationId must be positive";
        }

        if (repositories.isEmpty() || !repositories.stream().allMatch(Settings::repositoryName)) {
            return "repositories must be a non-empty list of repository names";
        }

        if (permissions.isEmpty() || !permissions.entrySet().stream().allMatch(Settings::allowed)) {
            return "permissions must be a non-empty subset of " + PERMISSION_NAMES + " at level read";
        }

        return "";
    }

    private static boolean missing(Object... values) {
        return Stream.of(values).anyMatch(value -> option(value).isEmpty());
    }

    private static boolean repositoryName(String name) {
        return option(name).filter(value -> NAME.matcher(value).matches())
                     .isPresent();
    }

    private static boolean allowed(Map.Entry<String, String> permission) {
        return option(permission.getKey()).filter(PERMISSION_NAMES::contains)
                     .isPresent() && option(permission.getValue()).filter(PERMISSION_LEVELS::contains)
                                           .isPresent();
    }
}
