package org.pragmatica.kiln.service.mirror;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.mirror.MirrorFixture.branch;
import static org.pragmatica.kiln.service.mirror.MirrorFixture.sha;

/// The WP7 verifier's attacks on [GitMirror] against a real origin ([MirrorFixture]), kept as regression tests: a
/// corrupt object, a killed presence check and a shallow mirror are failures, never "absent"; an option-shaped origin
/// runs no command; answers stay fresh across interleaved fetches.
class GitMirrorAdversarialTest {
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

    private Result<Map<Sha, Boolean>> reachable(GitMirror on, String... shas) {
        return on.reachable(Set.of(shas).stream().map(MirrorFixture::sha).collect(Collectors.toSet())).await();
    }

    /// A commit fetched after the clone arrives as a loose object (fetch.unpackLimit); garbage replaces it.
    private String corruptLooseCommit() throws Exception {
        mirror.fetch().await().unwrap();
        var x = fixture.commit(c1, "x.txt", "x\n");
        fixture.ref("refs/heads/x", x);
        mirror.fetch().await().unwrap();
        var loose = fixture.mirror.resolve("objects/" + x.substring(0, 2) + "/" + x.substring(2));

        assertThat(Files.exists(loose)).as("loose object").isTrue();
        assertThat(reachable(mirror, x).unwrap().get(sha(x))).as("control: intact, reachable").isTrue();
        loose.toFile().setWritable(true);
        Files.writeString(loose, "garbage");
        return x;
    }

    // ---- presence failures ---------------------------------------------------------------------------------------
    @Test
    void A1_corruptObject_reachable_fails_neverReadsFalse() throws Exception {
        var x = corruptLooseCommit();
        var fresh = fixture.gitMirror();

        assertThat(reachable(fresh, x).isFailure()).as("reachable over a corrupt object").isTrue();
    }

    @Test
    void A2_corruptObject_ancestry_fails_neverReadsNotInMirror() throws Exception {
        var x = corruptLooseCommit();
        Result<Ancestry> answer = mirror.ancestry(sha(x), List.of(branch("main"))).await();

        assertThat(answer.isFailure()).as("ancestry over a corrupt object: " + answer).isTrue();
        assertThat(mirror.ancestry(sha(c1), List.of(branch("main"))).await().isSuccess()).isTrue();
    }

    @Test
    void A3_corruptObject_behind_fails_neverReadsUnknown() throws Exception {
        var x = corruptLooseCommit();
        Result<BehindFacts> answer = mirror.behind(sha(x), branch("main")).await();

        assertThat(answer.isFailure()).as("behind over a corrupt object: " + answer).isTrue();
        assertThat(mirror.behind(sha(c1), branch("main")).await().isSuccess()).isTrue();
    }

