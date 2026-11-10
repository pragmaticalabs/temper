package org.pragmatica.temper.worker.workspace;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.worker.WorkerError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The git calls of workspace preparation (m0-design §7.3). argv only, isolated environment (`PATH`, `HOME`,
/// plus `GIT_TERMINAL_PROMPT=0` so a credential prompt fails instead of hanging). Positional arguments follow
/// `--` where git accepts it, so an origin URL can never be read as an option.
public record Git(ProcessRunner runner, Map<String, String> env, TimeSpan timeout) {
    private static final Logger LOG = LoggerFactory.getLogger(Git.class);

    public Git {
        var isolated = new HashMap<>(env);

        isolated.put("GIT_TERMINAL_PROMPT", "0");
        env = Map.copyOf(isolated);
    }

    public static Git git(ProcessRunner runner, Map<String, String> env, TimeSpan timeout) {
        return new Git(runner, env, timeout);
    }

    /// `git clone --mirror --quiet -- <origin> <target>`.
    public Promise<Unit> cloneMirror(String origin, Path target) {
        return zero("clone --mirror",
                    List.of("git", "clone", "--mirror", "--quiet", "--", origin, target.toString()));
    }

    /// `git --git-dir <mirror> fetch --prune --quiet origin`.
    public Promise<Unit> fetch(Path mirror) {
        return zero("fetch",
                    List.of("git", "--git-dir", mirror.toString(), "fetch", "--prune", "--quiet", "origin"));
    }

    /// Points the mirror's `origin` at `origin` unless it already is (integration finding F1): a mirror made from
    /// another origin (a host whose live worker ran, or a different `[repo] origin`) is otherwise fetched from the
    /// wrong repository and never holds the job's commit. The URLs are not logged: an origin can carry credentials.
    public Promise<Unit> originPointedAt(Path mirror, String origin) {
        return originUrl(mirror).flatMap(current -> repointedUnlessEqual(mirror, current, origin));
    }

    private Promise<Unit> repointedUnlessEqual(Path mirror, Option<String> current, String origin) {
        return current.filter(origin::equals)
                      .isPresent()
               ? Promise.success(unit())
               : repointed(mirror, origin);
    }

    /// `git --git-dir <mirror> config -- remote.origin.url <origin>`.
    private Promise<Unit> repointed(Path mirror, String origin) {
        return zero("config",
                    List.of("git", "--git-dir", mirror.toString(), "config", "--", "remote.origin.url", origin)).onSuccessRun(() -> LOG.info("mirror {}: origin differed from the job's origin; set to the job's origin",
                                                                                                                                             mirror));
    }

    /// `git --git-dir <mirror> config --get remote.origin.url`: the mirror's origin, none when it has none.
    private Promise<Option<String>> originUrl(Path mirror) {
        return run("config",
                   List.of("git", "--git-dir", mirror.toString(), "config", "--get", "remote.origin.url")).map(Git::configValue);
    }

    private static Option<String> configValue(Completed completed) {
        return completed.rc() == 0
               ? some(completed.stdoutText().strip())
               : none();
    }

    /// `git --git-dir <mirror> cat-file -e <sha>^{commit}`: whether the mirror holds the commit.
    public Promise<Boolean> hasCommit(Path mirror, Sha sha) {
        return run("cat-file",
                   List.of("git", "--git-dir", mirror.toString(), "cat-file", "-e", sha.value() + "^{commit}")).map(completed -> completed.rc() == 0);
    }

    /// `git clone --no-checkout --quiet -- <mirror> <target>`. A local clone hard-links `.git/objects`, which is
    /// harmless (git objects are immutable); the A13 seed rule concerns `.m2-local` only (risk R11).
    public Promise<Unit> cloneWorkspace(Path mirror, Path target) {
        return zero("clone",
                    List.of("git", "clone", "--no-checkout", "--quiet", "--", mirror.toString(), target.toString()));
    }

    /// `git -C <ws> checkout --quiet --detach <sha>`; idempotent when the workspace is already at `sha`.
    public Promise<Unit> checkout(Path workspace, Sha sha) {
        return zero("checkout",
                    List.of("git", "-C", workspace.toString(), "checkout", "--quiet", "--detach", sha.value()));
    }

    private Promise<Completed> run(String operation, List<String> argv) {
        return Command.command(argv, env, timeout)
                      .async()
                      .flatMap(runner::run)
                      .mapError(origin -> WorkerError.Git.FACTORY.apply(operation, origin));
    }

    private Promise<Unit> zero(String operation, List<String> argv) {
        return run(operation, argv).flatMap(completed -> succeeded(operation, completed))
                  .mapToUnit();
    }

    private static Promise<Completed> succeeded(String operation, Completed completed) {
        return completed.requireZero()
                        .mapError(origin -> WorkerError.Git.FACTORY.apply(operation, origin))
                        .async();
    }
}
