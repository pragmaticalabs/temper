package org.pragmatica.kiln.service.scan;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.assertj.core.api.SoftAssertions;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.derive.DeriveJob;
import org.pragmatica.kiln.service.mirror.GitMirror;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.KilnPgFixture;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.scan.ScanFixture.commit;
import static org.pragmatica.kiln.service.scan.ScanFixture.git;
import static org.pragmatica.kiln.service.scan.ScanFixture.gitRan;
import static org.pragmatica.kiln.service.scan.ScanFixture.real;
import static org.pragmatica.kiln.service.scan.ScanFixture.repo;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// K-1 (kiln-f1-design §7.2): the preservation rules end to end. Fixture checkouts in a `@TempDir` home are scanned by
/// the real `kiln-scan` through [LocalScanTransport] and a [HostScanner] full pass into Postgres; a local bare origin is
/// fetched into a real [GitMirror]; a second bare at `$HOME/work/pragmatica.git` answers `reach`; [DeriveJob] derives,
/// and each checkout's `checkout_derived` preservation is read back. One row per rule, plus the control:
///
/// | checkout | case | reads |
/// |---|---|---|
/// | `preserved` | clean, every tip pushed (control) | PRESERVED |
/// | `stash-wt` | a sibling worktree (`git worktree add`) of a repository with a stash | `stash:1` |
/// | `rebase` | `git rebase` stopped on a conflict, the conflict resolved to HEAD's content, not continued | `op:rebase` |
/// | `archived` | a detached HEAD on a pushed `archive/x` tag that no branch contains | PRESERVED |
/// | `bareonly` | a branch pushed only to the host's own bare | HOST-BARE-ONLY |
///
/// Mutations (each recorded in the WP8 state file, each turning one row red): drop the stash term of
/// `Preservation.tree` (`stash-wt` reads PRESERVED); drop the `op` term (`rebase` reads PRESERVED); drop `refs/tags`
/// from the mirror's reachability patterns (`archived` reads unpreserved); map a host-bare `reach` answer to
/// `Reach.MIRROR` in [DeriveJob] (`bareonly` reads PRESERVED).
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PreservationPipelineIT {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(120).seconds();
    private static final HostName BIGBOY = HostName.hostName("bigboy").unwrap();
    private static final List<String> ROOTS = List.of("~/work/co/*");

    @TempDir
    Path dir;

    private KilnStore store;

    @BeforeEach
    void store() {
        store = KilnPgFixture.freshStore();
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close().await(WAIT);
        }
    }

    @Test
    void eachPreservationRule_readsEndToEnd() throws IOException {
        var home = Files.createDirectories(dir.resolve("home"));
        var co = Files.createDirectories(home.resolve("work/co"));
        var origin = dir.resolve("origin.git");
        var hostBare = home.resolve("work/pragmatica.git");

        var seed = repo(dir.resolve("seed"));
        git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        git(seed, "remote", "add", "origin", origin.toString());
        git(seed, "push", "-q", "origin", "main");
        git(seed, "checkout", "-q", "-b", "topic");
        commit(seed, "f", "topic\n");
        git(seed, "push", "-q", "origin", "topic");
        git(seed, "checkout", "-q", "main");
        commit(seed, "f", "main two\n");
        git(seed, "push", "-q", "origin", "main");
        git(seed, "checkout", "-q", "--detach", "main");
        var archivedTip = commit(seed, "g", "archived\n");
        git(seed, "tag", "archive/x");
        git(seed, "push", "-q", "origin", "refs/tags/archive/x");
        git(seed, "checkout", "-q", "main");

        var preserved = clone(origin, co, "preserved");

        var stash = clone(origin, co, "stash");
        git(stash, "worktree", "add", "-q", "-b", "side", co.resolve("stash-wt").toString());
        Files.writeString(stash.resolve("f"), "stashed\n");
        git(stash, "stash", "-q");

        var rebase = clone(origin, co, "rebase");
        git(rebase, "checkout", "-q", "topic");
        assertThat(gitRan(rebase, "rebase", "main").rc()).as("the rebase stops on its conflict").isNotZero();
        git(rebase, "checkout", "HEAD", "--", "f");
        assertThat(git(rebase, "status", "--porcelain")).as("the conflict resolved to HEAD's content").isEmpty();

        var archived = clone(origin, co, "archived");
        git(archived, "checkout", "-q", "--detach", archivedTip);

        var bareOnly = clone(origin, co, "bareonly");
        git(bareOnly, "checkout", "-q", "-b", "wip");
        commit(bareOnly, "h", "wip\n");
        git(dir, "init", "-q", "--bare", "-b", "main", hostBare.toString());
        git(bareOnly, "push", "-q", hostBare.toString(), "wip");

        var mirror = GitMirror.gitMirror(ProcessRunner.processRunner(), "file://" + origin, dir.resolve("mirror.git")).unwrap();
        await(mirror.fetch());
        var host = ScanHost.scanHost(BIGBOY, ROOTS, ScanFixture.transport(home));
        var scanner = HostScanner.hostScanner(host, store.checkouts(), store.sources(), ScanFakes::t0, Duration.ofHours(48), () -> {});
        var derive = DeriveJob.deriveJob(store.checkouts(), store.prs(), mirror, List.of(host), "pragmaticalabs/pragmatica",
                                         List.of(BranchName.branchName("main").unwrap()), Duration.ofHours(48), ScanFakes::t0);

        assertThat(scanner.full().await(WAIT).unwrap()).isEqualTo(some(new SourceState.Ok(ScanFakes.T0)));
        await(derive.derive());

        var read = preservations();
        assertThat(read).containsOnlyKeys(real(preserved), real(stash), real(co.resolve("stash-wt")), real(rebase), real(archived),
                                          real(bareOnly));
        // Soft: a probe of one rule shows every row, so it is seen to turn exactly its own row red.
        var rows = new SoftAssertions();
        rows.assertThat(read.get(real(preserved))).as("control").isInstanceOf(Preservation.Preserved.class);
        rows.assertThat(read.get(real(co.resolve("stash-wt")))).as("sibling stash").isEqualTo(new Preservation.Unpreserved(List.of("stash:1")));
        rows.assertThat(read.get(real(stash))).as("stash").isEqualTo(new Preservation.Unpreserved(List.of("stash:1")));
        rows.assertThat(read.get(real(rebase))).as("rebase stopped").isEqualTo(new Preservation.Unpreserved(List.of("op:rebase")));
        rows.assertThat(read.get(real(archived))).as("a pushed archive tag").isInstanceOf(Preservation.Preserved.class);
        rows.assertThat(read.get(real(bareOnly))).as("only in the host's bare").isInstanceOf(Preservation.HostBareOnly.class);
        rows.assertAll();
    }

    private Map<String, Preservation> preservations() {
        return await(store.views().checkouts(none(), some(BIGBOY), 1)).rows()
                                                                     .stream()
                                                                     .collect(Collectors.toMap(view -> view.row().path().value(),
                                                                                               PreservationPipelineIT::preservation));
    }

    private static Preservation preservation(CheckoutView view) {
        assertThat(view.derived().isPresent()).as("%s is derived", view.row().path()).isTrue();
        return view.derived().unwrap().derived().preservation();
    }

    private static Path clone(Path origin, Path co, String name) {
        var target = co.resolve(name);
        git(co, "clone", "-q", origin.toString(), target.toString());
        return target;
    }

    private static <T> T await(Promise<T> promise) {
        return promise.await(WAIT).unwrap();
    }
}
