package org.pragmatica.kiln.service.mirror;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.mirror.MirrorFixture.branch;
import static org.pragmatica.kiln.service.mirror.MirrorFixture.sha;

/// [GitMirror] against a real "GitHub" origin ([MirrorFixture]): the clone and the fetch with its three refspecs and
/// `--prune`, reachability through each ref pattern (and never through `refs/pull/*/merge` or any other namespace), the
/// memo per fetch generation, and HEAD's ancestry over a stacked chain. Each answer has its control: the same query
/// whose answer flips when only the property under test changes.
class GitMirrorTest {
    private static final String MISSING = "1".repeat(40);

    @TempDir
    Path dir;
    private MirrorFixture fixture;
    private GitMirror mirror;
    private String c0;
    private String c1;

    @BeforeEach
    void setUp() throws Exception {
        fixture = MirrorFixture.create(dir);
        c0 = fixture.root("a.txt", "a\n");
        c1 = fixture.commit(c0, "a.txt", "b\n");
        fixture.ref("refs/heads/main", c1);
        mirror = fixture.gitMirror();
    }

    private Map<Sha, Boolean> reachable(String... shas) {
        return mirror.reachable(Set.of(shas).stream().map(MirrorFixture::sha).collect(Collectors.toSet()))
                     .await()
                     .unwrap();
    }

    private boolean isReachable(String one) {
        return reachable(one).get(sha(one));
    }

    private Ancestry ancestry(String head, String... bases) {
        return mirror.ancestry(sha(head),
                               List.of(bases).stream().map(MirrorFixture::branch).toList())
                     .await()
                     .unwrap();
    }

    private static Set<PrNumber> prs(long... numbers) {
        return Arrays.stream(numbers).mapToObj(n -> PrNumber.prNumber(n).unwrap()).collect(Collectors.toSet());
    }

    private static Set<Sha> shas(String... shas) {
        return Set.of(shas).stream().map(MirrorFixture::sha).collect(Collectors.toSet());
    }

    // ---- fetch ---------------------------------------------------------------------------------------------------
    @Test
    void fetch_clonesTheMirror_whenAbsent_creatingItsParents() throws Exception {
        assertThat(Files.exists(fixture.mirror)).isFalse();

        mirror.fetch().await().unwrap();

        assertThat(fixture.mirrorRefs()).contains(c1 + " refs/heads/main");
    }

    @Test
    void fetch_bringsNewRefs_intoTheExistingMirror() throws Exception {
        mirror.fetch().await().unwrap();
        var c2 = fixture.commit(c1, "f.txt", "f\n");
        fixture.ref("refs/heads/feature", c2);
        fixture.ref("refs/tags/v2", c2);
        fixture.ref("refs/pull/4/head", c2);

        assertThat(fixture.mirrorRefs()).doesNotContain(c2 + " refs/heads/feature");

        mirror.fetch().await().unwrap();

        assertThat(fixture.mirrorRefs()).contains(c2 + " refs/heads/feature", c2 + " refs/tags/v2", c2 + " refs/pull/4/head");
    }

    @Test
    void fetch_prunesADeletedBranch_andItsCommitBecomesUnreachable() throws Exception {
        var gone = fixture.commit(c1, "g.txt", "g\n");
        fixture.ref("refs/heads/gone", gone);
        mirror.fetch().await().unwrap();

        assertThat(fixture.mirrorRefs()).contains(gone + " refs/heads/gone");
        assertThat(isReachable(gone)).isTrue();

        fixture.deleteRef("refs/heads/gone");
        mirror.fetch().await().unwrap();

        assertThat(fixture.mirrorRefs()).doesNotContain(gone + " refs/heads/gone");
        assertThat(isReachable(gone)).isFalse();
    }

    /// The fetch names only the three refspecs: a merge ref the origin adds after the clone never reaches the mirror,
    /// while the PR head beside it does.
    @Test
    void fetch_neverBringsPullMergeRefs() throws Exception {
        mirror.fetch().await().unwrap();
        var head = fixture.commit(c1, "p.txt", "p\n");
        var merge = fixture.commit(head, "m.txt", "m\n");
        fixture.ref("refs/pull/5/head", head);
        fixture.ref("refs/pull/5/merge", merge);

        mirror.fetch().await().unwrap();

        assertThat(fixture.mirrorRefs()).contains(head + " refs/pull/5/head")
                                        .noneMatch(line -> line.endsWith("refs/pull/5/merge"));
    }

    @Test
    void fetch_fails_whenTheOriginIsUnreachable() {
        var lost = GitMirror.gitMirror(ProcessRunner.processRunner(), "file://" + dir.resolve("nowhere.git"), fixture.mirror).unwrap();

        assertThat(lost.fetch().await().isFailure()).isTrue();
        assertThat(mirror.fetch().await().isSuccess()).isTrue();
    }

