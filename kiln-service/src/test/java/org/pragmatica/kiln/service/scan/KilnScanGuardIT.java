package org.pragmatica.kiln.service.scan;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.ScanLine.CheckoutLine;
import org.pragmatica.kiln.core.scan.ScanLine.StubLine;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.core.scan.ScanOutcome.Incomplete;
import org.pragmatica.kiln.service.scan.ScanFixture.Ran;
import org.pragmatica.kiln.service.scan.ScanTransportError.NonZero;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.scan.ScanFixture.GIT_ENV;
import static org.pragmatica.kiln.service.scan.ScanFixture.checkoutPath;
import static org.pragmatica.kiln.service.scan.ScanFixture.commit;
import static org.pragmatica.kiln.service.scan.ScanFixture.complete;
import static org.pragmatica.kiln.service.scan.ScanFixture.facts;
import static org.pragmatica.kiln.service.scan.ScanFixture.forced;
import static org.pragmatica.kiln.service.scan.ScanFixture.git;
import static org.pragmatica.kiln.service.scan.ScanFixture.onPath;
import static org.pragmatica.kiln.service.scan.ScanFixture.pathFirst;
import static org.pragmatica.kiln.service.scan.ScanFixture.real;
import static org.pragmatica.kiln.service.scan.ScanFixture.repo;
import static org.pragmatica.kiln.service.scan.ScanFixture.rows;
import static org.pragmatica.kiln.service.scan.ScanFixture.run;
import static org.pragmatica.kiln.service.scan.ScanFixture.scanned;
import static org.pragmatica.kiln.service.scan.ScanFixture.script;
import static org.pragmatica.kiln.service.scan.ScanFixture.snapshot;
import static org.pragmatica.kiln.service.scan.ScanFixture.transport;

