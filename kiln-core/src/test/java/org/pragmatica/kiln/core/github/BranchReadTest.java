package org.pragmatica.kiln.core.github;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.kiln.core.model.ModelError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.causes;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.kiln.core.Fixtures.resource;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §4.3: the branch read and the rulesets read. The `github/branch-*.json` and `github/rules-*.json` fixtures are
/// the live answers for `pragmaticalabs/pragmatica` (captured 2026-10-30, unauthenticated): rc5 holds 9 checks pinned to
/// app 15368 at `non_admins`, `main` is `off` with none, and the rulesets answer `[]`.
class BranchReadTest {
    static final List<String> RC5_CONTEXTS = List.of("build-and-test",
                                                     "forge-tests",
                                                     "fragment",
                                                     "ember-shard-1",
                                                     "ember-shard-2",
                                                     "stub-suites",
                                                     "ember-shard-3",
                                                     "consensus-shard-1",
                                                     "consensus-shard-2");

    private static String required(String body) {
        return "{\"name\": \"b\", \"protection\": {\"enabled\": true, \"required_status_checks\": " + body + "}}";
    }

    @Test
    void decode_readsTheLiveRc5Protection() {
        var read = ok(BranchRead.decode(resource("github/branch-release-1.0.0-rc5.json")));

        assertThat(read.enabled()).isTrue();
        assertThat(read.enforcementLevel()).isEqualTo(some("non_admins"));
        assertThat(read.checks()).extracting(check -> check.context()
                                                           .value())
                                 .containsExactlyElementsOf(RC5_CONTEXTS);
        assertThat(read.checks()).extracting(RequiredCheck::appId)
                                 .containsOnly(some(ok(AppId.appId(15368))));
    }

    @Test
    void decode_readsTheLiveMainProtection() {
        var read = ok(BranchRead.decode(resource("github/branch-main.json")));

        assertThat(read.enabled()).isTrue();
        assertThat(read.enforcementLevel()).isEqualTo(some("off"));
        assertThat(read.checks()).isEmpty();
    }

    /// The older shape: no `checks[]`, so `contexts[]` with no app id.
    @Test
    void decode_fallsBackToContexts_withNoAppId() {
        var read = ok(BranchRead.decode(required("{\"enforcement_level\": \"everyone\", \"contexts\": [\"a\", \"b\"]}")));

        assertThat(read.checks()).containsExactly(new RequiredCheck(ok(CheckContext.checkContext("a")), none()),
                                                  new RequiredCheck(ok(CheckContext.checkContext("b")), none()));
    }

    @Test
    void decode_prefersChecks_overContexts() {
        var read = ok(BranchRead.decode(required("{\"enforcement_level\": \"everyone\", \"contexts\": [\"a\", \"b\"],"
                                                 + " \"checks\": [{\"context\": \"a\", \"app_id\": 7}]}")));

        assertThat(read.checks()).containsExactly(new RequiredCheck(ok(CheckContext.checkContext("a")),
                                                                    some(ok(AppId.appId(7)))));
    }

    /// `null` and GitHub's `-1` both mean "any app"; any other id must be a valid app id.
    @Test
    void decode_readsAnyAppAsNoAppId() {
        var read = ok(BranchRead.decode(required("{\"enforcement_level\": \"everyone\", \"checks\": ["
                                                 + "{\"context\": \"a\", \"app_id\": null}, {\"context\": \"b\", \"app_id\": -1},"
                                                 + " {\"context\": \"c\"}]}")));

        assertThat(read.checks()).extracting(RequiredCheck::appId)
                                 .containsExactly(none(), none(), none());
        assertThat(causes(BranchRead.decode(required("{\"checks\": [{\"context\": \"a\", \"app_id\": 0}]}")))).hasAtLeastOneElementOfType(ModelError.InvalidValue.class);
        assertThat(causes(BranchRead.decode(required("{\"checks\": [{\"context\": \"a\", \"app_id\": \"7\"}]}")))).contains(GitHubError.BadField.FACTORY.apply("app_id"));
    }

    @Test
    void decode_readsNoRequiredStatusChecks_asNoChecks() {
        var read = ok(BranchRead.decode("{\"protection\": {\"enabled\": false}}"));

        assertThat(read.enabled()).isFalse();
        assertThat(read.enforcementLevel()).isEqualTo(none());
        assertThat(read.checks()).isEmpty();
        assertThat(ok(BranchRead.decode("{\"protection\": {\"enabled\": false, \"required_status_checks\": null}}")).checks()).isEmpty();
    }

    /// A list object with neither array is a malformed answer, never "no checks".
    @Test
    void decode_failsOnAListWithNeitherChecksNorContexts() {
        assertThat(causes(BranchRead.decode(required("{\"enforcement_level\": \"non_admins\"}")))).contains(GitHubError.BadField.FACTORY.apply("protection.required_status_checks.contexts"));
        assertThat(causes(BranchRead.decode(required("\"on\"")))).contains(GitHubError.BadField.FACTORY.apply("protection.required_status_checks.contexts"));
        assertThat(causes(BranchRead.decode(required("{\"contexts\": [7]}")))).contains(GitHubError.BadField.FACTORY.apply("protection.required_status_checks.contexts"));
    }

    @Test
    void decode_failsWithoutProtection() {
        assertThat(causes(BranchRead.decode("{\"name\": \"b\"}"))).contains(GitHubError.BadField.FACTORY.apply("protection.enabled"));
        assertThat(causes(BranchRead.decode("{\"protection\": {\"enabled\": \"yes\"}}"))).contains(GitHubError.BadField.FACTORY.apply("protection.enabled"));
        assertThat(causes(BranchRead.decode("<html>"))).hasAtLeastOneElementOfType(GitHubError.Unparseable.class);
    }

    @Test
    void rulesets_readsTheLiveEmptyAnswer() {
        assertThat(ok(Rulesets.decode(resource("github/rules-release-1.0.0-rc5.json")))).isEmpty();
    }

    /// GitHub's documented shape of `rules/branches/{b}`: only `required_status_checks` rules name contexts.
    @Test
    void rulesets_readsTheContextsOfStatusCheckRules_only() {
        var json = """
                   [{"type": "deletion", "ruleset_source_type": "Repository", "ruleset_id": 1},
                    {"type": "required_status_checks", "ruleset_source_type": "Repository", "ruleset_source": "o/r",
                     "ruleset_id": 2, "parameters": {"strict_required_status_checks_policy": false,
                     "required_status_checks": [{"context": "ci", "integration_id": 15368}, {"context": "lint"}]}},
                    {"type": "required_status_checks", "parameters": {"required_status_checks": [{"context": "e2e"}]}}]
                   """;

        assertThat(ok(Rulesets.decode(json))).containsExactly(new RequiredCheck(ok(CheckContext.checkContext("ci")),
                                                                                some(ok(AppId.appId(15368)))),
                                                              new RequiredCheck(ok(CheckContext.checkContext("lint")),
                                                                                none()),
                                                              new RequiredCheck(ok(CheckContext.checkContext("e2e")),
                                                                                none()));
    }

    @Test
    void rulesets_failsOnAMalformedAnswer() {
        assertThat(causes(Rulesets.decode("[{\"ruleset_id\": 1}]"))).contains(GitHubError.BadField.FACTORY.apply("type"));
        assertThat(causes(Rulesets.decode("[{\"type\": \"required_status_checks\"}]"))).contains(GitHubError.BadField.FACTORY.apply("parameters.required_status_checks"));
        assertThat(causes(Rulesets.decode("{}"))).contains(GitHubError.BadField.FACTORY.apply("$"));
    }
}
