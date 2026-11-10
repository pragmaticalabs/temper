package org.pragmatica.kiln.service.conflict;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.conflict.Changes;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.conflict.ConflictGit.Merge;
import org.pragmatica.kiln.service.conflict.ConflictGit.ObjectFiles;
import org.pragmatica.kiln.service.mirror.MirrorError;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// [ConflictGit] over the `git` CLI, on kiln's bare mirror (temper #100), after [org.pragmatica.kiln.service.mirror.GitMirror]:
/// argv only, never a shell; SHAs and branch names are validated value objects that cannot start with `-`; every call
/// has a deadline; `GIT_TERMINAL_PROMPT=0`, so git never waits for a credential (the conflict watch never fetches; the
/// mirror step does, anonymously).
///
/// The mirror is never written. `merge-tree --write-tree` and `commit-tree` write objects, so every call runs with
/// `GIT_OBJECT_DIRECTORY` set to a scratch directory and the mirror's own objects as an alternate: reads see both, writes
/// land in scratch only. A concurrent fetch or `gc --auto` in the mirror therefore never meets an object of ours. The
/// mirror's size is read from its object files' lengths ([#mirrorObjects]), not from git.
public final class GitConflicts implements ConflictGit {
    private static final TimeSpan QUICK = timeSpan(1).minutes();
    /// One merge-tree on pragmatica takes well under a second (measured on bigboy); a minute is a hung git.
    private static final TimeSpan MERGE = timeSpan(2).minutes();
    /// `merge-tree --write-tree` exits 1 when the merge has conflicts; 0 is clean; anything else is a failed merge.
    private static final int CONFLICTED = 1;
    /// `rev-parse --verify --quiet` exits 1 when the object is absent.
    private static final int ABSENT = 1;

    /// A loose object (SHA-1 or SHA-256 name) or a pack's data; indexes, bitmaps and temporary files are git's own.
    private static final Pattern OBJECT_FILE = Pattern.compile("[0-9a-f]{2}/(?:[0-9a-f]{38}|[0-9a-f]{62})|pack/pack-[0-9a-f]+\\.pack");

    /// A fixed identity and date for the squash commit, so the same tree and parent give the same commit every time.
    private static final Map<String, String> IDENTITY = Map.of("GIT_AUTHOR_NAME",
                                                               "kiln",
                                                               "GIT_AUTHOR_EMAIL",
                                                               "kiln@localhost",
                                                               "GIT_AUTHOR_DATE",
                                                               "1700000000 +0000",
                                                               "GIT_COMMITTER_NAME",
                                                               "kiln",
                                                               "GIT_COMMITTER_EMAIL",
                                                               "kiln@localhost",
                                                               "GIT_COMMITTER_DATE",
                                                               "1700000000 +0000");

    private final ProcessRunner runner;
    private final Path mirror;
    private final Path scratch;

    private GitConflicts(ProcessRunner runner, Path mirror, Path scratch) {
        this.runner = runner;
        this.mirror = mirror;
        this.scratch = scratch;
    }

    /// `mirror` is kiln's bare mirror (`[mirror] path`); `scratch` the directory whose `objects` receive what merges write.
    public static GitConflicts gitConflicts(ProcessRunner runner, Path mirror, Path scratch) {
        return new GitConflicts(runner, mirror, scratch);
    }

    @Override
    public Promise<Sha> tip(BranchName branch) {
        return gitOk("rev-parse",
                     List.of("rev-parse", "--verify", "refs/heads/" + branch.value() + "^{commit}"),
                     QUICK).flatMap(ok -> parsed("rev-parse",
                                                 Sha.sha(ok.stdoutText().trim())));
    }

    @Override
    public Promise<Boolean> present(Sha head) {
        return git("rev-parse",
                   List.of("rev-parse", "--verify", "--quiet", head.value() + "^{commit}"),
                   QUICK).flatMap(GitConflicts::presence);
    }

    private static Promise<Boolean> presence(Completed revParse) {
        return revParse.rc() == ABSENT
               ? Promise.success(false)
               : revParse.requireZero()
                         .map(_ -> true)
                         .mapError(cause -> MirrorError.Failed.FACTORY.apply("rev-parse", cause))
                         .async();
    }

    @Override
    public Promise<Changes> changed(Sha tip, Sha head) {
        return gitOk("diff",
                     List.of("diff",
                             "--name-status",
                             "-z",
                             "-M",
                             tip.value() + "..." + head.value(),
                             "--"),
                     MERGE).flatMap(ok -> parsed("diff", nameStatus(ok.stdoutText())));
    }

    /// `--name-status -z` output: a status (`M`, `A`, `D`, `T`, or `R`/`C` with a score), NUL, its path, NUL; a rename or
    /// a copy carries its old path, then its new one. A record cut short is not an answer and fails.
    static Result<Changes> nameStatus(String output) {
        var fields = nulSeparated(output);
        var entries = new ArrayList<Changes.Entry>();
        var at = 0;

        while (at < fields.size()) {
            var status = fields.get(at).charAt(0);
            var paths = status == 'R' || status == 'C'
                        ? 2
                        : 1;

            if (at + paths >= fields.size()) {
                return Causes.cause("diff --name-status: a record without its path").result();
            }

            entries.add(new Changes.Entry(status, fields.get(at + 1), fields.get(at + paths)));
            at += 1 + paths;
        }

        return Result.success(Changes.changes(entries));
    }

    @Override
    public Promise<Merge> merge(Sha ours, Sha theirs) {
        return git("merge-tree",
                   List.of("merge-tree",
                           "--write-tree",
                           "--name-only",
                           "--no-messages",
                           "-z",
                           ours.value(),
                           theirs.value()),
                   MERGE).flatMap(GitConflicts::merged);
    }