/// The guarantees "never Complete when wrong" and "never runs a repository's code" of `ops/kiln/kiln-scan` (WP4 fix
/// round, findings 1-8 of the WP4 verification): facts only of the checkout's own repository, a read error is never a
/// complete listing, a branch kiln cannot hold stubs only its checkout, no filter driver runs, symbolic links under a
/// root are ignored, remotes lose their userinfo, and `size` answers only inside its root.
///
/// Probes (recorded in the WP4 state file): drop the identity check and the ceiling, the read-error exits, the branch
/// rule, the filter check, the device:inode de-duplication or the `size` confinement → the matching test goes red.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KilnScanGuardIT {
    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2001-01-01T00:00:00Z"));

    @TempDir
    Path home;

    /// Finding 1: an interrupted clone (`.git` holding only HEAD) inside an enclosing repository gets a stub, never
    /// the enclosing repository's facts; its neighbour keeps its full facts.
    @Test
    void scan_givesAStub_forAnInterruptedCloneInsideAnotherRepository() throws IOException {
        repo(home.resolve("work"));
        var half = Files.createDirectories(home.resolve("work/co/half/.git"));
        Files.writeString(half.resolve("HEAD"), "ref: refs/heads/main\n");
        var b = repo(home.resolve("work/co/b"));

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(rows.get(real(half.getParent()))).isInstanceOf(StubLine.class);
        assertThat(rows.get(real(b))).isInstanceOf(CheckoutLine.class);
    }

    /// Finding 1: a `.git` with no HEAD at all, inside an enclosing repository, is a stub too: git does not read it as
    /// the checkout's repository.
    @Test
    void scan_givesAStub_forAGitDirWithoutHead_insideAnotherRepository() throws IOException {
        repo(home.resolve("work"));
        var empty = Files.createDirectories(home.resolve("work/co/empty/.git"));

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(rows.get(real(empty.getParent()))).isInstanceOf(StubLine.class);
    }

    /// Finding 1: a repository whose `objects/` cannot be read, inside an enclosing repository, gets a stub.
    @Test
    void scan_givesAStub_forUnreadableObjects_insideAnotherRepository() throws IOException {
        repo(home.resolve("work"));
        var x = repo(home.resolve("work/co/x"));
        var objects = x.resolve(".git/objects");

        withMode(objects, "---------", () -> {
            var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));
            assertThat(rows.get(real(x))).isInstanceOf(StubLine.class);
        });
    }

    /// Finding 1: the identity check alone (the ceiling cannot help here): `core.worktree` naming another directory
    /// makes git's top level that directory, so the checkout gets a stub, not the other directory's status.
    @Test
    void scan_givesAStub_whenGitNamesAnotherTopLevel() throws IOException {
        var w = repo(home.resolve("work/co/w"));
        var other = repo(home.resolve("other"));
        git(w, "config", "core.worktree", real(other));

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(rows.get(real(w))).isInstanceOf(StubLine.class);
    }

    /// Finding 2: the root's directory cannot be listed → 74, no trailer, and the lines decode as Incomplete.
    @Test
    void scan_isIncomplete_whenTheRootCannotBeRead() throws IOException {
        repo(home.resolve("work/co/a"));

        withMode(home.resolve("work/co"), "---------", () -> incomplete(forced(home, home, Map.of(), "scan ~/work/co/*")));
    }

    /// Finding 2: a directory in the middle of the root cannot be searched → 74 and Incomplete.
    @Test
    void scan_isIncomplete_whenAMiddleComponentCannotBeRead() throws IOException {
        repo(home.resolve("work/co/a"));

        withMode(home.resolve("work"), "---------", () -> incomplete(forced(home, home, Map.of(), "scan ~/work/co/*")));
    }

    /// Finding 2: a matched directory that cannot be read is never non-git → 74 and Incomplete.
    @Test
    void scan_isIncomplete_whenAMatchCannotBeRead() throws IOException {
        repo(home.resolve("work/co/a"));
        repo(home.resolve("work/co/b"));

        withMode(home.resolve("work/co/b"), "---------", () -> incomplete(forced(home, home, Map.of(), "scan ~/work/co/*")));
    }

    /// Finding 2: a checkout's `.git` directory that cannot be read → 74 and Incomplete.
    @Test
    void scan_isIncomplete_whenADotGitCannotBeRead() throws IOException {
        var a = repo(home.resolve("work/co/a"));

        withMode(a.resolve(".git"), "---------", () -> incomplete(forced(home, home, Map.of(), "scan ~/work/co/*")));
    }

    /// Finding 2: a worktree's `.git` file that cannot be read → 74 and Incomplete.
    @Test
    void scan_isIncomplete_whenADotGitFileCannotBeRead() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        git(a, "worktree", "add", "-q", "../wt", "-b", "wt");

        withMode(home.resolve("work/co/wt/.git"),
                 "---------",
                 () -> incomplete(forced(home, home, Map.of(), "scan ~/work/co/*")));
    }

    /// Finding 2: a `stat` that fails (a fake `stat` first on `PATH` refuses every git dir HEAD) → 74 and Incomplete;
    /// through the transport the call fails with 74.
    @Test
    void scan_isIncomplete_whenAStatFails() throws IOException {
        repo(home.resolve("work/co/a"));
        var bin = home.resolve("bin");
        script(bin.resolve("stat"),
               "for a in \"$@\"; do case $a in */HEAD) exit 1 ;; esac; done; exec " + onPath("stat") + " \"$@\"\n");
        var env = pathFirst(bin);

        incomplete(forced(home, home, env, "scan ~/work/co/*"));
        var failure = transport(home, env).scan(List.of("~/work/co/*"), List.of()).await();

        Cause cause = failure.fold(origin -> origin, _ -> null);
        assertThat(cause).isInstanceOf(NonZero.class);
        assertThat(((NonZero) cause).rc()).isEqualTo(74);
    }

    /// A worktree of a repository whose name is not UTF-8 is a checkout whose common dir no JSON line can spell: a stub
    /// with that common dir less its invalid bytes, never a non-git row (which would lose its row); the scan stays
    /// complete. ZFS with utf8only (bigboy's /tmp) refuses such a name, so the repository goes under the first of the
    /// temp dir and /dev/shm that holds it; none of them failing the test, never skipping it.
    @Test
    void scan_givesAStub_forAWorktreeWhoseCommonDirIsNotUtf8() throws IOException {
        var base = nonUtf8Capable();
        try {
            var wt = home.resolve("work/co/wt");
            Files.createDirectories(wt.getParent());
            var made = run(base,
                                       GIT_ENV,
                                       "",
                                       List.of("bash",
                                               "-c",
                                               "set -e; cd \"$(printf 'b\\377d')\"; mkdir main; cd main; git init -q -b main;"
                                               + " echo 1 > f; git add f; git commit -q -m 1; git worktree add -q \"$1\" -b wt",
                                               "make",
                                               wt.toString()));
            assertThat(made.rc()).as(made.stderr()).isZero();

            var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

            assertThat(rows.get(real(wt))).isInstanceOf(StubLine.class);
            var stub = (StubLine) rows.get(real(wt));
            assertThat(stub.path()).isEqualTo(checkoutPath(wt));
            assertThat(stub.commonDir()).isEqualTo(real(base) + "/bd/main/.git");
            // git's own HEAD mtime; Java cannot reach the git dir to compare (its name is not UTF-8).
            assertThat(stub.activityAt()).isAfter(Instant.EPOCH);
        } finally {
            if (!base.startsWith(home)) {
                run(Path.of("/"), Map.of(), "", List.of("rm", "-rf", base.toString()));
            }
        }
    }

    /// A fresh directory, under the temp dir or else under /dev/shm, that holds the name `b\377d` (created there).
    private Path nonUtf8Capable() throws IOException {
        for (var parent : List.of(home, Path.of("/dev/shm"))) {
            if (Files.isDirectory(parent)) {
                var base = Files.createTempDirectory(parent, "kiln-");
                if (run(base, Map.of(), "", List.of("bash", "-c", "mkdir \"$(printf 'b\\377d')\"")).rc() == 0) {
                    return base;
                }
                Files.delete(base);
            }
        }
        throw new AssertionError("no file system here holds a name that is not UTF-8");
    }

    /// N1: a match that loses its read or search permission after the listing (while git runs in the checkout before
    /// it: a fake `git` first on `PATH` flips it) is a read error, 74 and Incomplete, never a non-git row.
    @ParameterizedTest
    @ValueSource(strings = {"---------", "rw-r--r--"})
    void scan_isIncomplete_whenAMatchBecomesUnreadableDuringTheScan(String mode) throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var b = repo(home.resolve("work/co/b"));
        var flipped = home.resolve("flipped");
        var bin = home.resolve("bin");
        script(bin.resolve("git"),
               "case \"$*\" in *\"-C " + real(a) + " \"*) [ -e '" + flipped + "' ] || { touch '" + flipped + "'; chmod "
               + octal(mode) + " '" + real(b) + "'; } ;; esac\nexec " + onPath("git") + " \"$@\"\n");

        try {
            var ran = forced(home, home, pathFirst(bin), "scan ~/work/co/*");

            assertThat(flipped).as("the race happened").exists();
            incomplete(ran);
            assertThat(ran.stdout()).doesNotContain("\"nonGit\"");
        } finally {
            Files.setPosixFilePermissions(b, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /// KILN-SCAN-74: a match that VANISHES after the listing (a lane's `bb-temper rm`; a fake `git` first on `PATH` removes it
    /// while the previous checkout is read) is skipped like a replaced symbolic link: the scan is complete (rc 0, a
    /// trailer), the vanished directory has no line and the others keep theirs. The control above (the match is still
    /// there, only unreadable) stays 74.
    @Test
    void scan_skipsAMatchThatVanishedDuringTheScan() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var b = repo(home.resolve("work/co/b"));
        var c = repo(home.resolve("work/co/c"));
        var removed = home.resolve("removed");
        var bin = home.resolve("bin");
        var vanishing = real(b);

        script(bin.resolve("git"),
               "case \"$*\" in *\"-C " + real(a)
              + " \"*) [ -e '" + removed
              + "' ] || { touch '" + removed
              + "'; rm -rf '" + vanishing
              + "'; } ;; esac\nexec " + onPath("git")
              + " \"$@\"\n");
        var ran = forced(home, home, pathFirst(bin), "scan ~/work/co/*");

        assertThat(removed).as("the race happened").exists();
        assertThat(ran.rc()).as(ran.stderr()).isZero();
        var rows = rows(complete(ran.stdout().lines().toList()));

        assertThat(rows).containsOnlyKeys(real(a), real(c));
        assertThat(ran.stdout()).doesNotContain(vanishing);
    }

    /// KILN-SCAN-74: `du` that fails once because a subdirectory vanished mid-walk is retried once and answers; a
    /// `du` that fails every time on a path that is still there stays 74 (the control, size_exits74_whenDuCannotReadAll).
    @Test
    void size_retriesDuOnce_whenASubdirectoryVanishedMidWalk() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var failed = home.resolve("du-failed");
        var bin = home.resolve("bin");

        script(bin.resolve("du"),
               "[ -e '" + failed + "' ] || { touch '" + failed + "'; exit 1; }; exec " + onPath("du") + " \"$@\"\n");
        var ran = forced(home, home, pathFirst(bin), "size ~/work/co/* " + real(a));

        assertThat(failed).as("the first du failed").exists();
        assertThat(ran.rc()).as(ran.stderr()).isZero();
        assertThat(ran.stdout()).contains("\"kb\":");
    }

    /// KILN-SCAN-74: `du` fails and the path itself is gone since: 66, as for a path that is no directory.
    @Test
    void size_exits66_whenThePathVanishedDuringDu() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var bin = home.resolve("bin");

        script(bin.resolve("du"), "rm -rf '" + real(a) + "'; exit 1\n");
        var ran = forced(home, home, pathFirst(bin), "size ~/work/co/* " + real(a));

        assertThat(ran.rc()).as(ran.stderr()).isEqualTo(66);
        assertThat(ran.stdout()).isEmpty();
    }

    /// #116 review: an ANCESTOR that loses search permission mid-scan (EACCES, not ENOENT) hides the match without removing
    /// it: not vanished, so still 74 and Incomplete, never a complete listing that drops the match.
    @Test
    void scan_isIncomplete_whenAnAncestorLosesSearchPermissionDuringTheScan() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        repo(home.resolve("work/co/b"));
        var co = home.resolve("work/co");
        var flipped = home.resolve("flipped");
        var bin = home.resolve("bin");
        script(bin.resolve("git"),
               "case \"$*\" in *\"-C " + real(a) + " \"*) [ -e '" + flipped + "' ] || { touch '" + flipped + "'; chmod 000 '"
               + real(co) + "'; } ;; esac\nexec " + onPath("git") + " \"$@\"\n");

        try {
            var ran = forced(home, home, pathFirst(bin), "scan ~/work/co/*");

            assertThat(flipped).as("the race happened").exists();
            incomplete(ran);
        } finally {
            Files.setPosixFilePermissions(co, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /// #116 review: `du` fails because an ancestor of the path became unsearchable (EACCES): the path still exists, so 74, not 66.
    @Test
    void size_exits74_whenAnAncestorBecomesUnsearchableDuringDu() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var co = home.resolve("work/co");
        var bin = home.resolve("bin");
        script(bin.resolve("du"), "chmod 000 '" + real(co) + "'; exit 1\n");

        try {
            var ran = forced(home, home, pathFirst(bin), "size ~/work/co/* " + real(a));

            assertThat(ran.rc()).as(ran.stderr()).isEqualTo(74);
            assertThat(ran.stdout()).isEmpty();
        } finally {
            Files.setPosixFilePermissions(co, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private static String octal(String mode) {
        return mode.equals("---------") ? "000" : "644";
    }

    /// N2: a partial clone whose `git status` would fetch a missing blob (a staged rename from it) gets a stub: `status`
    /// never runs there, the remote's upload-pack never runs (its marker stays absent) and `.git` is unchanged.
    @Test
    void scan_givesAStub_forAPartialClone_andRunsNothing() throws IOException {
        var marker = home.resolve("uploadpack-ran");
        var pc = partialClone(home.resolve("work/co/pc"), marker);
        var before = snapshot(pc.resolve(".git"));
        var bin = home.resolve("bin");
        var log = home.resolve("calls.log");
        script(bin.resolve("git"), "echo \"$*\" >> '" + log + "'; exec " + onPath("git") + " \"$@\"\n");

        var rows = rows(complete(scanned(transport(home, pathFirst(bin)), List.of("~/work/co/*"), List.of())));

        assertThat(rows.get(real(pc))).isInstanceOf(StubLine.class);
        assertThat(marker).doesNotExist();
        assertThat(snapshot(pc.resolve(".git"))).isEqualTo(before);
        var inPc = "-C " + real(pc) + " ";
        assertThat(Files.readAllLines(log)).as("git status never runs in the partial clone")
                                           .noneMatch(call -> call.contains(inPc) && call.contains(" status "));
    }

    /// N2, the transport layer alone: `reach` makes no partial-clone check, so with `~/work/pragmatica.git` a partial
    /// clone that even allows the file protocol in its own config, looking up a blob it lacks would lazily fetch it. No
    /// transport runs: the ref is null, the upload-pack marker stays absent, the bare is unchanged. Run twice: as is, and
    /// with a `git` first on `PATH` that drops `GIT_NO_LAZY_FETCH` (as git before 2.44, bigboy's 2.43, ignores it), so
    /// `-c protocol.allow=never` and `GIT_ALLOW_PROTOCOL=none` alone must stop the fetch.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reach_neverFetches_intoAPartialCloneBare(boolean lazyFetchFlagIgnored) throws IOException {
        var marker = home.resolve("uploadpack-ran");
        var origin = origin(home.resolve("origin.git"));
        var seed = repo(home.resolve("seed"));
        git(seed, "push", "-q", origin.toString(), "main");
        Files.createDirectories(home.resolve("work"));
        git(home.resolve("work"), "clone", "-q", "--bare", "--filter=blob:none", "file://" + origin, "pragmatica.git");
        var bare = home.resolve("work/pragmatica.git");
        git(bare, "config", "remote.origin.uploadpack", "touch '" + marker + "'; git-upload-pack");
        git(bare, "config", "protocol.file.allow", "always");
        var blob = git(seed, "rev-parse", "HEAD:f");
        var before = snapshot(bare);
        var bin = home.resolve("bin");
        script(bin.resolve("git"), "unset GIT_NO_LAZY_FETCH\nexec " + onPath("git") + " \"$@\"\n");
        var environment = lazyFetchFlagIgnored ? pathFirst(bin) : Map.<String, String>of();

        var lines = transport(home, environment).reach(Set.of(Sha.sha(blob).unwrap())).await().unwrap();

        assertThat(lines).containsExactly("{\"sha\":\"" + blob + "\",\"ref\":null}", "{\"end\":true,\"count\":1}");
        assertThat(marker).doesNotExist();
        assertThat(snapshot(bare)).isEqualTo(before);
    }

    /// A bare origin that serves partial clones.
    private static Path origin(Path dir) throws IOException {
        Files.createDirectories(dir);
        git(dir, "init", "-q", "--bare", "-b", "main");
        git(dir, "config", "uploadpack.allowFilter", "true");
        git(dir, "config", "uploadpack.allowAnySHA1InWant", "true");
        return dir;
    }

    /// A blob-less partial clone of a 3000-line file `big`, its upload-pack writing `marker`, with `big` renamed to a
    /// similar `big2` in the index, so rename detection would need the blob it lacks (the WP4 round-2 verifier's repro).
    private Path partialClone(Path dir, Path marker) throws IOException {
        var origin = origin(home.resolve("origin.git"));
        var seed = repo(home.resolve("seed"));
        var big = new StringBuilder();
        for (var i = 0; i < 3000; i++) {
            big.append(i).append('\n');
        }
        commit(seed, "big", big.toString());
        git(seed, "push", "-q", origin.toString(), "main");
        git(home, "clone", "-q", "--filter=blob:none", "--no-checkout", "file://" + origin, dir.toString());
        git(dir, "config", "remote.origin.uploadpack", "touch '" + marker + "'; git-upload-pack");
        git(dir, "reset", "-q");
        Files.writeString(dir.resolve("big2"), big + "extra\n");
        git(dir, "rm", "-q", "--cached", "big");
        git(dir, "add", "big2");
        Files.deleteIfExists(marker);
        return dir;
    }

    /// Control for finding 2: a root whose fixed part exists and whose `*` matches nothing is a complete, empty listing.
    @Test
    void scan_isComplete_whenTheRootMatchesNothing() throws IOException {
        Files.createDirectories(home.resolve("work/co"));

        assertThat(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())).rows()).isEmpty();
    }

    /// WP8 fix round, ruling 6: a root whose fixed part (before the first `*`, or the whole literal root) is missing is a
    /// read error (74, no trailer), never an empty listing, so a vanished or unmounted root deletes nothing; the other
    /// root's lines may stay, the listing is incomplete.
    @Test
    void scan_isIncomplete_whenARootsFixedPartIsMissing() throws IOException {
        var a = repo(home.resolve("work/co/a"));

        incomplete(forced(home, home, Map.of(), "scan ~/work/co/* ~/gone/*"));
        incomplete(forced(home, home, Map.of(), "scan ~/work/co/* ~/work/gone/x"));
        incomplete(forced(home, home, Map.of(), "scan ~/work/co/a/missing/*"));
        Files.writeString(home.resolve("file"), "not a directory\n");
        incomplete(forced(home, home, Map.of(), "scan ~/file/*"));
        assertThat(forced(home, home, Map.of(), "scan ~/gone/*").stderr()).contains("fixed prefix");
        assertThat(rows(complete(scanned(transport(home), List.of("~/work/co/*", "~/work/co/a"), List.of())))).containsKey(real(a));
    }

    private static void incomplete(Ran ran) {
        assertThat(ran.rc()).as(ran.stderr()).isEqualTo(74);
        assertThat(ran.stdout()).doesNotContain("\"end\"");
        assertThat(ScanOutcome.of(ran.stdout().lines().toList())).isInstanceOf(Incomplete.class);
    }

    /// Finding 3: a branch git accepts but kiln's branch rule refuses (`+plus`, U+0085, U+2028, and an unborn HEAD on
    /// `+head`) stubs that checkout only; a good name with `/`, `-`, `.` and digits keeps its full facts, and the scan
    /// stays complete.
    @Test
    void scan_givesAStub_onlyToTheCheckoutWithARefusedBranchName() throws IOException {
        var co = home.resolve("work/co");
        var plus = repo(co.resolve("plus"));
        git(plus, "branch", "+plus");
        var nel = repo(co.resolve("nel"));
        git(nel, "branch", "n\u0085x");
        var ls = repo(co.resolve("ls"));
        git(ls, "branch", "l x");
        var unborn = Files.createDirectories(co.resolve("unborn"));
        git(unborn, "init", "-q", "-b", "main");
        git(unborn, "symbolic-ref", "HEAD", "refs/heads/+head");
        var good = repo(co.resolve("good"));
        git(good, "branch", "good/name-1.x");

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(rows.get(real(plus))).isInstanceOf(StubLine.class);
        assertThat(rows.get(real(nel))).isInstanceOf(StubLine.class);
        assertThat(rows.get(real(ls))).isInstanceOf(StubLine.class);
        assertThat(rows.get(real(unborn))).isInstanceOf(StubLine.class);
        assertThat(facts(rows, good).branches()).extracting(tip -> tip.name().value()).contains("good/name-1.x");
    }

    /// Finding 4: a clean filter in `.git/config`, with `.gitattributes` naming it on a stat-dirty file, never runs (its
    /// marker stays absent, `.git` unchanged) and the checkout is a stub.
    @Test
    void scan_neverRunsAFilter_definedInTheRepositoryConfig() throws IOException {
        var a = filtered(home.resolve("work/co/a"));
        var marker = home.resolve("marker");
        git(a, "config", "filter.ev.clean", "touch '" + marker + "'; cat");

        neverRuns(a, marker);
    }

    /// Finding 4: a filter process defined in a file the repository config includes is found as well.
    @Test
    void scan_neverRunsAFilter_definedThroughAnInclude() throws IOException {
        var a = filtered(home.resolve("work/co/a"));
        var marker = home.resolve("marker");
        var included = home.resolve("included.cfg");
        Files.writeString(included, "[filter \"ev\"]\n\tprocess = touch '" + marker + "'\n");
        git(a, "config", "include.path", included.toString());

        neverRuns(a, marker);
    }

    /// Finding 4: a smudge filter alone (which `status` would not run) also stubs the checkout: any filter driver does.
    @Test
    void scan_givesAStub_forASmudgeFilter() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        git(a, "config", "filter.ev.smudge", "cat");

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(rows.get(real(a))).isInstanceOf(StubLine.class);
    }

    /// `git status` runs `git status` in a submodule, with the submodule's own config: a checkout whose index holds a
    /// submodule is a stub, and the submodule's filter never runs.
    @Test
    void scan_neverRunsASubmoduleFilter() throws IOException {
        var sub = filtered(home.resolve("sub"));
        var a = repo(home.resolve("work/co/a"));
        git(a, "-c", "protocol.file.allow=always", "submodule", "add", "-q", sub.toString(), "sub");
        git(a, "commit", "-q", "-m", "sub");
        var marker = home.resolve("marker");
        git(a.resolve("sub"), "config", "filter.ev.clean", "touch '" + marker + "'; cat");

        neverRuns(a, marker, a.resolve("sub/f"));
    }

    /// The scan reads the repository's own config only: a filter in the global config (`$HOME/.gitconfig`) or in the
    /// system config (`GIT_CONFIG_SYSTEM`) never runs, so the checkout keeps its full facts.
    @Test
    void scan_readsNeitherTheGlobalNorTheSystemConfig() throws IOException {
        var a = filtered(home.resolve("work/co/a"));
        var global = home.resolve("global-ran");
        var system = home.resolve("system-ran");
        Files.writeString(home.resolve(".gitconfig"), "[filter \"ev\"]\n\tclean = touch '" + global + "'; cat\n");
        var systemConfig = home.resolve("system.cfg");
        Files.writeString(systemConfig, "[filter \"ev\"]\n\tclean = touch '" + system + "'; cat\n");
        Files.setLastModifiedTime(a.resolve("f"), LONG_AGO);

        var rows = rows(complete(scanned(transport(home, Map.of("GIT_CONFIG_SYSTEM", systemConfig.toString())),
                                         List.of("~/work/co/*"),
                                         List.of())));

        assertThat(rows.get(real(a))).isInstanceOf(CheckoutLine.class);
        assertThat(global).doesNotExist();
        assertThat(system).doesNotExist();
    }

    /// A repository with `.gitattributes` naming filter `ev` on `f`.
    private static Path filtered(Path dir) throws IOException {
        var repo = repo(dir);
        commit(repo, ".gitattributes", "f filter=ev\n");
        return repo;
    }

    private void neverRuns(Path checkout, Path marker) throws IOException {
        neverRuns(checkout, marker, checkout.resolve("f"));
    }

    /// `dirty` made stat-dirty, then a full scan: the checkout is a stub, the marker absent, `.git` unchanged.
    private void neverRuns(Path checkout, Path marker, Path dirty) throws IOException {
        Files.setLastModifiedTime(dirty, LONG_AGO);
        var before = snapshot(checkout.resolve(".git"));

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(marker).doesNotExist();
        assertThat(rows.get(real(checkout))).isInstanceOf(StubLine.class);
        assertThat(snapshot(checkout.resolve(".git"))).isEqualTo(before);
    }

    /// Finding 8: symbolic links under a root are neither listed nor counted nor followed (one to a directory outside
    /// every root, one to a checkout under the root).
    @Test
    void scan_ignoresSymbolicLinksUnderARoot() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var outside = repo(home.resolve("outside"));
        Files.createSymbolicLink(home.resolve("work/co/esc"), outside);
        Files.createSymbolicLink(home.resolve("work/co/alias"), a);
        Files.createDirectories(home.resolve("work/co/b"));
        Files.createSymbolicLink(home.resolve("work/co/b/units"), outside);

        var lines = scanned(transport(home), List.of("~/work/co/*", "~/work/co/*/units"), List.of());

        assertThat(rows(complete(lines)).keySet()).containsExactlyInAnyOrder(real(a), real(home.resolve("work/co/b")));
        assertThat(lines.getLast()).isEqualTo("{\"end\":true,\"count\":1,\"nonGit\":1,\"nonGitBytes\":null,\"home\":\"" + real(home) + "\"}");
    }

    /// N5: a match replaced by a symbolic link during the scan (to a non-git directory or to another checkout, while git
    /// runs in the checkout before it, or during its own git calls) is skipped like any symbolic link under a root: no
    /// line for its path, none carrying the other directory, and the scan stays complete.
    @ParameterizedTest
    @ValueSource(strings = {"plain:a", "other:a", "other:b"})
    void scan_skipsAMatchReplacedByASymbolicLink_duringTheScan(String swap) throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var b = repo(home.resolve("work/co/b"));
        var bPath = real(b);
        var target = swap.startsWith("plain") ? Files.createDirectories(home.resolve("plain")) : repo(home.resolve("other"));
        var hook = swap.endsWith(":a") ? real(a) : bPath;
        var swapped = home.resolve("swapped");
        var bin = home.resolve("bin");
        script(bin.resolve("git"),
               "case \"$*\" in *\"-C " + hook + " \"*) [ -e '" + swapped + "' ] || { touch '" + swapped + "'; mv '" + bPath
               + "' '" + home.resolve("gone") + "'; ln -s '" + real(target) + "' '" + bPath + "'; } ;; esac\nexec "
               + onPath("git") + " \"$@\"\n");

        var lines = scanned(transport(home, pathFirst(bin)), List.of("~/work/co/*"), List.of());

        assertThat(swapped).as("the swap happened").exists();
        assertThat(rows(complete(lines)).keySet()).containsExactly(real(a));
        assertThat(lines).noneMatch(line -> line.contains(bPath + "\"") || line.contains(bPath + "/"));
        assertThat(String.join("\n", lines)).doesNotContain(real(target));
    }

    /// Finding 6: `remote` loses everything through the last `@` of its userinfo (URL and scp-like forms); a URL whose
    /// `@` lies past its authority cannot be told apart from a path and is withheld (null); a URL without userinfo is
    /// unchanged.
    @Test
    void scan_writesNoUserinfoOfARemote() throws IOException {
        var remotes = Map.of("https://user:p@ss@host/x.git", Option.some("https://host/x.git"),
                             "https://user:a/b@host/x.git", Option.<String>none(),
                             "git@github.com:org/r.git", Option.some("github.com:org/r.git"),
                             "user:p@ss@host:org/r.git", Option.some("host:org/r.git"),
                             "user:a/b@host:r.git", Option.some("host:r.git"),
                             "ssh://git@host:22/r.git", Option.some("ssh://host:22/r.git"),
                             "https://github.com/org/r.git", Option.some("https://github.com/org/r.git"),
                             "host:org/r.git", Option.some("host:org/r.git"),
                             "/srv/r@2", Option.some("/srv/r@2"),
                             "file:///srv/r.git", Option.some("file:///srv/r.git"));
        var index = 0;
        var checkouts = new LinkedHashMap<Path, Option<String>>();
        for (var remote : remotes.entrySet()) {
            var checkout = repo(home.resolve("work/co/r" + index++));
            git(checkout, "config", "remote.origin.url", remote.getKey());
            checkouts.put(checkout, remote.getValue());
        }

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        for (var checkout : checkouts.entrySet()) {
            assertThat(facts(rows, checkout.getKey()).remoteUrl()).as(checkout.getKey().toString()).isEqualTo(checkout.getValue());
        }
    }

    /// Finding 7: `size` answers a match of its root and a directory under one.
    @Test
    void size_answers_insideItsRoot() throws IOException {
        var a = repo(home.resolve("work/co/a"));

        assertThat(sized("~/work/co/*", real(a))).startsWith("{\"path\":\"" + real(a) + "\",\"kb\":");
        assertThat(sized("~/work/co/*", real(a) + "/.git")).startsWith("{\"path\":\"" + real(a) + "/.git\",\"kb\":");
    }

    /// Finding 7: outside its root (`/usr/share`), through a symbolic link under the root or inside a match, or a hard
    /// exclusion: 64, and `du` never runs.
    @Test
    void size_refuses_outsideItsRoot_aSymbolicLink_andAHardExclusion() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        Files.createDirectories(a.resolve("sub"));
        Files.createSymbolicLink(home.resolve("work/co/esc"), a);
        Files.createSymbolicLink(a.resolve("link"), a.resolve("sub"));
        git(home.resolve("work"), "init", "-q", "--bare", "pragmatica.git");

        assertThat(sizeRc("~/work/co/*", "/usr/share")).isEqualTo(64);
        assertThat(sizeRc("~/work/co/*", real(home.resolve("work/co")) + "/esc")).isEqualTo(64);
        assertThat(sizeRc("~/work/co/*", real(a) + "/link")).isEqualTo(64);
        assertThat(sizeRc("~/work/*", real(home.resolve("work/pragmatica.git")))).isEqualTo(64);
        assertThat(sizeRc("~/work/*", real(home.resolve("work/co")))).as("control: a match of ~/work/*").isZero();
    }

    /// N3: `du` failing (a directory under the path cannot be read) exits 74 and writes nothing.
    @Test
    void size_exits74_whenDuCannotReadAll() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var sub = Files.createDirectories(a.resolve("sub/inner"));

        withMode(sub.getParent(), "---------", () -> {
            var ran = forced(home, home, Map.of(), "size ~/work/co/* " + real(a));
            assertThat(ran.rc()).as(ran.stderr()).isEqualTo(74);
            assertThat(ran.stdout()).isEmpty();
        });
    }

    private String sized(String root, String path) {
        var ran = forced(home, home, Map.of(), "size " + root + " " + path);
        assertThat(ran.rc()).as(ran.stderr()).isZero();
        return ran.stdout().strip();
    }

    private int sizeRc(String root, String path) {
        var ran = forced(home, home, Map.of(), "size " + root + " " + path);
        assertThat(ran.rc() == 0 || ran.stdout().isEmpty()).as("a refusal writes nothing").isTrue();
        return ran.rc();
    }

    interface Body {
        void run() throws IOException;
    }

    /// Runs `body` with `dir` at `mode`, then restores `rwxr-xr-x` (so the temp dir can be removed).
    private static void withMode(Path dir, String mode, Body body) throws IOException {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(mode));
        try {
            body.run();
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
