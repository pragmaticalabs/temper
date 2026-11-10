package org.pragmatica.kiln.core.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;


/// The drift reads: the ruleset ids of an effective-rules reply, the part of a ruleset that
/// governs merging, a team's logins, and the hash over them. The hash covers canonical JSON (object keys sorted at every
/// depth, array order kept, whitespace gone), so a reply that only re-orders keys hashes the same (K.5), and it covers
/// each ruleset's `bypass_actors`, so adding one changes it.
public sealed interface RulesetDrift {
    /// What of a ruleset the hash covers (§7.4).
    List<String> GOVERNING = List.of("enforcement", "conditions", "rules", "bypass_actors");

    /// The distinct `ruleset_id` values of a `rules/branches/{b}` reply, ascending.
    static Result<List<Long>> ids(String rules) {
        return GitHubJson.tree(rules)
                         .flatMap(root -> GitHubJson.each(root,
                                                          GitHubJson.ROOT,
                                                          node -> GitHubJson.number(node, "ruleset_id")))
                         .map(RulesetDrift::distinct);
    }

    /// The `ruleset_id` values of a `rules/branches/{b}` reply whose `ruleset_source_type` is `Organization`: those are read at
    /// `/orgs/{org}/rulesets/{id}`, not under the repository. Guess: GitHub documents `ruleset_source_type` as `Repository` or
    /// `Organization` on the items of `GET /repos/{o}/{r}/rules/branches/{branch}`; an item without it is a repository's.
    static Result<Set<Long>> organizationIds(String rules) {
        return GitHubJson.tree(rules)
                         .flatMap(root -> GitHubJson.each(root,
                                                          GitHubJson.ROOT,
                                                          node -> GitHubJson.number(node, "ruleset_id").map(id -> organization(node)
                                                                                                                  ? List.of(id)
                                                                                                                  : List.<Long> of())))
                         .map(lists -> lists.stream()
                                            .flatMap(List::stream)
                                            .collect(Collectors.toSet()));
    }

    private static boolean organization(JsonNode item) {
        return "Organization".equals(item.path("ruleset_source_type").asText(""));
    }

    /// The canonical JSON of `json`.
    static Result<String> canonical(String json) {
        return GitHubJson.tree(json).map(RulesetDrift::canonical);
    }

    /// The canonical JSON of the governing fields of a `rulesets/{id}` reply; a field the token cannot see (LIVE.10) is
    /// absent from the reply and so from the hash.
    static Result<String> governing(String ruleset) {
        return GitHubJson.tree(ruleset).map(RulesetDrift::governing);
    }

    /// The logins of a `teams/{slug}/members` reply, sorted.
    static Result<List<String>> members(String team) {
        return GitHubJson.tree(team)
                         .flatMap(root -> GitHubJson.each(root,
                                                          GitHubJson.ROOT,
                                                          node -> GitHubJson.text(node, "login")))
                         .map(logins -> logins.stream()
                                              .sorted()
                                              .toList());
    }

    /// What the hash (SHA-256, taken by the caller) covers: the canonical effective rules and the governing part of each
    /// ruleset, in id order.
    static String material(String effective, Map<Long, String> rulesets) {
        var input = new StringBuilder(effective);

        new TreeMap<>(rulesets).forEach((id, governing) -> input.append('\n')
                                                                .append(id)
                                                                .append('=')
                                                                .append(governing));

        return input.toString();
    }

    private static List<Long> distinct(List<Long> ids) {
        return ids.stream()
                  .distinct()
                  .sorted()
                  .toList();
    }

    private static String governing(JsonNode ruleset) {
        var kept = new ArrayList<String>();

        GOVERNING.stream()
                 .filter(field -> !ruleset.path(field)
                                          .isMissingNode())
                 .forEach(field -> kept.add(quoted(field) + ":" + canonical(ruleset.path(field))));

        return "{" + String.join(",", kept) + "}";
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();

            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            var parts = new ArrayList<String>();

            sorted.forEach((key, value) -> parts.add(quoted(key) + ":" + canonical(value)));

            return "{" + String.join(",", parts) + "}";
        }

        if (node.isArray()) {
            var parts = new ArrayList<String>();

            node.forEach(element -> parts.add(canonical(element)));

            return "[" + String.join(",", parts) + "]";
        }

        return node.toString();
    }

    private static String quoted(String key) {
        return "\"" + key.replace("\\", "\\\\")
                         .replace("\"", "\\\"") + "\"";
    }

    record unused() implements RulesetDrift {}
}