    /// The presence check killed (rc 137, e.g. the OOM killer) in a healthy mirror; the control runs it unharmed.
    @Test
    void A4_presenceCheckKilled_reachable_fails_neverReadsFalse() throws Exception {
        mirror.fetch().await().unwrap();
        var real = ProcessRunner.processRunner();
        ProcessRunner killing = new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                return command.argv().contains("--verify")
                       ? Promise.success(Completed.completed("git", 137, new byte[0], new byte[0], false))
                       : real.run(command);
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                return real.spawn(command);
            }
        };
        var killed = GitMirror.gitMirror(killing, fixture.originUrl(), fixture.mirror).unwrap();

        assertThat(reachable(killed, c1).isFailure()).as("reachable with the presence check killed").isTrue();
        assertThat(reachable(fixture.gitMirror(), c1).unwrap().get(sha(c1))).isTrue();
    }

    /// An operator-made shallow mirror answers nothing, though main reaches c0 on the origin; a fetch does not deepen
    /// it. Once the operator removes it, the next fetch clones a whole mirror and the same adapter answers again.
    @Test
    void A5_shallowMirror_failsEveryMethod_untilMadeWhole() throws Exception {
        Files.createDirectories(fixture.mirror.getParent());
        MirrorFixture.git(dir, "clone", "-q", "--mirror", "--depth", "1", fixture.originUrl(), fixture.mirror.toString());

        assertThat(mirror.fetch().await().isFailure()).as("fetch leaves it shallow").isTrue();
        assertThat(reachable(mirror, c0).isFailure()).isTrue();
        assertThat(mirror.behind(sha(c1), branch("main")).await().isFailure()).isTrue();
        assertThat(mirror.ancestry(sha(c1), List.of(branch("main"))).await().isFailure()).isTrue();

        try (var paths = Files.walk(fixture.mirror)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }

        assertThat(mirror.fetch().await().isSuccess()).isTrue();
        assertThat(reachable(mirror, c0).unwrap().get(sha(c0))).isTrue();
    }

    @Test
    void A6_nonBareMirrorPath_fails() throws Exception {
        var onWork = GitMirror.gitMirror(ProcessRunner.processRunner(), fixture.originUrl(), fixture.work).unwrap();

        assertThat(reachable(onWork, c1).isFailure()).isTrue();
        assertThat(onWork.behind(sha(c1), branch("main")).await().isFailure()).isTrue();
        assertThat(onWork.ancestry(sha(c1), List.of(branch("main"))).await().isFailure()).isTrue();
        assertThat(onWork.fetch().await().isFailure()).isTrue();
    }

    // ---- argument injection --------------------------------------------------------------------------------------
    /// An option-shaped origin is refused by the factory, so no fetch runs and no command is run through it.
    @Test
    void B1_optionShapedOrigin_isRefused_andRunsNoCommand() throws Exception {
        mirror.fetch().await().unwrap();
        var marker = dir.resolve("PWNED");
        var hostile = GitMirror.gitMirror(ProcessRunner.processRunner(),
                                          "--upload-pack=touch " + marker + ";git-upload-pack",
                                          fixture.mirror);
        Result<Unit> fetched = hostile.async()
                                      .flatMap(GitMirror::fetch)
                                      .await();

        assertThat(fetched.isFailure()).isTrue();
        assertThat(Files.exists(marker)).as("origin ran a command").isFalse();
    }

    // ---- memo, prune, concurrency --------------------------------------------------------------------------------
    @Test
    void C1_deletedBranch_unreachableAfterNextFetch_evenIfMemoised() throws Exception {
        var gone = fixture.commit(c1, "g.txt", "g\n");
        fixture.ref("refs/heads/gone", gone);
        mirror.fetch().await().unwrap();

        assertThat(reachable(mirror, gone).unwrap().get(sha(gone))).isTrue();
        assertThat(reachable(mirror, gone).unwrap().get(sha(gone))).isTrue();

        fixture.deleteRef("refs/heads/gone");
        mirror.fetch().await().unwrap();

        assertThat(reachable(mirror, gone).unwrap().get(sha(gone))).isFalse();
    }

    @Test
    void C2_reachableDuringFetches_neverFails_andIsFreshAfterEach() throws Exception {
        mirror.fetch().await().unwrap();
        var tips = new ArrayList<String>();
        for (var i = 0; i < 6; i++) {
            tips.add(fixture.commit(c1, "t" + i + ".txt", "t\n"));
        }
        for (var round = 0; round < 12; round++) {
            var on = round % 2 == 0;
            for (var i = 0; i < tips.size(); i++) {
                if (on) {
                    fixture.ref("refs/heads/t" + i, tips.get(i));
                } else {
                    fixture.deleteRef("refs/heads/t" + i);
                }
            }
            var queries = new ArrayList<Promise<Map<Sha, Boolean>>>();
            var fetches = new ArrayList<Promise<Unit>>();
            for (var k = 0; k < 4; k++) {
                fetches.add(mirror.fetch());
                queries.add(mirror.reachable(tips.stream().map(MirrorFixture::sha).collect(Collectors.toSet())));
            }
            for (var f : fetches) {
                assertThat(f.await().isSuccess()).as("fetch round " + round).isTrue();
            }
            for (var q : queries) {
                assertThat(q.await().isSuccess()).as("query during fetch round " + round + ": " + q.await()).isTrue();
            }
            var fresh = reachable(mirror, tips.toArray(String[]::new)).unwrap();
            assertThat(fresh.values()).as("round " + round).containsOnly(on);
        }
    }

    // ---- ancestry ------------------------------------------------------------------------------------------------
    @Test
    void E1_headEqualsBaseTip() throws Exception {
        mirror.fetch().await().unwrap();

        assertThat(mirror.ancestry(sha(c1), List.of(branch("main"))).await().unwrap()).isEqualTo(new Ancestry(true,
                                                                                                             true,
                                                                                                             Set.of(),
                                                                                                             Set.of()));
    }
}
