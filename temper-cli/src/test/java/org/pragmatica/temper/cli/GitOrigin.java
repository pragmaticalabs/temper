package org.pragmatica.temper.cli;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// A `file://` stand-in for GitHub (WP-C): a bare repository at `<root>/gh/<owner>/<name>.git`
/// that the CLI fetches from through the injected base `file://<root>/gh/`, and a work repository the test builds commits
/// in and pushes any ref from (`refs/heads/*`, `refs/pull/<n>/head`). Commits have fixed author and committer dates, so a
/// fixture is reproducible.
final class GitOrigin {
    private final Path root;
    private final Path bare;
    private final Path work;

    private GitOrigin(Path root, Path bare, Path work) {
        this.root = root;
        this.bare = bare;
        this.work = work;
    }

    static GitOrigin create(Path root, String repo) throws IOException {
        var bare = root.resolve("gh").resolve(repo + ".git");
        var work = root.resolve("work-" + repo.replace('/', '-'));

        Files.createDirectories(bare);
        Files.createDirectories(work);
        var origin = new GitOrigin(root, bare, work);

        origin.run(bare, "init", "--bare", "-q");
        origin.run(work, "init", "-q", "-b", "main");
        return origin;
    }

    /// The base the CLI builds `<base><repo>.git` from.
    String base() {
        return "file://" + root.resolve("gh") + "/";
    }

    String url(String repo) {
        return base() + repo + ".git";
    }

    Path bare() {
        return bare;
    }

    /// A commit on `parent` (none: a root commit) writing `files` (path → content; null content deletes).
    String commit(String parent, String message, Map<String, String> files) throws IOException {
        detachAt(parent);
        for (var entry : files.entrySet()) {
            var file = work.resolve(entry.getKey());
            if (entry.getValue() == null) {
                run(work, "rm", "-q", entry.getKey());
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
                run(work, "add", entry.getKey());
            }
        }
        run(work, "commit", "-q", "--allow-empty", "-m", message);
        return head();
    }

    /// An empty commit (same tree as its parent): a `know:` commit or a `[skip ci]` one.
    String empty(String parent, String message) throws IOException {
        return commit(parent, message, Map.of());
    }

    /// `git merge --no-ff` of `other` into `first` (first parent), resolved by git; `extra` folded in by hand when given.
    String merge(String first, String other, String message, Map<String, String> extra) throws IOException {
        detachAt(first);
        run(work, "merge", "-q", "--no-ff", "--no-edit", "-m", message, other);
        if (!extra.isEmpty()) {
            for (var entry : extra.entrySet()) {
                Files.writeString(work.resolve(entry.getKey()), entry.getValue());
                run(work, "add", entry.getKey());
            }
            run(work, "commit", "-q", "--amend", "--no-edit");
        }
        return head();
    }

    /// `git merge -s ours` of `other` into `first`: a merge commit whose tree is `first`'s (an empty merge).
    String mergeOurs(String first, String other, String message) throws IOException {
        detachAt(first);
        run(work, "merge", "-q", "--no-ff", "-s", "ours", "-m", message, other);
        return head();
    }

    /// Pushes `sha` to `ref` in the bare origin (forced: refs move backwards too).
    GitOrigin push(String sha, String ref) throws IOException {
        run(work, "push", "-q", "--force", bare.toString(), sha + ":" + ref);
        return this;
    }

    private void detachAt(String parent) throws IOException {
        if (parent == null) {
            run(work, "checkout", "-q", "--orphan", "tmp-" + System.nanoTime());
            run(work, "rm", "-rq", "--cached", "--ignore-unmatch", ".");
        } else {
            run(work, "checkout", "-q", "--detach", parent);
        }
    }

    private String head() throws IOException {
        return run(work, "rev-parse", "HEAD").strip();
    }

    String run(Path dir, String... args) throws IOException {
        var argv = new ArrayList<String>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        var builder = HermeticProcess.builder(argv).redirectErrorStream(true);
        var env = builder.environment();

        env.keySet().removeIf(name -> name.startsWith("GIT_"));
        env.put("HOME", root.toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_AUTHOR_NAME", "fixture");
        env.put("GIT_AUTHOR_EMAIL", "fixture@example.invalid");
        env.put("GIT_COMMITTER_NAME", "fixture");
        env.put("GIT_COMMITTER_EMAIL", "fixture@example.invalid");
        env.put("GIT_AUTHOR_DATE", "2026-11-03T10:00:00Z");
        env.put("GIT_COMMITTER_DATE", "2026-11-03T10:00:00Z");
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        try {
            if (process.waitFor() != 0) {
                throw new IOException("git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
        return output;
    }
}
