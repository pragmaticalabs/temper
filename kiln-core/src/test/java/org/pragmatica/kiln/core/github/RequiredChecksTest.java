package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.kiln.core.Fixtures.resource;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;

/// K-6 (spec §4.3): the three-state list. The branch read alone decides the state: a 403 is `UNKNOWN(403)` whatever
/// the rulesets say, `enforcement_level: off` is `NONE-REQUIRED(off)`, and the live rc5 read with the live rulesets
/// `[]` is `REQUIRED(9, non_admins)`. Neither an empty list nor an unknown one ever yields a success cell.
class RequiredChecksTest {
    private static final AppId ACTIONS = ok(AppId.appId(15368));
    private static final Result<List<RequiredCheck>> NO_RULESETS = success(List.of());

    private static Result<BranchRead> rc5() {
        return BranchRead.decode(resource("github/branch-release-1.0.0-rc5.json"));
    }

    private static Result<BranchRead> main() {
        return BranchRead.decode(resource("github/branch-main.json"));
    }

    private static Result<List<RequiredCheck>> liveRulesets() {
        return Rulesets.decode(resource("github/rules-release-1.0.0-rc5.json"));
    }

    private static RequiredCheck check(String context, AppId app) {
        return new RequiredCheck(ok(CheckContext.checkContext(context)), some(app));
    }

    private static RequiredCheck anyApp(String context) {
        return new RequiredCheck(ok(CheckContext.checkContext(context)), none());
    }

    private static Result<BranchRead> branch(boolean enabled, String level, List<RequiredCheck> checks) {
        return success(new BranchRead(enabled, some(level), checks));
    }

    @Test
    void resolve_readsTheLiveRc5List_asRequiredNine() {
        var resolved = RequiredChecks.resolve(rc5(), liveRulesets(), true);

        assertThat(resolved).isInstanceOfSatisfying(RequiredChecks.Required.class, required -> {
            assertThat(required.checks()).hasSize(9);
            assertThat(required.enforcement()).isEqualTo("non_admins");
            assertThat(required.checks()).extracting(check -> check.context()
                                                                   .value())
                                         .containsExactlyElementsOf(BranchReadTest.RC5_CONTEXTS);
            assertThat(required.checks()).extracting(RequiredCheck::appId)
                                         .containsOnly(some(ACTIONS));
        });
    }

    @Test
    void resolve_readsAFailedBranchRead_asUnknownWithItsStatus() {
        var forbidden = RequiredChecks.resolve(GitHubError.HttpStatus.FACTORY.apply(403)
                                                                     .result(),
                                               NO_RULESETS,
                                               true);

        assertThat(forbidden).isEqualTo(new RequiredChecks.Unknown("403"));
        assertThat(RequiredChecks.resolve(cause("timeout after 30 s").result(), NO_RULESETS, true)).isEqualTo(new RequiredChecks.Unknown("timeout after 30 s"));
    }

    /// The rulesets never stand in for the branch read: with the branch read failed they change nothing.
    @Test
    void resolve_neverResolvesFromTheRulesetsAlone() {
        var rulesets = success(List.of(check("build-and-test", ACTIONS)));

        assertThat(RequiredChecks.resolve(GitHubError.HttpStatus.FACTORY.apply(403)
                                                                .result(),
                                          rulesets,
                                          true)).isEqualTo(new RequiredChecks.Unknown("403"));
        assertThat(RequiredChecks.resolve(main(), rulesets, true)).isEqualTo(new RequiredChecks.NoneRequired(RequiredChecks.OFF,
                                                                                                             false));
        assertThat(RequiredChecks.resolve(branch(true, "non_admins", List.of()), rulesets, true)).isEqualTo(new RequiredChecks.NoneRequired(RequiredChecks.NO_CHECKS,
                                                                                                                                            false));
    }

    @Test
    void resolve_readsTheLiveMainList_asNoneRequiredOff() {
        assertThat(RequiredChecks.resolve(main(), liveRulesets(), true)).isEqualTo(new RequiredChecks.NoneRequired("off",
                                                                                                                   false));
    }

    /// A base no config names is a stacked PR's base: "stacked base: CI may not run".
    @Test
    void resolve_marksAStackedBase() {
        assertThat(RequiredChecks.resolve(main(), NO_RULESETS, false)).isEqualTo(new RequiredChecks.NoneRequired("off",
                                                                                                                 true));
    }

    @Test
    void resolve_namesWhyNothingIsRequired() {
        var checks = List.of(check("build", ACTIONS));

        assertThat(RequiredChecks.resolve(branch(false, "non_admins", checks), NO_RULESETS, true)).isEqualTo(new RequiredChecks.NoneRequired("disabled",
                                                                                                                                             false));
        assertThat(RequiredChecks.resolve(branch(true, "off", checks), NO_RULESETS, true)).isEqualTo(new RequiredChecks.NoneRequired("off",
                                                                                                                                     false));
        assertThat(RequiredChecks.resolve(branch(true, "everyone", List.of()), NO_RULESETS, true)).isEqualTo(new RequiredChecks.NoneRequired("no checks",
                                                                                                                                             false));
        assertThat(RequiredChecks.resolve(success(new BranchRead(false, none(), List.of())), NO_RULESETS, false)).isEqualTo(new RequiredChecks.NoneRequired("disabled",
                                                                                                                                                            true));
    }

