package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.list;
import static org.pragmatica.kiln.core.github.GitHubJson.number;
import static org.pragmatica.kiln.core.github.GitHubJson.optional;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalInstant;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalNumber;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalText;
import static org.pragmatica.kiln.core.github.GitHubJson.text;


/// One check run at a PR head (`GET /commits/{sha}/check-runs`, spec §4.4): `id, name, app.id, status, conclusion,
/// started_at`. GitHub may send no `app` and no `started_at` (both nullable in its schema); `conclusion` is absent
/// until the run completes. `suiteId` (`check_suite.id`, CI interface T1 WP2) ties the run to its workflow run, whose
/// event and attempt the change feed reports (review H2); absent when GitHub sends none.
public record CheckRun(long id,
                       String name,
                       Option<AppId> app,
                       String status,
                       Option<String> conclusion,
                       Option<Instant> startedAt,
                       Option<Long> suiteId) {
    /// A run without its suite, as the cells read it.
    public CheckRun(long id,
                    String name,
                    Option<AppId> app,
                    String status,
                    Option<String> conclusion,
                    Option<Instant> startedAt) {
        this(id, name, app, status, conclusion, startedAt, Option.none());
    }

    /// One page of check runs. Lenient: unknown fields are ignored.
    public static Result<List<CheckRun>> decode(String json) {
        return GitHubJson.tree(json).flatMap(root -> list(root, "check_runs", CheckRun::checkRun));
    }

    static Result<CheckRun> checkRun(JsonNode node) {
        return Result.all(number(node, "id"),
                          text(node, "name"),
                          optionalNumber(node, "app.id").flatMap(raw -> optional(raw, AppId::appId)),
                          text(node, "status"),
                          optionalText(node, "conclusion"),
                          optionalInstant(node, "started_at"),
                          optionalNumber(node, "check_suite.id"))
                     .map(CheckRun::new);
    }
}
