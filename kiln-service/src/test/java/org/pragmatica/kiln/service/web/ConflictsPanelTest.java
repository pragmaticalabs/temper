package org.pragmatica.kiln.service.web;

import java.util.List;

import org.pragmatica.kiln.core.conflict.Prediction;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.conflict.ConflictBoard;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.kiln.service.web.WebFixtures.NOW;
import static org.pragmatica.kiln.service.web.WebFixtures.ago;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// temper #100: the Conflicts tab lists the current and carried predictions with hot files marked, states the textual
/// limit, shows what the last checks cost, escapes a hostile path, and turns red while the last step failed.
class ConflictsPanelTest {
    private static final Sha TIP = Sha.sha("2e44f7577bbfce430673a4813836186b499f3552").unwrap();

    private static final String TAGS = "integrations/serialization/api/src/main/java/org/pragmatica/serialization/SystemTags.java";

    private static ConflictBoard board(Option<String> failure) {
        return new ConflictBoard(BranchName.branchName("release-1.0.0-rc5").unwrap(),
                                 some(TIP),
                                 some(ago(30)),
                                 5,
                                 1,
                                 4,
                                 List.of(Prediction.pair(pr(2711), pr(2713), List.of(TAGS, "docs/<b>x</b>.md"), TIP)),
                                 List.of(Prediction.tip(pr(2770), List.of("a/ReplicaFilter.java"), TIP)),
                                 List.of(new ConflictBoard.Cost(ago(30), some(850L), some(40960L), 1200, 2, 1)),
                                 failure);
    }

    private static PrNumber pr(long number) {
        return PrNumber.prNumber(number).unwrap();
    }

    private static DashboardView view(Option<ConflictBoard> board) {
        var base = WebFixtures.fresh();

        return new DashboardView(base.sources(),
                                 base.bases(),
                                 base.hosts(),
                                 base.budget(),
                                 base.skipped(),
                                 base.passes(),
                                 base.prs(),
                                 base.runs(),
                                 base.counts(),
                                 base.listed(),
                                 base.mac(),
                                 base.nonGit(),
                                 base.digest(),
                                 base.work(),
                                 base.fleet(),
                                 base.pipeline(),
                                 board);
    }

    @Test
    void board_listsPredictions_hotMarked_limitStated_costShown_pathEscaped() {
        var html = ConflictsPanel.render(view(some(board(none()))), NOW);

        assertThat(html).contains("<section id=\"conflicts\" class=\"panel\">",
                                  "<strong>Limit:</strong> merge-tree finds textual conflicts only",
                                  "release-1.0.0-rc5 at <code>2e44f75</code>",
                                  "5 open PRs checked, 1 not checkable yet",
                                  "<td>pair</td><td>#2711 then #2713</td>",
                                  "pairs skipped: 4",
                                  "<strong class=\"warn\">HOT</strong> <code>" + TAGS + "</code>",
                                  "<code>docs/&lt;b&gt;x&lt;/b&gt;.md</code>",
                                  "carried (not re-checked this step)",
                                  "<td>tip</td><td>#2770</td>",
                                  "<td>2</td><td>1</td><td>1200 ms</td><td>850 ms</td>")
                  .doesNotContain("<b>x</b>");
        assertThat(Html.marked(html)).as("a HOT mark is a warning, not a stale/red mark").isFalse();
        assertThat(ConflictsPanel.count(view(some(board(none()))))).isEqualTo(1);
    }

    @Test
    void unknownFetchedBytes_saysUnknownWhenCompactedOrUnreadable_neverAPlainNothing() {
        var unknown = new ConflictBoard(BranchName.branchName("release-1.0.0-rc5").unwrap(),
                                        some(TIP),
                                        some(ago(30)),
                                        5,
                                        1,
                                        0,
                                        List.of(),
                                        List.of(),
                                        List.of(new ConflictBoard.Cost(ago(30), some(850L), none(), 1200, 2, 1)),
                                        none());
        var html = ConflictsPanel.render(view(some(unknown)), NOW);

        assertThat(html).contains("<span title=\"unknown: the store was compacted or unreadable during the step\">n/a</span>");
    }

    @Test
    void failedStep_isRed_andSaysWhy() {
        var html = ConflictsPanel.render(view(some(board(some("git merge-tree failed")))), NOW);

        assertThat(html).contains("<section id=\"conflicts\" class=\"panel red\">",
                                  "last step failed: git merge-tree failed");
    }

    @Test
    void watchOff_saysSo_andCountsNothing() {
        var html = ConflictsPanel.render(view(none()), NOW);

        assertThat(html).contains("the conflict watch is off", "<strong>Limit:</strong>");
        assertThat(ConflictsPanel.count(view(none()))).isZero();
    }
}
