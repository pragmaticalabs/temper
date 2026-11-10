package org.pragmatica.temper.ghapp;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Result;


/// One identity's settings: the non-secret ids from the config file, the path of the key (never its
/// content), and the narrowing the mint asks for: repositories and permissions, both required, so a token is never
/// minted with everything the installation holds (F5).
public sealed interface AppAuthConfig permits Settings {
    /// name for logs and the G5 view, e.g. `kiln-app`
    String identity();
    /// the JWT `iss` (F6)
    String clientId();
    /// the installation the token is minted for
    long installationId();
    /// the PEM private key, read at each mint (the path, never the content)
    Path keyFile();
    /// repository names the token is narrowed to
    List<String> repositories();
    /// permission name to level, e.g. `pull_requests` to `read`
    Map<String, String> permissions();
    /// `https://api.github.com`, or a stub in tests
    URI apiBase();

    /// A config, or the first thing wrong with the arguments: a missing value (null) is a failure like any other.
    static Result<AppAuthConfig> appAuthConfig(String identity,
                                               String clientId,
                                               long installationId,
                                               Path keyFile,
                                               List<String> repositories,
                                               Map<String, String> permissions,
                                               URI apiBase) {
        return Settings.settings(identity, clientId, installationId, keyFile, repositories, permissions, apiBase);
    }
}