    /// `-z --name-only --no-messages` output: the tree OID, then each conflicted path, NUL-terminated. Exit 1 with no
    /// path, or exit 0 without a tree OID, is not an answer and fails.
    static Promise<Merge> merged(Completed completed) {
        var fields = nulSeparated(completed.stdoutText());

        return completed.rc() == CONFLICTED
               ? conflicted(fields)
               : cleanTree(completed, fields);
    }

    private static Promise<Merge> conflicted(List<String> fields) {
        var paths = fields.stream().skip(1).distinct().toList();

        return paths.isEmpty()
               ? MirrorError.Failed.FACTORY.apply("merge-tree",
                                                  Causes.cause("exit 1 named no conflicted path"))
                                           .promise()
               : Promise.success(new Merge.Conflicted(paths));
    }

    private static Promise<Merge> cleanTree(Completed completed, List<String> fields) {
        return completed.requireZero()
                        .flatMap(_ -> Sha.sha(fields.stream().findFirst().orElse("")))
                        .map(GitConflicts::clean)
                        .mapError(cause -> MirrorError.Failed.FACTORY.apply("merge-tree", cause))
                        .async();
    }

    private static Merge clean(Sha tree) {
        return new Merge.Clean(tree);
    }

    @Override
    public Promise<Sha> squash(Sha tree, Sha parent) {
        return gitOk("commit-tree",
                     List.of("commit-tree", tree.value(), "-p", parent.value(), "-m", "kiln squash"),
                     QUICK).flatMap(ok -> parsed("commit-tree",
                                                 Sha.sha(ok.stdoutText().trim())));
    }

    @Override
    public Promise<ObjectFiles> mirrorObjects() {
        return objectFiles(mirror.resolve("objects")).mapError(cause -> MirrorError.Failed.FACTORY.apply("objects read",
                                                                                                         cause))
                          .async();
    }

    /// Read from the filesystem, not `count-objects`: its `size:` is allocated blocks, which on ZFS lag the write. A file
    /// that vanishes during the walk (a concurrent `gc`) fails the read; it never reads as smaller. A symlinked `objects`
    /// is followed (`Files.walk` does not follow a symlinked start, which read as an empty store: every step reported zero
    /// bytes); a store that cannot be resolved or read fails the read, so the bytes are unknown, never zero.
    private static Result<ObjectFiles> objectFiles(Path objects) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               var real = objects.toRealPath();

                               try (Stream<Path> walk = Files.walk(real, 2)) {
                               var lengths = new HashMap<String, Long>();

                               for (var file : walk.filter(Files::isRegularFile)
                                                   .toList()) {
                               var name = real.relativize(file)
                                              .toString()
                                              .replace(File.separatorChar, '/');

                               if (OBJECT_FILE.matcher(name).matches()) {
                               lengths.put(name, Files.size(file));
                           }
                           }

                               return new ObjectFiles(lengths);
                           }
                           });
    }

    @Override
    public Promise<Unit> resetScratch() {
        return Promise.lift(Causes::fromThrowable,
                            () -> {
                                if (Files.isDirectory(scratch)) {
                                try (Stream<Path> walk = Files.walk(scratch)) {
                                walk.sorted(Comparator.reverseOrder())
                                    .map(Path::toFile)
                                    .forEach(File::delete);
                            }
                            }

                                return Files.createDirectories(scratch.resolve("objects"));
                            })
                      .mapToUnit();
    }

    private static List<String> nulSeparated(String text) {
        return Arrays.stream(text.split("\0"))
                     .filter(field -> !field.isEmpty())
                     .toList();
    }

    private static <T> Promise<T> parsed(String operation, Result<T> result) {
        return result.mapError(cause -> MirrorError.Failed.FACTORY.apply(operation, cause))
                     .async();
    }

    private Promise<Completed> gitOk(String operation, List<String> args, TimeSpan timeout) {
        return git(operation, args, timeout).flatMap(completed -> completed.requireZero()
                                                                           .mapError(cause -> MirrorError.Failed.FACTORY.apply(operation,
                                                                                                                               cause))
                                                                           .async());
    }

    private Promise<Completed> git(String operation, List<String> args, TimeSpan timeout) {
        return run(operation, args, environment(), timeout);
    }

    private Promise<Completed> run(String operation, List<String> args, Map<String, String> env, TimeSpan timeout) {
        return Command.command(Stream.concat(Stream.of("git",
                                                       "--git-dir",
                                                       mirror.toString()),
                                             args.stream()).toList(),
                               env,
                               timeout)
                      .map(Command::inheritingEnvironment)
                      .async()
                      .flatMap(command -> scratchReady().flatMap(_ -> runner.run(command)))
                      .mapError(cause -> MirrorError.Failed.FACTORY.apply(operation, cause));
    }

    /// git refuses to start while `GIT_OBJECT_DIRECTORY` does not exist, so it is created before every call (a no-op once
    /// it exists; [#resetScratch] deletes it).
    private Promise<Path> scratchReady() {
        return Promise.lift(Causes::fromThrowable,
                            () -> Files.createDirectories(scratch.resolve("objects")));
    }

    private Map<String, String> environment() {
        var env = new java.util.HashMap<>(IDENTITY);

        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_OBJECT_DIRECTORY",
                scratch.resolve("objects").toString());
        env.put("GIT_ALTERNATE_OBJECT_DIRECTORIES",
                mirror.resolve("objects").toString());

        return Map.copyOf(env);
    }
}
