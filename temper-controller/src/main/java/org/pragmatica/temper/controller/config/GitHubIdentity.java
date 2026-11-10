package org.pragmatica.temper.controller.config;

import java.nio.file.Path;
import java.util.Map;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// Whom the controller reads GitHub as: its App (`[github.app] clientId, installationId,
/// keyFile`), or nobody (`[github] anonymous = true`, tests; G5 state `ANONYMOUS`). A config with neither is refused at
/// start ([ConfigError.General#NO_GITHUB_IDENTITY], exit 78): an unauthenticated start must be asked for, never a default.
/// Both together are refused as well.
public sealed interface GitHubIdentity {
    String APP_SECTION = "github.app";
    String SECTION = "github";

    /// The controller App; the key file is read at each mint, never here.
    record App(String clientId, long installationId, Path keyFile) implements GitHubIdentity {
        static Result<GitHubIdentity> app(Map<String, Object> table, Path home) {
            return Result.all(TomlValues.string(table, APP_SECTION, "clientId"),
                              installation(table),
                              TomlValues.path(table, APP_SECTION, "keyFile", home))
                         .map(App::new);
        }

        private static Result<Long> installation(Map<String, Object> table) {
            return option(table.get("installationId")).filter(Number.class::isInstance)
                         .map(value -> ((Number) value).longValue())
                         .filter(id -> id > 0)
                         .toResult(ConfigError.Missing.FACTORY.apply(APP_SECTION, "installationId"));
        }
    }

    /// No credential: GitHub is read unauthenticated.
    enum Anonymous implements GitHubIdentity {
        INSTANCE
    }

    static Result<GitHubIdentity> parse(TomlDocument doc, Path home) {
        return Result.all(anonymous(doc),
                          success(option(doc.sections().get(APP_SECTION))))
                     .flatMap((anonymous, app) -> chosen(anonymous, app, home));
    }

    private static Result<Boolean> anonymous(TomlDocument doc) {
        return option(doc.sections().get(SECTION)).map(table -> TomlValues.strictFlag(table, SECTION, "anonymous"))
                     .or(() -> success(false));
    }

    private static Result<GitHubIdentity> chosen(boolean anonymous, Option<Map<String, Object>> app, Path home) {
        return app.map(table -> anonymous
                                ? bothGiven()
                                : App.app(table, home))
                  .or(() -> anonymous
                            ? Result.<GitHubIdentity> success(Anonymous.INSTANCE)
                            : ConfigError.General.NO_GITHUB_IDENTITY.result());
    }

    private static Result<GitHubIdentity> bothGiven() {
        return ConfigError.Invalid.FACTORY.apply(SECTION,
                                                 "anonymous",
                                                 cause("true cannot be combined with [github.app]")).result();
    }
}
