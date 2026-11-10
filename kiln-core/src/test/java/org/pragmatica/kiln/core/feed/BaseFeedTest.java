package org.pragmatica.kiln.core.feed;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.Compare;
import org.pragmatica.kiln.core.github.GitHubError;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The base-move walk and its fail-safes.
class BaseFeedTest {
    private static final Instant AT = Instant.parse("2026-11-04T10:00:00Z");

    private static Sha sha(long n) {
        return Sha.sha(String.format("%040x", n)).unwrap();
    }

    /// Commit `n` with tree `tree` and first parent `parent`.
    private static Compare.Commit commit(long n, long tree, long parent) {
        return new Compare.Commit(sha(n), sha(1000 + tree), some(sha(parent)));
    }

    private static final BaseFeed.Tip OLD = new BaseFeed.Tip(sha(1), sha(1001));

    private static BaseFeed.Moved ahead(Sha now, Compare.Commit... commits) {
        return BaseFeed.moved(OLD, now, Result.success(new Compare(Compare.AHEAD, List.of(commits))));
    }

    @Test
    void moved_aheadWithTreeChanges_treeChanged_firstIsTheOldestChanged() {
        var moved = ahead(sha(4), commit(2, 2, 1), commit(3, 2, 2), commit(4, 4, 3));

        assertThat(moved).isEqualTo(new BaseFeed.Moved(true, false, 3, some(sha(2)), true));
    }

    @Test
    void moved_aheadWithOnlyEmptyCommits_treeUnchanged_complete() {
        var moved = ahead(sha(3), commit(2, 1, 1), commit(3, 1, 2));

        assertThat(moved).isEqualTo(new BaseFeed.Moved(false, false, 2, none(), true));
    }

    @Test
    void moved_mergeCommitWalksFirstParentOnly() {
        var moved = ahead(sha(3), commit(9, 7, 1), commit(2, 1, 1), new Compare.Commit(sha(3), sha(1001), some(sha(2))));

        assertThat(moved).isEqualTo(new BaseFeed.Moved(false, false, 2, none(), true));
    }

    @Test
    void moved_behindOrDiverged_rewound() {
        for (var status : List.of("behind", "diverged")) {
            assertThat(BaseFeed.moved(OLD, sha(5), Result.success(new Compare(status, List.of())))).isEqualTo(new BaseFeed.Moved(true, true, 0, none(), true));
        }
    }

    @Test
    void moved_parentNotListed_over250Commits_failSafe() {
        var listed = LongStream.rangeClosed(300 - 249, 300).mapToObj(n -> commit(n, 1, n - 1)).toArray(Compare.Commit[]::new);

        assertThat(ahead(sha(300), listed)).isEqualTo(new BaseFeed.Moved(true, false, 250, none(), false));
    }

    @Test
    void moved_oldNotOnTheFirstParentLine_failSafe() {
        assertThat(ahead(sha(3), commit(3, 1, 2), new Compare.Commit(sha(2), sha(1001), none())))
            .isEqualTo(new BaseFeed.Moved(true, false, 2, none(), false));
    }

    @Test
    void moved_newTipNotListed_failSafe() {
        assertThat(ahead(sha(3))).isEqualTo(new BaseFeed.Moved(true, false, 0, none(), false));
    }

    @Test
    void moved_cycleInAMalformedAnswer_stopsIncomplete() {
        assertThat(ahead(sha(3), commit(3, 1, 2), commit(2, 1, 3)).complete()).isFalse();
    }

    @Test
    void moved_failedCompare_failSafe() {
        assertThat(BaseFeed.moved(OLD, sha(3), GitHubError.HttpStatus.FACTORY.apply(500).result())).isEqualTo(new BaseFeed.Moved(true, false, 0, none(), false));
    }

    // verifier t2wpbv
    @Test
    void vx3_aChangeAndItsRevert_treeChanged_firstIsTheChange() {
        var moved = ahead(sha(3), commit(2, 2, 1), commit(3, 1, 2));

        assertThat(moved).isEqualTo(new BaseFeed.Moved(true, false, 2, some(sha(2)), true));
    }

    @Test
    void change_rowAndPayloadInContractOrder() {
        var change = BaseFeed.change(BranchName.branchName("main").unwrap(), sha(1), sha(4), new BaseFeed.Moved(true, false, 3, some(sha(2)), true), AT);

        assertThat(change.kind()).isEqualTo(FeedKind.BASE_MOVED);
        assertThat(change.pr()).isEqualTo(none());
        assertThat(change.subject()).isEqualTo("main");
        assertThat(change.before()).isEqualTo(sha(1).value());
        assertThat(change.after()).isEqualTo(sha(4).value());
        assertThat(change.payload()).containsExactly(Map.entry("base", "main"),
                                                     Map.entry("treeChanged", true),
                                                     Map.entry("rewound", false),
                                                     Map.entry("commits", 3),
                                                     Map.entry("firstChanged", sha(2).value()),
                                                     Map.entry("complete", true));
        assertThat(BaseFeed.change(BranchName.branchName("main").unwrap(), sha(1), sha(4), new BaseFeed.Moved(true, false, 0, none(), false), AT).payload())
            .doesNotContainKey("firstChanged");
    }
}
