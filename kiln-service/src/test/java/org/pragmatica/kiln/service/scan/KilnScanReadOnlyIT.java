package org.pragmatica.kiln.service.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.core.scan.ScanLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.scan.ScanFixture.commit;
import static org.pragmatica.kiln.service.scan.ScanFixture.complete;
import static org.pragmatica.kiln.service.scan.ScanFixture.git;
import static org.pragmatica.kiln.service.scan.ScanFixture.gitRan;
import static org.pragmatica.kiln.service.scan.ScanFixture.repo;
import static org.pragmatica.kiln.service.scan.ScanFixture.run;
import static org.pragmatica.kiln.service.scan.ScanFixture.scanned;
import static org.pragmatica.kiln.service.scan.ScanFixture.snapshot;
import static org.pragmatica.kiln.service.scan.ScanFixture.transport;

/// K-3 (kiln-f1-design §7.2, spec §5.2 "it writes nothing"): a scan never writes into a checkout's git dir, so an
/// agent's concurrent `git commit` never meets kiln's `index.lock`.
///
/// Probe (recorded in the WP4 state file): drop `GIT_OPTIONAL_LOCKS=0` AND `--no-optional-locks` from the script's git
/// wrapper (either one alone keeps git from taking the lock: the flag sets the variable for git's children) →
/// [#scan_leavesEveryFileUnderGit_byteAndMtimeIdentical_onAStatDirtyIndex] is red every time, since `git status`
/// refreshes the stat-dirty index. Arm (b) alone can miss the race, which is why arm (c) exists.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KilnScanReadOnlyIT {
    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2001-01-01T00:00:00Z"));

    @TempDir
    Path home;

    /// Arm (a): with `chmod -R a-w .git` the scan reads the same facts as the writable run.
    @Test
    void scan_readsTheSameFacts_fromAReadOnlyGitDir() throws IOException {
        var a = dirtyCheckout(home.resolve("work/co/a"));
        var gitDir = a.resolve(".git");
        var writable = facts();

        chmod("a-w", gitDir);
        try {
            assertThat(Files.isWritable(gitDir)).as("the git dir is read-only (the arm's precondition)").isFalse();
            assertThat(gitRan(a, "update-ref", "refs/heads/probe", "HEAD").rc()).as("git cannot write it").isNotZero();

            assertThat(facts()).isEqualTo(writable);
        } finally {
            chmod("u+w", gitDir);
        }
        assertThat(writable).hasSize(1);
    }

    /// Arm (b): 50 scans while another process commits in a loop: no commit fails on `index.lock`.
    @Test
    void scan_neverFailsAConcurrentCommit() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var transport = transport(home);
        var stop = new AtomicBoolean();
        var loop = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        // The loop runs on its own thread, never the common pool: on a 2-CPU host that pool has one worker, and the
        // JDK completes Process.onExit() there, so a loop holding it left every scan's exit unseen (PR #59 CI).
        var commits = loop.submit(() -> commitUntil(a, stop));
        Commits failures;

        try {
            for (var i = 0; i < 50; i++) {
                complete(scanned(transport, List.of("~/work/co/*"), List.of()));
            }
        } finally {
            // stopped before @TempDir cleanup, whatever the scans did
            stop.set(true);
            failures = stopped(commits, loop);
        }
        assertThat(failures.attempts()).as("the loop committed while the scans ran").isGreaterThan(5);
        assertThat(failures.lockFailures()).as("commits that met index.lock").isEmpty();
    }

    record Commits(int attempts, List<String> lockFailures) {}

    /// The loop's result once it has stopped (within a minute), its thread shut down.
    private static Commits stopped(Future<Commits> commits, ExecutorService loop) {
        try {
            return commits.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("the commit loop did not stop", e);
        } finally {
            loop.shutdownNow();
        }
    }

    private static Commits commitUntil(Path repo, AtomicBoolean stop) {
        var lockFailures = new ArrayList<String>();
        var attempts = 0;
        while (!stop.get()) {
            attempts++;
            var ran = run(repo,
                          Map.of("GIT_CONFIG_NOSYSTEM", "1",
                                 "GIT_CONFIG_GLOBAL", "/dev/null",
                                 "GIT_AUTHOR_NAME", "agent",
                                 "GIT_AUTHOR_EMAIL", "agent@example.org",
                                 "GIT_COMMITTER_NAME", "agent",
                                 "GIT_COMMITTER_EMAIL", "agent@example.org"),
                          "",
                          List.of("bash", "-c", "echo " + attempts + " > f && git add f && git commit -q -m " + attempts));
            if (ran.rc() != 0 && ran.stderr().contains("index.lock")) {
                lockFailures.add(ran.stderr());
            }
        }
        return new Commits(attempts, lockFailures);
    }

    /// Arm (c), deterministic: a tracked file touched so the index is stat-dirty, then a full scan leaves every file
    /// under `.git` byte- and mtime-identical. The control: plain `git status` on a twin checkout rewrites its index,
    /// so the snapshot does see the write the scan must not make.
    @Test
    void scan_leavesEveryFileUnderGit_byteAndMtimeIdentical_onAStatDirtyIndex() throws IOException {
        var a = dirtyCheckout(home.resolve("work/co/a"));
        var twin = dirtyCheckout(home.resolve("twin"));
        var twinBefore = snapshot(twin.resolve(".git"));
        var control = gitRan(twin, "status", "--porcelain");
        assertThat(control.rc()).isZero();
        assertThat(snapshot(twin.resolve(".git"))).as("control: plain git status writes the index")
                                                   .isNotEqualTo(twinBefore);
        var before = snapshot(a.resolve(".git"));

        var rows = complete(scanned(transport(home), List.of("~/work/co/*"), List.of())).rows();

        assertThat(rows).singleElement().isInstanceOf(ScanLine.CheckoutLine.class);
        assertThat(snapshot(a.resolve(".git"))).isEqualTo(before);
    }

    /// A checkout with a modified file, an untracked one, a stash and a stat-dirty index (a tracked file whose
    /// mtime no longer matches the index, content unchanged).
    private static Path dirtyCheckout(Path dir) throws IOException {
        var a = repo(dir);
        commit(a, "g", "kept\n");
        Files.writeString(a.resolve("f"), "stashed\n");
        git(a, "stash", "-q");
        Files.writeString(a.resolve("f"), "modified\n");
        Files.writeString(a.resolve("u"), "untracked\n");
        Files.setLastModifiedTime(a.resolve("g"), LONG_AGO);
        return a;
    }

    private List<ScanLine> facts() {
        return complete(scanned(transport(home), List.of("~/work/co/*"), List.of())).rows();
    }

    private static void chmod(String mode, Path dir) {
        assertThat(run(dir.getParent(), Map.of(), "", List.of("chmod", "-R", mode, dir.toString())).rc()).isZero();
    }
}