    @Test
    void fetch_fails_whenTheMirrorIsBroken() throws Exception {
        Files.createDirectories(fixture.mirror);

        assertThat(mirror.fetch().await().isFailure()).isTrue();
    }

    // ---- reachable -----------------------------------------------------------------------------------------------
    /// Each commit is reachable from one ref only. The merge ref and the `refs/keep` ref are in the mirror (a `--mirror`
    /// clone copies every ref), so their commits are present and only the ref patterns can make them unreachable.
    @Test
    void reachable_throughABranchATagAndAPullHead_neverThroughOtherRefs() throws Exception {
        var onBranch = fixture.commit(c1, "b.txt", "b\n");
        var onTag = fixture.commit(c1, "t.txt", "t\n");
        var onPull = fixture.commit(c1, "p.txt", "p\n");
        var onMerge = fixture.commit(c1, "m.txt", "m\n");
        var onKeep = fixture.commit(c1, "k.txt", "k\n");
        fixture.ref("refs/heads/topic", onBranch);
        fixture.ref("refs/tags/archive/x", onTag);
        fixture.ref("refs/pull/3/head", onPull);
        fixture.ref("refs/pull/4/merge", onMerge);
        fixture.ref("refs/keep/k", onKeep);
        mirror.fetch().await().unwrap();

        assertThat(fixture.mirrorRefs()).contains(onMerge + " refs/pull/4/merge", onKeep + " refs/keep/k");
        assertThat(reachable(onBranch, onTag, onPull, onMerge, onKeep, c0)).isEqualTo(Map.of(sha(onBranch), true,
                                                                                            sha(onTag), true,
                                                                                            sha(onPull), true,
                                                                                            sha(onMerge), false,
                                                                                            sha(onKeep), false,
                                                                                            sha(c0), true));
    }

    @Test
    void reachable_isFalse_forAnObjectTheMirrorLacks() throws Exception {
        mirror.fetch().await().unwrap();

        assertThat(reachable(MISSING, c1)).isEqualTo(Map.of(sha(MISSING), false, sha(c1), true));
    }

    @Test
    void reachable_answersEverySha_acrossBatches() throws Exception {
        var many = new ArrayList<String>();
        var parent = c1;
        for (var i = 0; i < 20; i++) {
            parent = fixture.empty(parent);
            many.add(parent);
        }
        fixture.ref("refs/heads/long", parent);
        many.add(MISSING);
        mirror.fetch().await().unwrap();

        var answers = reachable(many.toArray(String[]::new));

        assertThat(answers).hasSize(21);
        assertThat(answers.get(sha(MISSING))).isFalse();
        assertThat(answers.entrySet().stream().filter(Map.Entry::getValue).count()).isEqualTo(20);
    }

    @Test
    void reachable_isEmpty_forNoShas() throws Exception {
        mirror.fetch().await().unwrap();

        assertThat(mirror.reachable(Set.of()).await().unwrap()).isEmpty();
    }

    /// Within one fetch generation an answer is remembered, even when the mirror changes behind the adapter's back;
    /// the next fetch starts a new generation, so both a lost ref and a newly fetched commit are seen.
    @Test
    void reachable_isMemoisedPerFetchGeneration() throws Exception {
        var kept = fixture.commit(c1, "y.txt", "y\n");
        var later = fixture.commit(c1, "z.txt", "z\n");
        fixture.ref("refs/heads/y", kept);
        mirror.fetch().await().unwrap();

        assertThat(isReachable(kept)).isTrue();
        assertThat(isReachable(later)).isFalse();

        fixture.deleteMirrorRef("refs/heads/y");
        fixture.deleteRef("refs/heads/y");
        fixture.ref("refs/heads/z", later);

        assertThat(isReachable(kept)).isTrue();
        assertThat(isReachable(later)).isFalse();

        mirror.fetch().await().unwrap();

        assertThat(isReachable(kept)).isFalse();
        assertThat(isReachable(later)).isTrue();
    }

    /// A failed fetch also starts a new generation: it may have moved some refs before failing.
    @Test
    void reachable_forgetsItsAnswers_afterAFailedFetch() throws Exception {
        var kept = fixture.commit(c1, "y.txt", "y\n");
        fixture.ref("refs/heads/y", kept);
        mirror.fetch().await().unwrap();
        assertThat(isReachable(kept)).isTrue();
        fixture.deleteMirrorRef("refs/heads/y");
        var failing = GitMirror.gitMirror(ProcessRunner.processRunner(), "file://" + dir.resolve("nowhere.git"), fixture.mirror).unwrap();

        assertThat(failing.reachable(Set.of(sha(kept))).await().unwrap()).isEqualTo(Map.of(sha(kept), false));
        assertThat(isReachable(kept)).isTrue();

        Files.move(fixture.origin, dir.resolve("moved.git"));
        assertThat(mirror.fetch().await().isFailure()).isTrue();

        assertThat(isReachable(kept)).isFalse();
    }

