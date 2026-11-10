package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The two rules kiln-f1-design §1.1 states on the github records themselves: fork exclusion and the only success.
class PullRequestTest {
    private static final String REPO = "pragmaticalabs/pragmatica";

    private static PullRequest withHeadRepo(Option<String> headRepo, List<String> labels) {
        return new PullRequest(ok(PrNumber.prNumber(7)),
                               "t",
                               PrState.OPEN,
                               false,
                               ok(BranchName.branchName("main")),
                               ok(BranchName.branchName("kiln-wp1")),
                               headRepo,
                               ok(Sha.sha("0cf563192a0cf563192a0cf563192a0cf563192a")),
                               none(),
                               none(),
                               Instant.parse("2026-10-30T10:00:00Z"),
                               labels,
                               List.of(),
                               List.of());
    }

    @Test
    void sameRepo_isTrue_forTheBaseRepository() {
        assertThat(withHeadRepo(some(REPO), List.of()).sameRepo(REPO)).isTrue();
    }

    /// A fork, or a head whose repository is gone, never links a checkout (spec §5.3).
    @Test
    void sameRepo_isFalse_forAForkOrAMissingHeadRepository() {
        assertThat(withHeadRepo(some("someone/pragmatica"), List.of()).sameRepo(REPO)).isFalse();
        assertThat(withHeadRepo(none(), List.of()).sameRepo(REPO)).isFalse();
    }

    /// GitHub resolves owner and name ignoring case, so a configured `repo` cased differently is the same repository;
    /// a different owner or name, in any case, is not.
    @Test
    void sameRepo_ignoresCase_inOwnerAndName() {
        assertThat(withHeadRepo(some("PragmaticaLabs/Pragmatica"), List.of()).sameRepo(REPO)).isTrue();
        assertThat(withHeadRepo(some(REPO), List.of()).sameRepo("PRAGMATICALABS/PRAGMATICA")).isTrue();
        assertThat(withHeadRepo(some("Someone/Pragmatica"), List.of()).sameRepo(REPO)).isFalse();
        assertThat(withHeadRepo(some("PragmaticaLabs/Pragmatica-fork"), List.of()).sameRepo(REPO)).isFalse();
    }

    @Test
    void pullRequest_copiesItsLists() {
        var labels = new ArrayList<>(List.of("a"));
        var pr = withHeadRepo(some(REPO), labels);

        labels.add("b");

        assertThat(pr.labels()).containsExactly("a");
        assertThatThrownBy(() -> pr.labels().add("c")).isInstanceOf(UnsupportedOperationException.class);
    }

    /// spec §4.4: only `success` counts as success, anywhere; `success*` (a skipped status) and `UNKNOWN` do not.
    @Test
    void cell_isSuccess_onlyForSuccess() {
        var check = new RequiredCheck(ok(CheckContext.checkContext("build")), some(ok(AppId.appId(15368))));

        assertThat(new Cell(check, "success", 0, List.of("success")).isSuccess()).isTrue();
        assertThat(new Cell(check, "success*", 0, List.of("success*")).isSuccess()).isFalse();
        assertThat(new Cell(check, "SUCCESS", 0, List.of()).isSuccess()).isFalse();
        assertThat(new Cell(check, "UNKNOWN", 0, List.of()).isSuccess()).isFalse();
        assertThat(new Cell(check, "pending", 1, List.of()).isSuccess()).isFalse();
    }
}
