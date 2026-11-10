package org.pragmatica.kiln.service.scan;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.ScanLine.CheckoutLine;
import org.pragmatica.kiln.core.scan.ScanLine.NonGitLine;
import org.pragmatica.kiln.core.scan.ScanLine.StubLine;
import org.pragmatica.kiln.service.scan.ScanTransportError.NonZero;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.scan.ScanFixture.SCRIPT;
import static org.pragmatica.kiln.service.scan.ScanFixture.activity;
import static org.pragmatica.kiln.service.scan.ScanFixture.checkoutPath;
import static org.pragmatica.kiln.service.scan.ScanFixture.commit;
import static org.pragmatica.kiln.service.scan.ScanFixture.complete;
import static org.pragmatica.kiln.service.scan.ScanFixture.expected;
import static org.pragmatica.kiln.service.scan.ScanFixture.facts;
import static org.pragmatica.kiln.service.scan.ScanFixture.forced;
import static org.pragmatica.kiln.service.scan.ScanFixture.git;
import static org.pragmatica.kiln.service.scan.ScanFixture.gitRan;
import static org.pragmatica.kiln.service.scan.ScanFixture.onPath;
import static org.pragmatica.kiln.service.scan.ScanFixture.pathFirst;
import static org.pragmatica.kiln.service.scan.ScanFixture.real;
import static org.pragmatica.kiln.service.scan.ScanFixture.repo;
import static org.pragmatica.kiln.service.scan.ScanFixture.rows;
import static org.pragmatica.kiln.service.scan.ScanFixture.run;
import static org.pragmatica.kiln.service.scan.ScanFixture.scanned;
import static org.pragmatica.kiln.service.scan.ScanFixture.script;
import static org.pragmatica.kiln.service.scan.ScanFixture.sha;
import static org.pragmatica.kiln.service.scan.ScanFixture.tip;
import static org.pragmatica.kiln.service.scan.ScanFixture.transport;

