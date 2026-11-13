package org.pragmatica.temper.cli.command;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.protocol.api.LeaseCall;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// The tree report of `task hb --tree DIR` (lane registry WP3, vt39 F3), measured by the CLI with git so the agent
/// cannot misreport it: `head` is `HEAD`; `dirty` the number of `git status --porcelain` entries (untracked ones
/// included; a directory of new files is one entry); `unpushed` the commits of `HEAD` the remote lacks.
///
/// `unpushed` is `@{upstream}..HEAD` when the branch has an upstream. With none (a new branch never pushed with `-u`, a
/// detached HEAD) it is the commits of `HEAD` that no remote-tracking ref contains (`HEAD --not --remotes`): a branch
/// pushed without `-u` reads 0 once the push has updated its remote-tracking ref, one never pushed reads as every commit
/// the remotes lack. A tree with no remote at all reads its whole history: over-reporting, never under-reporting. The
/// CLI does not fetch, so "the remote" is the remote-tracking refs as of the tree's last fetch or push.
///
/// A path that is not a git tree, or has no commit, is [CliError.BadSetting] (exit 64): nothing is sent.
sealed interface TreeProbe {
    Map<String, String> ENV = Map.of("GIT_TERMINAL_PROMPT", "0");
    TimeSpan QUICK = TimeSpan.timeSpan(2).minutes();

    static Promise<LeaseCall.Tree> measure(ProcessRunner runner, String tree) {
        return head(runner, tree).flatMap(head -> dirty(runner, tree).flatMap(dirty -> unpushed(runner, tree).map(unpushed -> new LeaseCall.Tree(head,
                                                                                                                                                 dirty,
                                                                                                                                                 unpushed))))
                   .mapError(cause -> CliError.BadSetting.FACTORY.apply("--tree", tree, cause));
    }

    private static Promise<String> head(ProcessRunner runner, String tree) {
        return git(runner, tree, "rev-parse", "--verify", "HEAD").map(text -> text.strip());
    }

    private static Promise<Integer> dirty(ProcessRunner runner, String tree) {
        return git(runner, tree, "status", "--porcelain").map(text -> (int) text.lines()
                                                                                .filter(line -> !line.isBlank())
                                                                                .count());
    }

    /// The upstream's own count when there is one, else commits no remote-tracking ref has.
    private static Promise<Integer> unpushed(ProcessRunner runner, String tree) {
        return run(runner, tree, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}").flatMap(upstream -> upstream.rc() == 0
                                                                                                                         ? count(runner,
                                                                                                                                 tree,
                                                                                                                                 "@{upstream}..HEAD")
                                                                                                                         : count(runner,
                                                                                                                                 tree,
                                                                                                                                 "HEAD",
                                                                                                                                 "--not",
                                                                                                                                 "--remotes"));
    }

    private static Promise<Integer> count(ProcessRunner runner, String tree, String... range) {
        var args = new String[range.length + 2];

        args[0] = "rev-list";
        args[1] = "--count";
        System.arraycopy(range, 0, args, 2, range.length);

        return git(runner, tree, args).flatMap(text -> Number.parseInt(text.strip()).async());
    }

    private static Promise<String> git(ProcessRunner runner, String tree, String... args) {
        return run(runner, tree, args).flatMap(done -> done.requireZero()
                                                           .async())
                  .map(Completed::stdoutText);
    }

    private static Promise<Completed> run(ProcessRunner runner, String tree, String... args) {
        var argv = new java.util.ArrayList<String>(List.of("git",
                                                           "-C",
                                                           Path.of(tree).toString()));

        argv.addAll(List.of(args));

        return Command.command(argv, ENV, QUICK)
                      .map(Command::inheritingEnvironment)
                      .async()
                      .flatMap(runner::run);
    }

    record unused() implements TreeProbe {}
}
