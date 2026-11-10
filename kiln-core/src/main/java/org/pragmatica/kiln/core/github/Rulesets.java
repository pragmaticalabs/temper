package org.pragmatica.kiln.core.github;

import java.util.List;

import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.each;
import static org.pragmatica.kiln.core.github.GitHubJson.list;
import static org.pragmatica.kiln.core.github.GitHubJson.text;
import static org.pragmatica.lang.Result.success;


/// Decodes `GET /repos/{o}/{r}/rules/branches/{b}` (spec §4.3): the contexts of every active `required_status_checks`
/// rule, with `integration_id` as the app id. Every other rule type names none. Under classic protection GitHub answers
/// `[]`, which means nothing: [RequiredChecks#resolve] only ever adds these to a classic list.
public sealed interface Rulesets {
    String STATUS_CHECKS = "required_status_checks";

    static Result<List<RequiredCheck>> decode(String json) {
        return GitHubJson.tree(json)
                         .flatMap(root -> each(root, GitHubJson.ROOT, Rulesets::rule))
                         .map(Rulesets::flattened);
    }

    private static Result<List<RequiredCheck>> rule(JsonNode node) {
        return text(node, "type").flatMap(type -> checks(type, node));
    }

    private static Result<List<RequiredCheck>> checks(String type, JsonNode node) {
        return STATUS_CHECKS.equals(type)
               ? list(node,
                      "parameters.required_status_checks",
                      check -> GitHubJson.requiredCheck(check, "integration_id"))
               : success(List.of());
    }

    private static List<RequiredCheck> flattened(List<List<RequiredCheck>> rules) {
        return rules.stream()
                    .flatMap(List::stream)
                    .toList();
    }

    record unused() implements Rulesets {}
}
