package org.pragmatica.kiln.service.mirror;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;

/// A "GitHub" for the mirror tests (kiln-f1-design §7.1 WP7): a bare origin whose refs a test sets with `update-ref`,
/// including `refs/pull/N/head`. The origin borrows its objects from a work repository through
/// `objects/info/alternates`, so a commit exists the moment the work repository writes it; the mirror reaches the origin
/// over `file://`, so only the objects the origin's refs reach travel, as from GitHub. git runs with no system or global
/// configuration, so a host's settings (signing, hooks, a default branch) cannot change a fixture.
final class MirrorFixture {
    final Path work;
    final Path origin;
    final Path mirror;
    private int commits;

    private MirrorFixture(Path work, Path origin, Path mirror) {
        this.work = work;
        this.origin = origin;
        this.mirror = mirror;
    }

    /// The mirror lives two directories below `dir` that do not exist yet, as `~/.local/share/kiln/mirror.git` may not.
    static MirrorFixture create(Path dir) throws IOException, InterruptedException {
        var fixture = new MirrorFixture(dir.resolve("work"), dir.resolve("origin.git"), dir.resolve("share/kiln/mirror.git"));

        git(dir, "init", "-q", "-b", "main", fixture.work.toString());
        git(dir, "init", "-q", "--bare", "-b", "main", fixture.origin.toString());
        Files.writeString(fixture.origin.resolve("objects/info/alternates"),
                          fixture.work.resolve(".git/objects").toAbsolutePath() + "\n");
        return fixture;
    }

    GitMirror gitMirror() {
        return GitMirror.gitMirror(ProcessRunner.processRunner(), originUrl(), mirror).unwrap();
    }

    String originUrl() {
        return "file://" + origin.toAbsolutePath();
    }

    /// A commit with no parent, holding one file.
    String root(String file, String content) throws IOException, InterruptedException {
        Files.writeString(work.resolve(file), content);
        git(work, "add", "-A");
        git(work, "commit", "-q", "-m", "root " + (++commits));
        return git(work, "rev-parse", "HEAD");
    }

    /// A child of `parent` that changes the tree: `file` gets `content`.
    String commit(String parent, String file, String content) throws IOException, InterruptedException {
        git(work, "checkout", "-q", "--detach", parent);
        Files.writeString(work.resolve(file), content);
        git(work, "add", "-A");
        git(work, "commit", "-q", "-m", "change " + (++commits));
        return git(work, "rev-parse", "HEAD");
    }

    /// A child of `parent` with the same tree (`git commit --allow-empty`, as a `know:` commit is).
    String empty(String parent) throws IOException, InterruptedException {
        git(work, "checkout", "-q", "--detach", parent);
        git(work, "commit", "-q", "--allow-empty", "-m", "empty " + (++commits));
        return git(work, "rev-parse", "HEAD");
    }

    /// Sets `ref` in the origin, as GitHub would after a push or a PR update.
    void ref(String ref, String sha) throws IOException, InterruptedException {
        git(origin, "update-ref", ref, sha);
    }

    void deleteRef(String ref) throws IOException, InterruptedException {
        git(origin, "update-ref", "-d", ref);
    }

    /// Deletes `ref` in the mirror itself, behind the adapter's back.
    void deleteMirrorRef(String ref) throws IOException, InterruptedException {
        git(mirror, "update-ref", "-d", ref);
    }

    /// The refs the mirror holds, as `<sha> <refname>` lines.
    List<String> mirrorRefs() throws IOException, InterruptedException {
        return git(mirror, "for-each-ref", "--format=%(objectname) %(refname)").lines().toList();
    }

    static Sha sha(String raw) {
        return Sha.sha(raw).unwrap();
    }

    static BranchName branch(String raw) {
        return BranchName.branchName(raw).unwrap();
    }

    /// Runs git in `dir` (`-C`), failing the test on a nonzero exit.
    static String git(Path dir, String... args) throws IOException, InterruptedException {
        var argv = Stream.concat(Stream.of("git", "-C", dir.toString()), Stream.of(args)).toList();
        var builder = HermeticProcess.builder(argv).redirectErrorStream(true);
        var env = builder.environment();

        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_AUTHOR_NAME", "kiln");
        env.put("GIT_AUTHOR_EMAIL", "kiln@test");
        env.put("GIT_COMMITTER_NAME", "kiln");
        env.put("GIT_COMMITTER_EMAIL", "kiln@test");
        var process = builder.start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + out).isZero();
        return out;
    }
}