/// `ops/kiln/kiln-scan` on fixture repositories (kiln-f1-design §3.2, §5, §7.1 WP4): every listing decodes through
/// kiln-core's `ScanOutcome.of` as Complete, with exactly the facts git itself reports. The line format is the contract
/// (`ScanLine`'s javadoc): one key once, plain integers, JSON-escaped strings, one row per on-disk path, the trailer
/// last and counting the rows.
///
/// Probes (recorded in the WP4 state file): drop the `awk` first-spelling filter →
/// [#scan_listsEachDirectoryOnce_byItsOnDiskSpelling] reads Incomplete (a duplicate path); emit the logical path
/// instead of `pwd -P` → the same test sees a non-canonical path; drop a hard exclusion or the parent rule →
/// [#scan_neverListsTheHardExclusions_norAParentOfAnotherRoot]; let `*` in the first component pass, or `**` →
/// [#request_isRefused_beforeAnythingRuns]'s rows go green on a scan.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KilnScanIT {
    private static final String REMOTE = "https://github.com/pragmaticalabs/x.git";

    @TempDir
    Path home;

    @Test
    void scan_reportsExactlyTheFactsGitReports_forEveryKindOfCheckout() throws IOException {
        var co = home.resolve("work/co");
        var a = repo(co.resolve("a"));
        Files.createDirectories(a.resolve(".mvn"));
        var headA = commit(a, ".mvn/maven.config", "-Dx\n");
        git(a, "remote", "add", "origin", "https://kiln:secret@github.com/pragmaticalabs/x.git");
        git(a, "update-ref", "refs/salvage/one", "HEAD");
        git(a, "update-ref", "refs/salvage/nested/two", "HEAD");
        Files.writeString(a.resolve("f"), "stashed\n");
        git(a, "stash", "-q");
        Files.writeString(a.resolve("f"), "stashed again\n");
        git(a, "stash", "-q");
        git(a, "worktree", "add", "-q", "../a-wt", "-b", "wt");
        git(a, "update-index", "--skip-worktree", ".mvn/maven.config");
        Files.writeString(a.resolve(".mvn/maven.config"), "-Dlocal\n");
        Files.writeString(a.resolve("f"), "modified\n");
        Files.writeString(a.resolve("u1"), "untracked\n");
        Files.writeString(a.resolve("u2"), "untracked\n");
        Files.createDirectories(a.resolve("target"));
        Files.writeString(a.resolve("target/ignored"), "ignored\n");
        var b = repo(co.resolve("b"));
        git(b, "checkout", "-q", "-b", "other");
        var otherTip = commit(b, "f", "other\n");
        git(b, "checkout", "-q", "main");
        var mainTip = commit(b, "f", "main\n");
        assertThat(gitRan(b, "merge", "-q", "other").rc()).as("the merge stops on a conflict").isEqualTo(1);
        var c = repo(co.resolve("c"));
        git(c, "checkout", "-q", "--detach");
        var d = Files.createDirectories(co.resolve("d"));
        git(d, "init", "-q", "-b", "main");
        var e = Files.createDirectories(co.resolve("e"));

        var complete = complete(scanned(transport(home), List.of("~/work/co/*"), List.of()));

        var rows = rows(complete);
        var branchesA = List.of(tip("main", headA), tip("wt", headA));
        assertThat(facts(rows, a)).isEqualTo(expected(a,
                                                      Option.some("main"),
                                                      Option.some(REMOTE),
                                                      branchesA,
                                                      1,
                                                      2,
                                                      Option.none(),
                                                      2,
                                                      2));
        assertThat(facts(rows, co.resolve("a-wt"))).isEqualTo(expected(co.resolve("a-wt"),
                                                                       Option.some("wt"),
                                                                       Option.some(REMOTE),
                                                                       branchesA,
                                                                       0,
                                                                       0,
                                                                       Option.none(),
                                                                       2,
                                                                       2));
        assertThat(facts(rows, co.resolve("a-wt")).commonDir()).isEqualTo(real(a.resolve(".git")));
        assertThat(facts(rows, b)).isEqualTo(expected(b,
                                                      Option.some("main"),
                                                      Option.none(),
                                                      List.of(tip("main", mainTip), tip("other", otherTip)),
                                                      1,
                                                      0,
                                                      Option.some("merge"),
                                                      0,
                                                      0));
        assertThat(facts(rows, c)).isEqualTo(expected(c,
                                                      Option.none(),
                                                      Option.none(),
                                                      List.of(tip("main", git(c, "rev-parse", "HEAD"))),
                                                      0,
                                                      0,
                                                      Option.none(),
                                                      0,
                                                      0));
        assertThat(facts(rows, d)).isEqualTo(expected(d,
                                                      Option.some("main"),
                                                      Option.none(),
                                                      List.of(),
                                                      0,
                                                      0,
                                                      Option.none(),
                                                      0,
                                                      0));
        assertThat(facts(rows, d).head().isEmpty()).as("an unborn branch has no head").isTrue();
        assertThat(rows.get(real(e))).isEqualTo(new NonGitLine("~/work/co/*", checkoutPath(e)));
        assertThat(rows).hasSize(6);
        assertThat(complete.nonGit()).isEqualTo(1);
    }

    /// Q-2: the paths on stdin get full facts, every other checkout a stub line of three stats (a worktree's common
    /// dir read from its `.git` file and `commondir`, as git finds it), and a stdin path under no root is ignored.
    @Test
    void scan_givesFullFacts_onlyToTheListedCheckouts_andStubsToTheRest() throws IOException {
        var co = home.resolve("work/co");
        var a = repo(co.resolve("a"));
        git(a, "worktree", "add", "-q", "../a-wt", "-b", "wt");
        var b = repo(co.resolve("b"));
        var e = Files.createDirectories(co.resolve("e"));

        var complete = complete(scanned(transport(home),
                                        List.of("~/work/co/*"),
                                        List.of(checkoutPath(a), CheckoutPath.checkoutPath("/nowhere/else").unwrap())));

        var rows = rows(complete);
        assertThat(rows.get(real(a))).isInstanceOf(CheckoutLine.class);
        assertThat(rows.get(real(b))).isEqualTo(new StubLine(checkoutPath(b),
                                                             Instant.ofEpochSecond(activity(b)),
                                                             real(b.resolve(".git"))));
        assertThat(rows.get(real(co.resolve("a-wt")))).isEqualTo(new StubLine(checkoutPath(co.resolve("a-wt")),
                                                                              Instant.ofEpochSecond(activity(co.resolve("a-wt"))),
                                                                              real(a.resolve(".git"))));
        assertThat(rows.get(real(e))).isInstanceOf(NonGitLine.class);
        assertThat(rows).hasSize(4);
    }

    /// Roots naming one directory differently (through a linked home, by its real path, the same directory twice) list
    /// it once, by its on-disk spelling, under the first root that matched it. On the Mac a second case spelling is the
    /// same device:inode too (recorded as a manual run in the WP4 state file; no case-insensitive file system here). The
    /// trailer's `home` is the linked home's on-disk spelling, which `ScanOutcome.Complete` exposes.
    @Test
    void scan_listsEachDirectoryOnce_byItsOnDiskSpelling() throws IOException {
        var realHome = Files.createDirectories(home.resolve("real"));
        var linkedHome = Files.createSymbolicLink(home.resolve("linked"), realHome);
        var a = repo(realHome.resolve("work/co/a"));
        var n = Files.createDirectories(realHome.resolve("work/co/n"));

        var lines = scanned(transport(linkedHome),
                            List.of("~/work/co/*", real(realHome) + "/work/co/*", "~/work/co/a"),
                            List.of());

        var complete = complete(lines);
        var rows = rows(complete);
        assertThat(rows.keySet()).containsExactlyInAnyOrder(real(a), real(n));
        assertThat(rows.get(real(n))).isEqualTo(new NonGitLine("~/work/co/*", checkoutPath(n)));
        assertThat(lines.getLast()).isEqualTo("{\"end\":true,\"count\":1,\"nonGit\":1,\"nonGitBytes\":null,\"home\":\""
                                              + real(realHome) + "\"}");
        assertThat(complete.home()).as("the linked HOME's on-disk spelling").isEqualTo(Option.some(real(realHome)));
    }

    /// `~/work/pragmatica.git`, `~/work/m2-seed` and `~/.temper/jobs/*` are never listed, nor anything under them; a
    /// match that is the parent directory of another root (`~/work/co` beside `~/work/co/*`, `~/p` beside `~/p/q`) is
    /// not listed either.
    @Test
    void scan_neverListsTheHardExclusions_norAParentOfAnotherRoot() throws IOException {
        var work = home.resolve("work");
        Files.createDirectories(work.resolve("pragmatica.git"));
        git(work.resolve("pragmatica.git"), "init", "-q", "--bare");
        repo(work.resolve("m2-seed"));
        repo(work.resolve("m2-seed/inner"));
        var x = repo(work.resolve("co/x"));
        var plain = Files.createDirectories(work.resolve("plain"));
        repo(home.resolve(".temper/jobs/7"));
        repo(home.resolve(".temper/jobs/7/units/u"));
        var q = repo(home.resolve("p/q"));

        var complete = complete(scanned(transport(home),
                                        List.of("~/work/*",
                                                "~/work/co/*",
                                                "~/work/m2-seed/*",
                                                "~/.temper/jobs/*",
                                                "~/.temper/jobs/*/units/*",
                                                "~/p",
                                                "~/p/q"),
                                        List.of()));

        assertThat(rows(complete).keySet()).containsExactlyInAnyOrder(real(x), real(plain), real(q));
    }

    /// Names are JSON-escaped (quote, backslash, UTF-8 kept as is; a branch name holds none of them, see
    /// [KilnScanGuardIT#scan_givesAStub_onlyToTheCheckoutWithARefusedBranchName]); a name no `CheckoutPath` can hold (a control
    /// character, U+2028, bytes that are not UTF-8) is skipped, so one odd directory never makes a scan undecodable.
    @Test
    void scan_escapesNamesAsJson_andSkipsNamesNoPathCanHold() throws IOException {
        var co = Files.createDirectories(home.resolve("work/co"));
        var made = run(co,
                       Map.of(),
                       "",
                       List.of("bash",
                               "-c",
                               "mkdir 'quo\"te' 'back\\slash' \"$(printf '\\303\\274n\\303\\257')\" \"$(printf 'tab\\tname')\""
                               + " \"$(printf 'ls\\342\\200\\250x')\" \"$(printf 'nl\\nx')\""
                               // A file system with utf8only (bigboy's ZFS) refuses this name, so it cannot occur there.
                               + "; mkdir \"$(printf 'bad\\377')\" 2>/dev/null || true"));
        assertThat(made.rc()).as(made.stderr()).isZero();
        var br = repo(co.resolve("br"));
        var prefix = real(co) + "/";

        var complete = complete(scanned(transport(home), List.of("~/work/co/*"), List.of()));

        var rows = rows(complete);
        assertThat(rows.keySet()).containsExactlyInAnyOrder(prefix + "quo\"te",
                                                            prefix + "back\\slash",
                                                            prefix + "ünï",
                                                            prefix + "br");
        assertThat(facts(rows, br).branches()).containsExactly(tip("main", git(br, "rev-parse", "HEAD")));
    }

    /// A checkout whose facts hold bytes that are not UTF-8 (here origin's URL) cannot be written as a JSON line, so it
    /// gets a stub line: kiln keeps its previous facts, and the scan stays decodable.
    @Test
    void scan_givesAStub_whenAFactIsNotUtf8() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var made = run(a, Map.of(), "", List.of("bash", "-c", "git config remote.origin.url \"$(printf 'https://h/\\377')\""));
        assertThat(made.rc()).as(made.stderr()).isZero();
        var b = repo(home.resolve("work/co/b"));

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(rows.get(real(a))).isInstanceOf(StubLine.class);
        assertThat(rows.get(real(b))).isInstanceOf(CheckoutLine.class);
    }

    /// `op` names the operation git has in progress: rebase, cherry-pick, revert and bisect here (merge in
    /// [#scan_reportsExactlyTheFactsGitReports_forEveryKindOfCheckout]), each left stopped by git itself.
    @Test
    void scan_namesTheOperationInProgress() throws IOException {
        var co = home.resolve("work/co");
        var rebase = conflicting(co.resolve("rebase"));
        assertThat(gitRan(rebase, "rebase", "main").rc()).as("the rebase stops on a conflict").isNotZero();
        var pick = conflicting(co.resolve("pick"));
        var sideTip = git(pick, "rev-parse", "HEAD");
        git(pick, "checkout", "-q", "main");
        assertThat(gitRan(pick, "cherry-pick", sideTip).rc()).as("the cherry-pick stops on a conflict").isNotZero();
        var revert = repo(co.resolve("revert"));
        commit(revert, "f", "two\n");
        commit(revert, "f", "three\n");
        assertThat(gitRan(revert, "revert", "--no-edit", "HEAD~1").rc()).as("the revert stops on a conflict").isNotZero();
        var bisect = repo(co.resolve("bisect"));
        git(bisect, "bisect", "start");

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(facts(rows, rebase).op()).isEqualTo(Option.some("rebase"));
        assertThat(facts(rows, pick).op()).isEqualTo(Option.some("cherry-pick"));
        assertThat(facts(rows, revert).op()).isEqualTo(Option.some("revert"));
        assertThat(facts(rows, bisect).op()).isEqualTo(Option.some("bisect"));
    }

    /// A repository on branch `side`, whose one commit and `main`'s next commit both change `f`.
    private static Path conflicting(Path dir) throws IOException {
        var repo = repo(dir);
        git(repo, "checkout", "-q", "-b", "side");
        commit(repo, "f", "side\n");
        git(repo, "checkout", "-q", "main");
        commit(repo, "f", "main\n");
        git(repo, "checkout", "-q", "side");
        return repo;
    }

    /// Every control character (1..31 and 127) in a fact is escaped so that the JSON line gives the value back exactly.
    @Test
    void scan_escapesEveryControlCharacter_soTheValueRoundTrips() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        var remote = new StringBuilder("https://h/");
        IntStream.concat(IntStream.range(1, 32), IntStream.of(127)).forEach(code -> remote.append((char) code).append('x'));
        git(a, "config", "remote.origin.url", remote.toString());

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(facts(rows, a).remoteUrl()).isEqualTo(Option.some(remote.toString()));
    }

    /// The script leaves its working directory first, so it never lists itself (nor its git children) as a process
    /// inside the checkout it is started from.
    @Test
    void scan_neverListsItself_whenStartedInsideACheckout() throws IOException {
        var a = repo(home.resolve("work/co/a"));

        var ran = forced(home, a, Map.of(), "scan ~/work/co/*");

        assertThat(ran.rc()).as(ran.stderr()).isZero();
        assertThat(facts(rows(complete(ran.stdout().lines().toList())), a).processes()).isEmpty();
    }

    /// The script runs under `nice -n 19` and, with an `ionice` on `PATH`, `ionice -c3`; every git call it makes is
    /// `GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_OPTIONAL_LOCKS=0 GIT_NO_LAZY_FETCH=1 GIT_ALLOW_PROTOCOL=none
    /// git --no-optional-locks -c core.hooksPath=/dev/null -c core.fsmonitor=false -c protocol.allow=never`, each call in a checkout has the checkout's parent as its
    /// `GIT_CEILING_DIRECTORIES`, and only reads (no fetch, gc or other writing verb). Fakes first on `PATH` log each
    /// call, then run the real tool.
    @Test
    void scan_runsNiced_andEveryGitCallReadOnly() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        Files.writeString(a.resolve("f"), "stashed\n");
        git(a, "stash", "-q");
        git(a, "worktree", "add", "-q", "../a-wt", "-b", "wt");
        git(home.resolve("work"), "clone", "-q", "--bare", a.toString(), "pragmatica.git");
        var bin = home.resolve("bin");
        var log = home.resolve("calls.log");
        script(bin.resolve("nice"), "echo \"nice $*\" >> \"$KILN_TEST_LOG\"; shift 2; exec \"$@\"\n");
        script(bin.resolve("ionice"), "echo \"ionice $*\" >> \"$KILN_TEST_LOG\"; shift; exec \"$@\"\n");
        script(bin.resolve("git"),
               "echo \"git ${GIT_OPTIONAL_LOCKS:-unset} ${GIT_CONFIG_NOSYSTEM:-unset} ${GIT_CONFIG_GLOBAL:-unset}"
               + " ${GIT_NO_LAZY_FETCH:-unset} ${GIT_ALLOW_PROTOCOL:-unset} ${GIT_CEILING_DIRECTORIES:-none} $*\" >> \"$KILN_TEST_LOG\"; exec " + onPath("git") + " \"$@\"\n");
        var env = pathFirst(bin);
        env.put("KILN_TEST_LOG", log.toString());
        var transport = transport(home, env);

        complete(scanned(transport, List.of("~/work/co/*"), List.of()));
        var reached = transport.reach(Set.of(sha(git(a, "rev-parse", "HEAD")))).await().unwrap();

        assertThat(reached).hasSize(2);
        var calls = Files.readAllLines(log);
        assertThat(calls.stream().filter(call -> call.startsWith("nice "))).hasSize(2)
                                                                            .allMatch(call -> call.startsWith("nice -n 19 ionice -c3 "));
        assertThat(calls.stream().filter(call -> call.startsWith("ionice "))).hasSize(2)
                                                                              .allMatch(call -> call.startsWith("ionice -c3 "));
        var gitCalls = calls.stream().filter(call -> call.startsWith("git ")).toList();
        assertThat(gitCalls).hasSizeGreaterThan(10)
                            .allMatch(call -> call.matches("git 0 1 /dev/null 1 none \\S+ --no-optional-locks -c core\\.hooksPath=/dev/null"
                                                           + " -c core\\.fsmonitor=false -c protocol\\.allow=never -C .*"));
        var bare = home.resolve("work/pragmatica.git").toString();
        assertThat(gitCalls.stream().filter(call -> !dir(call).equals(bare)))
            .hasSizeGreaterThan(10)
            .allMatch(call -> ceiling(call).equals(dir(call).substring(0, dir(call).lastIndexOf('/'))));
        assertThat(gitCalls.stream().map(KilnScanIT::verb).collect(Collectors.toSet()))
            .isSubsetOf(Set.of("rev-parse", "symbolic-ref", "config", "for-each-ref", "status", "rev-list", "cat-file", "ls-files"))
            .contains("status", "rev-list", "for-each-ref", "cat-file", "ls-files");
    }

    /// The git verb of a logged call: the word after `-C <dir>`.
    private static String verb(String call) {
        var words = call.split(" ");
        return words[List.of(words).indexOf("-C") + 2];
    }

    /// The directory of a logged call: the word after `-C`.
    private static String dir(String call) {
        var words = call.split(" ");
        return words[List.of(words).indexOf("-C") + 1];
    }

    /// The `GIT_CEILING_DIRECTORIES` of a logged call (`none` when unset).
    private static String ceiling(String call) {
        return call.split(" ")[6];
    }

    /// `reach`: the first ref of `~/work/pragmatica.git` holding each SHA, else null (a commit no ref holds, a SHA the
    /// bare lacks); then a trailer counting the SHAs.
    @Test
    void reach_namesTheFirstRefHoldingEachSha_inTheHostBare(@TempDir Path bareless) throws IOException {
        var origin = repo(home.resolve("origin"));
        var first = git(origin, "rev-parse", "HEAD");
        var second = commit(origin, "f", "two\n");
        git(origin, "checkout", "-q", "-b", "tagged");
        var tagged = commit(origin, "f", "tagged\n");
        git(origin, "tag", "v1");
        git(origin, "checkout", "-q", "main");
        git(origin, "branch", "-q", "-D", "tagged");
        Files.createDirectories(home.resolve("work"));
        git(home.resolve("work"), "clone", "-q", "--bare", origin.toString(), "pragmatica.git");
        var bare = home.resolve("work/pragmatica.git");
        var dangling = git(bare, "commit-tree", git(bare, "rev-parse", "main^{tree}"), "-p", second, "-m", "no ref");
        var refs = Map.of(first, "\"refs/heads/main\"",
                          second, "\"refs/heads/main\"",
                          tagged, "\"refs/tags/v1\"",
                          dangling, "null",
                          "0123456789abcdef0123456789abcdef01234567", "null");

        var lines = transport(home).reach(shas(refs.keySet())).await().unwrap();
        var unanswered = transport(bareless).reach(shas(refs.keySet())).await().unwrap();

        assertThat(lines).containsExactlyElementsOf(answers(refs));
        assertThat(unanswered).as("no bare: every ref is null")
                              .containsExactlyElementsOf(answers(refs.keySet()
                                                                     .stream()
                                                                     .collect(Collectors.toMap(sha -> sha, _ -> "null"))));
    }

    private static Set<Sha> shas(Set<String> values) {
        return values.stream().map(ScanFixture::sha).collect(Collectors.toSet());
    }

    /// The lines `reach` answers for `refs` (SHA → its JSON ref), SHAs in order (the transport sorts them).
    private static List<String> answers(Map<String, String> refs) {
        var lines = new ArrayList<String>();
        refs.keySet()
            .stream()
            .sorted()
            .forEach(sha -> lines.add("{\"sha\":\"" + sha + "\",\"ref\":" + refs.get(sha) + "}"));
        lines.add("{\"end\":true,\"count\":" + refs.size() + "}");
        return lines;
    }

    /// 200 SHAs is the limit, not past it: answered, each null without a bare.
    @Test
    void reach_answers200Shas() {
        var shas = IntStream.range(0, 200).mapToObj(KilnScanIT::fakeSha).collect(Collectors.toSet());

        var lines = transport(home).reach(shas(shas)).await().unwrap();

        assertThat(lines).hasSize(201).last().isEqualTo("{\"end\":true,\"count\":200}");
    }

    private static String fakeSha(int index) {
        return "%040x".formatted(index);
    }

    /// The figure is `du -sk`'s. ZFS (bigboy) updates a new file's blocks only at its next transaction group, even
    /// after `sync`, so the answer is bracketed by `du` just before and just after, never compared with a constant.
    @Test
    void size_answersTheKilobytesDuCounts() throws IOException {
        var dir = Files.createDirectories(home.resolve("work/co/big"));
        var blob = new byte[256 * 1024];
        new Random(42).nextBytes(blob);
        Files.write(dir.resolve("blob"), blob);
        var before = du(dir);

        var lines = transport(home).size("~/work/co/*", checkoutPath(dir)).await().unwrap();

        var after = du(dir);
        assertThat(lines).singleElement().asString().matches("\\{\"path\":\"" + Pattern.quote(real(dir)) + "\",\"kb\":[0-9]+}");
        var kb = Long.parseLong(lines.getFirst().replaceAll(".*\"kb\":([0-9]+)}", "$1"));
        assertThat(kb).isBetween(before, after);
    }

    private static long du(Path dir) throws IOException {
        return Long.parseLong(run(dir, Map.of(), "", List.of("du", "-sk", real(dir))).stdout().split("\\s")[0]);
    }

    /// A path under one of the root's matches that is no directory: 66.
    @Test
    void size_exits66_forNoSuchDirectory() throws IOException {
        var a = Files.createDirectories(home.resolve("work/co/a"));
        var failure = transport(home).size("~/work/co/*", CheckoutPath.checkoutPath(real(a) + "/missing").unwrap()).await();

        Cause cause = failure.fold(origin -> origin, _ -> null);
        assertThat(cause).isInstanceOf(NonZero.class);
        assertThat(((NonZero) cause).rc()).isEqualTo(66);
    }

    /// The Mac form (spec §5.4): with `SSH_ORIGINAL_COMMAND` unset the request is argv, and stdin is not read, so
    /// every checkout gets full facts, exactly as the forced command answers with an empty stdin.
    @Test
    void macForm_takesArgv_andAnswersAsTheForcedCommand() throws IOException {
        var a = repo(home.resolve("work/co/a"));
        repo(home.resolve("work/co/b"));
        Files.createDirectories(home.resolve("work/co/n"));
        var builder = HermeticProcess.builder(List.of("bash", SCRIPT.toString(), "scan", "~/work/co/*"));
        builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", System.getenv("PATH"), "HOME", home.toString(), "USER", System.getProperty("user.name")));

        var mac = ScanFixture.ran(builder, real(a) + "\n");

        assertThat(mac.rc()).as(mac.stderr()).isZero();
        var forced = scanned(transport(home), List.of("~/work/co/*"), List.of());
        assertThat(mac.stdout().lines().toList()).isEqualTo(forced);
        assertThat(complete(forced).rows().stream().filter(CheckoutLine.class::isInstance)).hasSize(2);
    }

    /// Every malformed request exits 64 before anything runs: nothing on stdout, nothing created in the home.
    @ParameterizedTest
    @MethodSource("refusedRequests")
    void request_isRefused_beforeAnythingRuns(String request) throws IOException {
        Files.createDirectories(home.resolve("work/co"));
        var env = new HashMap<String, String>();
        env.put("HOME", home.toString());
        env.put("SSH_ORIGINAL_COMMAND", request);

        var ran = run(home, env, "", List.of("bash", SCRIPT.toString()));

        assertThat(ran.rc()).as(ran.stderr()).isEqualTo(64);
        assertThat(ran.stdout()).isEmpty();
        try (var listed = Files.list(home)) {
            assertThat(listed.map(path -> path.getFileName().toString())).containsExactly("work");
        }
    }

    static Stream<String> refusedRequests() {
        var roots = Stream.of("/", "~", "~/", "~/*", "/*", "/a*", "~/a*/b", "/a/**", "/a/**/b", "/a//b", "/a/", "/a/./b",
                              "/a/../b", "/a/.", "rel", "./x", "~u/x", "~/a b", "/a/$x", "/a/[x]", "/a/?", "/a/{b,c}",
                              "~/$(touch${IFS}pwned)", "~/`touch pwned`", "/a;id", "/a|id", "/a\\b", "/a/b'c", "/a/\"b");
        var verbs = Stream.of("", " ", "scan", "scan ", " scan ~/x", "scan  ~/x", "scan ~/x ", "SCAN ~/x", "scan\t~/x",
                              "scan ~/x\n", "scan ~/x\rid", "rm -rf ~", "scan2 ~/x", "reach", "size", "size ~/work/co/*",
                              "size ~/work/co/* rel", "size ~/work/co/* /a/../b", "size ~/work/co/* /a/./b",
                              "size ~/work/co/* /a//b", "size ~/work/co/* /a/", "size ~/work/co/* /",
                              "size ~/work/co/* /a /b", "size /a", "size /* /a", "size ~/** /a", "size rel /a",
                              "tickets x", "tickets ", " tickets", "tickets  ", "TICKETS", "tickets\n", "tickets;id", "ticket",
                              "reach " + "A".repeat(40), "reach " + "a".repeat(39), "reach " + "a".repeat(41),
                              "reach " + "g".repeat(40), "reach " + "a".repeat(40) + " x",
                              "reach " + IntStream.range(0, 201).mapToObj(KilnScanIT::fakeSha).collect(Collectors.joining(" ")));
        return Stream.concat(roots.map(root -> "scan ~/work/co/* " + root), verbs);
    }

    /// `tickets`: read-only, execs the user's `~/tools/bb-tickets`, whose stdout and exit code are the answer.
    @Test
    void tickets_execsTheUsersBbTickets() throws IOException {
        var script = Files.createDirectories(home.resolve("tools")).resolve("bb-tickets");
        Files.writeString(script, "#!/bin/sh\necho \"args=$#\"\nexit 7\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();

        var ran = run(home, sshEnv("tickets"), "", List.of("bash", SCRIPT.toString()));

        assertThat(ran.rc()).as(ran.stderr()).isEqualTo(7);
        assertThat(ran.stdout()).isEqualTo("args=0\n");
    }

    @Test
    void tickets_withoutTheScript_isExit69() throws IOException {
        var ran = run(home, sshEnv("tickets"), "", List.of("bash", SCRIPT.toString()));

        assertThat(ran.rc()).isEqualTo(69);
        assertThat(ran.stdout()).isEmpty();
        assertThat(ran.stderr()).contains("kiln-scan: ~/tools/bb-tickets not installed");
    }

    /// The Mac has no bb queue: the argv form refuses the verb.
    @Test
    void macForm_refusesTickets() throws IOException {
        var script = Files.createDirectories(home.resolve("tools")).resolve("bb-tickets");
        Files.writeString(script, "#!/bin/sh\necho ran\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();

        var ran = run(home, Map.of("HOME", home.toString()), "", List.of("bash", SCRIPT.toString(), "tickets"));

        assertThat(ran.rc()).as(ran.stderr()).isEqualTo(64);
        assertThat(ran.stdout()).isEmpty();
    }

    private Map<String, String> sshEnv(String request) {
        var env = new HashMap<String, String>();
        env.put("HOME", home.toString());
        env.put("SSH_ORIGINAL_COMMAND", request);
        return env;
    }

    /// The Mac form refuses as the forced command does.
    @ParameterizedTest
    @ValueSource(strings = {"/a/**", "/*", "~/", "/a b", "rel"})
    void macForm_refusesABadRoot(String root) {
        var ran = run(home, Map.of("HOME", home.toString()), "", List.of("bash", SCRIPT.toString(), "scan", root));

        assertThat(ran.rc()).as(ran.stderr()).isEqualTo(64);
        assertThat(ran.stdout()).isEmpty();
    }

    @Test
    void macForm_refusesNoRequest() {
        var ran = run(home, Map.of("HOME", home.toString()), "", List.of("bash", SCRIPT.toString()));

        assertThat(ran.rc()).isEqualTo(64);
    }
}
