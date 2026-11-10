package org.pragmatica.kiln.service.github;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.BranchName;

import static org.assertj.core.api.Assertions.assertThat;


/// The feed's pure helpers: the tracked branches, the window URL, and the `git ls-remote --heads` parse.
class GitHubFeedTest {
    private static BranchName branch(String name) {
        return BranchName.branchName(name).unwrap();
    }

    @Test
    void tracked_isMain_theBases_andEveryReleaseBranch_eachOnce() {
        assertThat(GitHubFeed.tracked(List.of(branch("main"), branch("release-1.0.0-rc5")),
                                      List.of(branch("release-1.0.0-rc5"), branch("release-0.9"), branch("feature/x"), branch("main"))))
            .extracting(BranchName::value)
            .containsExactly("main", "release-1.0.0-rc5", "release-0.9");
    }

    @Test
    void windowQuery_encodesTheBound_inWholeSeconds() {
        assertThat(GitHubFeed.windowQuery("actions/runs?event=schedule", Instant.parse("2026-11-03T10:00:00.123Z")))
            .isEqualTo("actions/runs?event=schedule&created=%3E%3D2026-11-03T10%3A00%3A00Z&per_page=100");
    }

    @Test
    void lsRemote_keepsTheHeads_andSkipsAnythingElse() {
        var out = "a".repeat(40) + "\trefs/heads/main\n" + "b".repeat(40) + "\trefs/heads/release-1.0.0-rc5\n"
                  + "c".repeat(40) + "\trefs/tags/v1\nnot a line\n" + "d".repeat(40) + "\trefs/heads/bad..name\n";

        assertThat(GitLsRemote.branches(out)).extracting(BranchName::value)
                                             .containsExactly("main", "release-1.0.0-rc5");
    }
}
