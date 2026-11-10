package org.pragmatica.kiln.service.github;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.pragmatica.kiln.service.config.ConfigError;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.config.KilnSecrets;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.ghapp.AppAuthConfig;
import org.pragmatica.temper.ghapp.GitHubAppAuth;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// How kiln authenticates to GitHub: the installation token of the kiln App, the owner's
/// token file, or nothing (`[github] anonymous = true`). [#mode] is what a later view of the budget shows. [#bearer] is
/// asked before every request, so an App token that is refreshed is the one sent; an App that holds no usable token
/// refuses the request ([GitHubReadError.CredentialUnavailable]) and never sends it unauthenticated. No `toString` shows
/// a token.
public sealed interface Credential {
    enum Mode {
        APP,
        TOKEN,
        ANONYMOUS
    }

    /// The kiln App's identity name (logs, the later G5 view).
    String APP_IDENTITY = "kiln-app";

    /// The permission set, all read-only; the mint narrows the token to it (F5). A mint asking for a
    /// permission the installation lacks gets 422, so a permission is added here only after the installation holds it,
    /// and removed from the installation only after this list no longer asks for it.
    Map<String, String> APP_PERMISSIONS = Map.of("pull_requests",
                                                 "read",
                                                 "checks",
                                                 "read",
                                                 "statuses",
                                                 "read",
                                                 "actions",
                                                 "read",
                                                 "contents",
                                                 "read",
                                                 "issues",
                                                 "read",
                                                 "metadata",
                                                 "read",
                                                 "members",
                                                 "read");

    Mode mode();
    /// The bearer token to send now: none for an anonymous read.
    Promise<Option<String>> bearer();

    static Credential app(GitHubAppAuth auth) {
        return new App(auth);
    }

    static Credential token(String token) {
        return new Token(token);
    }

    static Credential anonymous() {
        return new Anonymous();
    }

    /// The credential `github` configures, or why the configuration is refused: exactly one of `tokenFile`,
    /// `[github.app]` (its key is read and checked now; the mint goes to `api`), or `anonymous = true`. No fallback from
    /// one to another.
    static Result<Credential> credential(KilnConfig.GitHub github, URI api, Clock clock) {
        var token = github.tokenFile().isPresent();
        var app = github.app().isPresent();

        if (token && app) {
            return ConfigError.Credential.BOTH.result();
        }

        if (github.anonymous() && (token || app)) {
            return ConfigError.Credential.ANONYMOUS_WITH_CREDENTIAL.result();
        }

        if (app) {
            return github.app()
                         .fold(() -> ConfigError.Credential.NONE.<Credential> result(),
                               found -> appCredential(github, found, api, clock));
        }

        if (token) {
            return KilnSecrets.gitHubToken(github).map(Credential::of);
        }

        return github.anonymous()
               ? Result.success(anonymous())
               : ConfigError.Credential.NONE.result();
    }

    private static Result<Credential> appCredential(KilnConfig.GitHub github,
                                                    KilnConfig.App app,
                                                    URI api,
                                                    Clock clock) {
        if (!oneOwner(github)) {
            return ConfigError.Credential.APP_REPOS_OTHER_OWNER.result();
        }

        return AppAuthConfig.appAuthConfig(APP_IDENTITY,
                                           app.clientId(),
                                           app.installationId(),
                                           app.keyFile(),
                                           repositoryNames(github),
                                           APP_PERMISSIONS,
                                           api)
                            .flatMap(config -> GitHubAppAuth.githubAppAuth(config,
                                                                           GitHubAppAuth.httpClient(),
                                                                           clock))
                            .map(Credential::app);
    }

    /// The mint names repositories without an owner, so they all belong to the owner of the installation.
    private static boolean oneOwner(KilnConfig.GitHub github) {
        return github.repos()
                     .stream()
                     .map(repo -> repo.name()
                                      .substring(0,
                                                 repo.name().indexOf('/')))
                     .distinct()
                     .count() <= 1;
    }

    /// The repository part of each configured `owner/name`, once each: the token is narrowed to the repositories kiln reads.
    private static List<String> repositoryNames(KilnConfig.GitHub github) {
        return github.repos()
                     .stream()
                     .map(repo -> repo.name()
                                      .substring(repo.name().indexOf('/') + 1))
                     .distinct()
                     .toList();
    }

    /// A token as the old code held it: some is a token file's content, none is anonymous.
    static Credential of(Option<String> token) {
        return token.map(Credential::token)
                    .or(Credential::anonymous);
    }

    record App(GitHubAppAuth auth) implements Credential {
        @Override
        public Mode mode() {
            return Mode.APP;
        }

        @Override
        public Promise<Option<String>> bearer() {
            return auth.token()
                       .map(secret -> some(secret.value()))
                       .mapError(GitHubReadError.CredentialUnavailable.FACTORY);
        }

        @Override
        public String toString() {
            return "Credential[APP]";
        }
    }

    record Token(String value) implements Credential {
        @Override
        public Mode mode() {
            return Mode.TOKEN;
        }

        @Override
        public Promise<Option<String>> bearer() {
            return Promise.success(some(value));
        }

        @Override
        public String toString() {
            return "Credential[TOKEN]";
        }
    }

    record Anonymous() implements Credential {
        @Override
        public Mode mode() {
            return Mode.ANONYMOUS;
        }

        @Override
        public Promise<Option<String>> bearer() {
            return Promise.success(none());
        }

        @Override
        public String toString() {
            return "Credential[ANONYMOUS]";
        }
    }
}
