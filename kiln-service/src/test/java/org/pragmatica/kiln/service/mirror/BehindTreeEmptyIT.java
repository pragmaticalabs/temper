package org.pragmatica.kiln.service.mirror;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.mirror.MirrorFixture.branch;
import static org.pragmatica.kiln.service.mirror.MirrorFixture.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// K-8 (kiln-f1-design §7.2, spec §5.1): `behindTreeEmpty` is `git diff --quiet B base`, not `behindBy == 0`, and means
/// tree-equal (maintainer ruling): the tree at the merge base equals the base tip's. A base ahead only by empty commits (`know:`)
/// is tree-equal; one real commit makes the tree differ; a change and its revert are tree-equal again; a head the mirror
/// lacks reads UNKNOWN for both facts, never 0.
class BehindTreeEmptyIT {
    @TempDir
    Path dir;
    private MirrorFixture fixture;
    private GitMirror mirror;
    private String fork;
    private String head;

    /// main: c0 - fork; the PR head: fork - head (a real change). Each test then moves main.
    @BeforeEach
    void setUp() throws Exception {
        fixture = MirrorFixture.create(dir);
        var c0 = fixture.root("a.txt", "a\n");
        fork = fixture.commit(c0, "a.txt", "b\n");
        head = fixture.commit(fork, "pr.txt", "pr\n");
        fixture.ref("refs/pull/1/head", head);
        mirror = fixture.gitMirror();
    }

    private BehindFacts behind(String baseTip) throws Exception {
        fixture.ref("refs/heads/main", baseTip);
        mirror.fetch().await().unwrap();
        return mirror.behind(sha(head), branch("main")).await().unwrap();
    }

    @Test
    void behind_byTwoEmptyCommits_isBehindWithAnEmptyTree() throws Exception {
        var know = fixture.empty(fixture.empty(fork));

        assertThat(behind(know)).isEqualTo(new BehindFacts(some(2), some(true)));
    }

    @Test
    void behind_byOneRealCommit_isBehindWithATreeThatDiffers() throws Exception {
        var real = fixture.commit(fork, "base.txt", "moved\n");

        assertThat(behind(real)).isEqualTo(new BehindFacts(some(1), some(false)));
    }

    /// An empty commit on top of a real one: the tree differs although the last commit is empty.
    @Test
    void behind_byARealCommitUnderAnEmptyOne_isATreeThatDiffers() throws Exception {
        var mixed = fixture.empty(fixture.commit(fork, "base.txt", "moved\n"));

        assertThat(behind(mixed)).isEqualTo(new BehindFacts(some(2), some(false)));
    }

    /// Ruled (K-8): tree-equal, not "empty commits only". A change and its revert leave the base's tree as it was, so
    /// the PR is tree-equal though the base is behind by two non-empty commits; the change alone is not.
    @Test
    void behind_revertPair_isTreeEqual() throws Exception {
        var change = fixture.commit(fork, "r.txt", "r\n");
        MirrorFixture.git(fixture.work, "rm", "-q", "r.txt");
        MirrorFixture.git(fixture.work, "commit", "-q", "-m", "revert");
        var revert = MirrorFixture.git(fixture.work, "rev-parse", "HEAD");

        assertThat(MirrorFixture.git(fixture.work, "rev-parse", "HEAD~1")).isEqualTo(change);
        assertThat(behind(revert)).isEqualTo(new BehindFacts(some(2), some(true)));
        assertThat(behind(change)).isEqualTo(new BehindFacts(some(1), some(false)));
    }

    @Test
    void behind_notBehind_isZeroWithAnEmptyTree() throws Exception {
        assertThat(behind(fork)).isEqualTo(new BehindFacts(some(0), some(true)));
    }

    @Test
    void behind_headAbsentFromTheMirror_isUnknown_neverZero() throws Exception {
        var unpushed = fixture.commit(fork, "u.txt", "u\n");
        fixture.ref("refs/heads/main", fork);
        mirror.fetch().await().unwrap();

        assertThat(mirror.behind(sha(unpushed), branch("main")).await().unwrap()).isEqualTo(new BehindFacts(none(), none()));
        assertThat(mirror.behind(sha(head), branch("main")).await().unwrap()).isEqualTo(new BehindFacts(some(0), some(true)));
    }

    /// Unrelated histories have no `B` to count from, so both facts are UNKNOWN.
    @Test
    void behind_withNoMergeBase_isUnknown() throws Exception {
        fixture.ref("refs/heads/main", fork);
        fixture.ref("refs/heads/orphan", orphan());
        mirror.fetch().await().unwrap();

        assertThat(mirror.behind(sha(head), branch("orphan")).await().unwrap()).isEqualTo(new BehindFacts(none(), none()));
    }

    @Test
    void behind_fails_forABaseTheMirrorLacks() throws Exception {
        fixture.ref("refs/heads/main", fork);
        mirror.fetch().await().unwrap();

        assertThat(mirror.behind(sha(head), branch("gone")).await().isFailure()).isTrue();
    }

    @Test
    void behind_fails_whenTheMirrorIsAbsent_neverReadingUnknown() {
        assertThat(mirror.behind(sha(head), branch("main")).await().isFailure()).isTrue();
    }

    private String orphan() throws Exception {
        MirrorFixture.git(fixture.work, "checkout", "-q", "--orphan", "orphan");
        return fixture.root("o.txt", "o\n");
    }
}