    @Test
    void reachable_fails_whenTheMirrorIsAbsent_neverReadingFalse() {
        var answer = mirror.reachable(Set.of(sha(c1))).await();

        assertThat(answer.isFailure()).isTrue();
        answer.onFailure(cause -> assertThat(cause).isInstanceOf(MirrorError.Failed.class));
    }

    // ---- ancestry ------------------------------------------------------------------------------------------------
    /// main: c0 - c1; PR #1: c1 - a1 - a2; PR #2 stacked on #1: a2 - b1 - b2.
    @Test
    void ancestry_overAStackedChain() throws Exception {
        var a1 = fixture.commit(c1, "a1.txt", "1\n");
        var a2 = fixture.commit(a1, "a2.txt", "2\n");
        var b1 = fixture.commit(a2, "b1.txt", "1\n");
        var b2 = fixture.commit(b1, "b2.txt", "2\n");
        fixture.ref("refs/pull/1/head", a2);
        fixture.ref("refs/pull/2/head", b2);
        fixture.ref("refs/pull/9/merge", b2);
        mirror.fetch().await().unwrap();

        assertThat(ancestry(a1, "main")).isEqualTo(new Ancestry(true, false, prs(1, 2), shas(a1)));
        assertThat(ancestry(b2, "main")).isEqualTo(new Ancestry(true, false, prs(2), shas(b2, b1, a2, a1)));
        assertThat(ancestry(c1, "main")).isEqualTo(new Ancestry(true, true, prs(1, 2), Set.of()));
    }

    @Test
    void ancestry_atBase_isTrueBehindABaseTip_andFalseOffIt() throws Exception {
        var off = fixture.commit(c1, "o.txt", "o\n");
        fixture.ref("refs/heads/off", off);
        mirror.fetch().await().unwrap();

        assertThat(ancestry(c0, "main").atBase()).isTrue();
        assertThat(ancestry(off, "main").atBase()).isFalse();
        assertThat(ancestry(off, "main").offBaseCommitsOfHead()).isEqualTo(shas(off));
    }

    /// A tag or a PR head named like a base never stands in for it: the base is named as `refs/heads/<base>`.
    @Test
    void ancestry_atBase_readsEveryConfiguredBase_andOnlyBranches() throws Exception {
        var r1 = fixture.commit(c0, "r.txt", "r\n");
        fixture.ref("refs/heads/release", r1);
        fixture.ref("refs/tags/main", r1);
        mirror.fetch().await().unwrap();

        assertThat(ancestry(r1, "main", "release").atBase()).isTrue();
        assertThat(ancestry(r1, "main", "release").offBaseCommitsOfHead()).isEmpty();
        assertThat(ancestry(r1, "main").atBase()).isFalse();
        assertThat(ancestry(r1, "main").offBaseCommitsOfHead()).isEqualTo(shas(r1));
    }

    @Test
    void ancestry_atBase_isFalse_forNoBases() throws Exception {
        mirror.fetch().await().unwrap();

        assertThat(ancestry(c1).atBase()).isFalse();
        assertThat(ancestry(c1, "main").atBase()).isTrue();
    }

    @Test
    void ancestry_offBaseCommits_areCutAt500() throws Exception {
        var parent = c1;
        for (var i = 0; i < 501; i++) {
            parent = fixture.empty(parent);
        }
        fixture.ref("refs/pull/7/head", parent);
        mirror.fetch().await().unwrap();

        assertThat(ancestry(parent, "main").offBaseCommitsOfHead()).hasSize(500);
    }

    @Test
    void ancestry_headNotInMirror_isNotAtBase_andLinksNothing() throws Exception {
        var unpushed = fixture.commit(c1, "u.txt", "u\n");
        mirror.fetch().await().unwrap();

        assertThat(ancestry(unpushed, "main")).isEqualTo(new Ancestry(false, false, Set.of(), Set.of()));
        assertThat(ancestry(c1, "main").headInMirror()).isTrue();
    }

    @Test
    void ancestry_fails_whenABaseIsAbsentFromTheMirror() throws Exception {
        mirror.fetch().await().unwrap();

        assertThat(mirror.ancestry(sha(c1), List.of(branch("main"), branch("gone"))).await().isFailure()).isTrue();
        assertThat(mirror.ancestry(sha(c1), List.of(branch("main"))).await().isSuccess()).isTrue();
    }

    @Test
    void ancestry_fails_whenTheMirrorIsAbsent_neverReadingNotInMirror() {
        Result<Ancestry> answer = mirror.ancestry(sha(c1), List.of(branch("main"))).await();

        assertThat(answer.isFailure()).isTrue();
    }
}
