package org.pragmatica.temper.controller.config;

import java.nio.file.Path;
import java.util.regex.Pattern;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// The reviewer machine account: `[github.reviewer] login tokenFile`. The PAT file
/// is read per review call, never here and never cached; absent section: `POST fleet/reviews` answers 503
/// `REVIEWER_CREDENTIAL` (no reviewer to post as).
public record GitHubReviewer(String login, Path tokenFile) {
    public static final String SECTION = "github.reviewer";
    private static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})(?:\\[bot\\])?$");

    static Result<Option<GitHubReviewer>> parse(TomlDocument doc, Path home) {
        return option(doc.sections().get(SECTION)).map(table -> Result.all(TomlValues.string(table, SECTION, "login").flatMap(login -> Verify.ensure(login,
                                                                                                                                                     Verify.Is::matches,
                                                                                                                                                     LOGIN,
                                                                                                                                                     cause("[github.reviewer] login is not a GitHub login"))),
                                                                           TomlValues.path(table,
                                                                                           SECTION,
                                                                                           "tokenFile",
                                                                                           home))
                                                                      .map(GitHubReviewer::new)
                                                                      .map(Option::some))
                     .or(() -> success(Option.none()));
    }
}