    /// An empty list is never "all success": NONE-REQUIRED and UNKNOWN give no cell at all, so nothing reads green.
    @Test
    void resolve_neverMapsAnEmptyOrUnknownList_toSuccessCells() {
        var runs = success(List.of(new CheckRun(1, "build", some(ACTIONS), "completed", some("success"), none())));
        var none = RequiredChecks.resolve(branch(true, "non_admins", List.of()), NO_RULESETS, true);
        var unknown = RequiredChecks.resolve(GitHubError.HttpStatus.FACTORY.apply(403)
                                                                   .result(),
                                             NO_RULESETS,
                                             true);

        assertThat(none).isNotInstanceOf(RequiredChecks.Required.class);
        assertThat(CheckCells.cells(none, runs, success(List.of()))).isEmpty();
        assertThat(CheckCells.cells(unknown, runs, success(List.of()))).isEmpty();
    }

    @Test
    void resolve_addsRulesetContexts_toARequiredList() {
        var resolved = RequiredChecks.resolve(rc5(), success(List.of(anyApp("lint"))), true);

        assertThat(resolved).isInstanceOfSatisfying(RequiredChecks.Required.class,
                                                    required -> assertThat(required.checks()).hasSize(10)
                                                                                             .endsWith(anyApp("lint")));
    }

    /// A required check is the pair `{context, app_id}`: a pair both name is kept once.
    @Test
    void resolve_keepsAnIdenticalPairNamedByBoth_once() {
        var resolved = RequiredChecks.resolve(rc5(),
                                              success(List.of(check("build-and-test", ACTIONS), anyApp("lint"), anyApp("lint"))),
                                              true);

        assertThat(resolved).isInstanceOfSatisfying(RequiredChecks.Required.class, required -> {
            assertThat(required.checks()).hasSize(10)
                                         .doesNotHaveDuplicates();
            assertThat(required.checks()).filteredOn(check -> check.context()
                                                                   .value()
                                                                   .equals("build-and-test"))
                                         .containsExactly(check("build-and-test", ACTIONS));
        });
    }

    /// a8: the classic list leaves `ci` unpinned, a ruleset pins it to Actions. GitHub enforces both, so both are kept,
    /// each its own cell, and a status alone satisfies only the unpinned one: never greener than GitHub.
    @Test
    void resolve_keepsBoth_whenARulesetPinsAContextTheClassicListLeavesUnpinned() {
        var resolved = RequiredChecks.resolve(branch(true, "non_admins", List.of(anyApp("ci"))),
                                              success(List.of(check("ci", ACTIONS))),
                                              true);
        var status = new CommitStatus(1, "ci", "success", none(), Instant.parse("2026-10-30T01:00:00Z"));

        assertThat(resolved).isEqualTo(new RequiredChecks.Required(List.of(anyApp("ci"), check("ci", ACTIONS)), "non_admins"));
        assertThat(CheckCells.cells(resolved, success(List.of()), success(List.of(status)))).extracting(Cell::value)
                                                                                           .containsExactly("success", "missing");
    }

    /// a9: the classic list and a ruleset pin `ci` to different apps: both kept, so one app's success alone is not green.
    @Test
    void resolve_keepsBoth_whenARulesetPinsTheSameContextToAnotherApp() {
        var other = ok(AppId.appId(999));
        var resolved = RequiredChecks.resolve(branch(true, "non_admins", List.of(check("ci", ACTIONS))),
                                              success(List.of(check("ci", other))),
                                              true);
        var run = new CheckRun(1, "ci", some(ACTIONS), "completed", some("success"), some(Instant.parse("2026-10-30T01:00:00Z")));

        assertThat(resolved).isEqualTo(new RequiredChecks.Required(List.of(check("ci", ACTIONS), check("ci", other)), "non_admins"));
        assertThat(CheckCells.cells(resolved, success(List.of(run)), success(List.of()))).extracting(Cell::value)
                                                                                         .containsExactly("success", "missing");
    }

    /// A failed rulesets read under a classic list may hide a context: never greener than GitHub.
    @Test
    void resolve_readsAFailedRulesetsRead_underARequiredList_asUnknown() {
        assertThat(RequiredChecks.resolve(rc5(),
                                          GitHubError.HttpStatus.FACTORY.apply(502)
                                                                .result(),
                                          true)).isEqualTo(new RequiredChecks.Unknown("rulesets 502"));
    }

    /// An unparseable branch answer is UNKNOWN, its status one line naming the bad field.
    @Test
    void resolve_readsAnUndecodableBranchAnswer_asUnknown() {
        var resolved = RequiredChecks.resolve(BranchRead.decode("{\"name\": \"b\"}"), NO_RULESETS, true);

        assertThat(resolved).isInstanceOfSatisfying(RequiredChecks.Unknown.class,
                                                    unknown -> assertThat(unknown.status()).contains("protection.enabled")
                                                                                           .doesNotContain("\n"));
    }
}
