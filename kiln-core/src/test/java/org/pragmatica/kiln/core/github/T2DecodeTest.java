package org.pragmatica.kiln.core.github;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.Sha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The decoders CI interface T2 adds.
class T2DecodeTest {
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    private static final String C = "c".repeat(40);

    private static Sha sha(String value) {
        return Sha.sha(value).unwrap();
    }

    @Test
    void branchRead_withCommit_decodesTipAndTree_withoutIt_none() {
        var read = BranchRead.decode("{\"name\":\"main\",\"commit\":{\"sha\":\"" + A + "\",\"commit\":{\"tree\":{\"sha\":\"" + B
                                     + "\"}}},\"protection\":{\"enabled\":false}}").unwrap();

        assertThat(read.tip()).isEqualTo(some(sha(A)));
        assertThat(read.tree()).isEqualTo(some(sha(B)));
        assertThat(BranchRead.decode("{\"protection\":{\"enabled\":false}}").unwrap().tip()).isEqualTo(none());
        assertThat(BranchRead.decode("{\"commit\":{\"sha\":\"XYZ\"},\"protection\":{\"enabled\":false}}").isFailure()).isTrue();
    }

    @Test
    void compare_decodesStatusTreesAndFirstParents() {
        var compare = Compare.decode("{\"status\":\"ahead\",\"commits\":[{\"sha\":\"" + B + "\",\"commit\":{\"tree\":{\"sha\":\"" + C
                                     + "\"}},\"parents\":[{\"sha\":\"" + A + "\"},{\"sha\":\"" + C + "\"}]},{\"sha\":\"" + C
                                     + "\",\"commit\":{\"tree\":{\"sha\":\"" + C + "\"}},\"parents\":[]}]}").unwrap();

        assertThat(compare.status()).isEqualTo("ahead");
        assertThat(compare.commits()).containsExactly(new Compare.Commit(sha(B), sha(C), some(sha(A))), new Compare.Commit(sha(C), sha(C), none()));
        assertThat(Compare.decode("{\"status\":\"ahead\"}").isFailure()).isTrue();
    }

    @Test
    void pullRead_decodesTheGateFields_mergedStateWhenMergedAtIsSet() {
        var open = PullRead.decode(pull("open", "null", "{\"full_name\":\"o/r\"}", "null")).unwrap();
        var merged = PullRead.decode(pull("closed", "\"2026-11-04T10:00:00Z\"", "null", "{\"login\":\"cto\"}")).unwrap();

        assertThat(open).isEqualTo(new PullRead("open", none(), sha(A), "feat", some("o/r"), open.base(), 4, none()));
        assertThat(open.open()).isTrue();
        assertThat(merged.state()).isEqualTo("merged");
        assertThat(merged.mergedAt()).isEqualTo(some(Instant.parse("2026-11-04T10:00:00Z")));
        assertThat(merged.headRepo()).isEqualTo(none());
        assertThat(merged.open()).isFalse();
        assertThat(PullRead.mergedBy(pull("closed", "null", "null", "{\"login\":\"cto\"}")).unwrap()).isEqualTo(some("cto"));
        assertThat(PullRead.mergedBy(pull("closed", "null", "null", "null")).unwrap()).isEqualTo(none());
    }

    private static String pull(String state, String mergedAt, String headRepo, String mergedBy) {
        return "{\"state\":\"" + state + "\",\"merged_at\":" + mergedAt + ",\"head\":{\"sha\":\"" + A + "\",\"ref\":\"feat\",\"repo\":"
               + headRepo + "},\"base\":{\"ref\":\"main\"},\"changed_files\":4,\"merged_by\":" + mergedBy + "}";
    }

    @Test
    void counted_readsTotalCountBesideItsItems_andFilesWithRenames() {
        var runs = Counted.checkRuns("{\"total_count\":7,\"check_runs\":[{\"id\":1,\"name\":\"ci\",\"status\":\"queued\"}]}").unwrap();
        var workflowRuns = Counted.workflowRuns("{\"total_count\":0,\"workflow_runs\":[]}").unwrap();
        var files = Counted.files("[{\"filename\":\"a\"},{\"filename\":\"b\",\"previous_filename\":\".github/x\"}]").unwrap();

        assertThat(runs.total()).isEqualTo(7);
        assertThat(runs.items()).extracting(CheckRun::id).containsExactly(1L);
        assertThat(workflowRuns.total()).isZero();
        assertThat(files).containsExactly(new Counted.File("a", none()), new Counted.File("b", some(".github/x")));
        assertThat(Counted.checkRuns("{\"check_runs\":[]}").isFailure()).isTrue();
    }
}
