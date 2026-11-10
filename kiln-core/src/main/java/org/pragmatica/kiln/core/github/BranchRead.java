package org.pragmatica.kiln.core.github;

import java.util.List;

import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.github.GitHubJson.at;
import static org.pragmatica.kiln.core.github.GitHubJson.badField;
import static org.pragmatica.kiln.core.github.GitHubJson.bool;
import static org.pragmatica.kiln.core.github.GitHubJson.list;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalSha;
import static org.pragmatica.kiln.core.github.GitHubJson.optionalText;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// The `protection` part of `GET /repos/{o}/{r}/branches/{b}` (spec §4.3): `enabled`,
/// `required_status_checks.enforcement_level` (absent when nothing is required) and `checks[]`, falling back to
/// `contexts[]` with no app id for the older shape. Base tracking adds the tip: `commit.sha` and its tree
/// `commit.commit.tree.sha`, each none when absent (an older fixture or answer still decodes; no tip, no base move).
public record BranchRead(boolean enabled,
                         Option<String> enforcementLevel,
                         List<RequiredCheck> checks,
                         Option<Sha> tip,
                         Option<Sha> tree) {
    private static final String REQUIRED = "protection.required_status_checks";
    private static final String CHECKS = REQUIRED + ".checks";
    private static final String CONTEXTS = REQUIRED + ".contexts";

    public BranchRead {
        checks = List.copyOf(checks);
    }

    /// A read without its tip, as the required list reads it.
    public BranchRead(boolean enabled, Option<String> enforcementLevel, List<RequiredCheck> checks) {
        this(enabled, enforcementLevel, checks, none(), none());
    }

    public static Result<BranchRead> decode(String json) {
        return GitHubJson.tree(json).flatMap(BranchRead::branchRead);
    }

    private static Result<BranchRead> branchRead(JsonNode root) {
        return Result.all(bool(root, "protection.enabled"),
                          optionalText(root, REQUIRED + ".enforcement_level"),
                          checks(at(root, REQUIRED), root),
                          optionalSha(root, "commit.sha"),
                          optionalSha(root, "commit.commit.tree.sha")).map(BranchRead::new);
    }

    /// No `required_status_checks` object: nothing is required. With one, `checks[]` is read, else `contexts[]`; an
    /// object holding neither is a malformed answer, never "no checks", so it fails (and reads `UNKNOWN`).
    private static Result<List<RequiredCheck>> checks(JsonNode required, JsonNode root) {
        return required.isMissingNode() || required.isNull()
               ? success(List.of())
               : listed(root);
    }

    private static Result<List<RequiredCheck>> listed(JsonNode root) {
        return at(root, CHECKS).isArray()
               ? list(root, CHECKS, check -> GitHubJson.requiredCheck(check, "app_id"))
               : list(root, CONTEXTS, BranchRead::context);
    }

    private static Result<RequiredCheck> context(JsonNode node) {
        return Option.from(node.stringValueOpt())
                     .toResult(badField(CONTEXTS))
                     .flatMap(CheckContext::checkContext)
                     .map(context -> new RequiredCheck(context,
                                                       none()));
    }
}
