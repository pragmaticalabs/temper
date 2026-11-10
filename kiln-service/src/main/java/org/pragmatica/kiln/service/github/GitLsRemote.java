package org.pragmatica.kiln.service.github;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// [BranchHeads] by `git ls-remote --heads https://github.com/<repo>.git`, never prompting for credentials
/// (`GIT_TERMINAL_PROMPT=0`), within `timeout`. A non-zero exit or a timeout fails the read. A line that is not
/// `<sha>\trefs/heads/<name>`, or names no valid branch, is left out.
public record GitLsRemote(ProcessRunner runner, TimeSpan timeout) implements BranchHeads {
    public static final TimeSpan TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final Pattern HEAD = Pattern.compile("^[0-9a-f]{40}\trefs/heads/(.+)$", Pattern.MULTILINE);

    public static GitLsRemote gitLsRemote(ProcessRunner runner, TimeSpan timeout) {
        return new GitLsRemote(runner, timeout);
    }

    @Override
    public Promise<List<BranchName>> heads(String repo) {
        return listed(repo).map(Completed::stdoutText)
                     .map(GitLsRemote::branches);
    }

    /// The command run to a zero exit.
    private Promise<Completed> listed(String repo) {
        return Command.command(List.of("git", "ls-remote", "--heads", "https://github.com/" + repo + ".git"),
                               Map.of("GIT_TERMINAL_PROMPT", "0"),
                               timeout)
                      .async()
                      .flatMap(runner::run)
                      .flatMap(GitLsRemote::zero);
    }

    private static Promise<Completed> zero(Completed completed) {
        return completed.requireZero()
                        .async();
    }

    static List<BranchName> branches(String stdout) {
        return HEAD.matcher(stdout)
                   .results()
                   .map(found -> BranchName.branchName(found.group(1)))
                   .flatMap(Result::stream)
                   .toList();
    }
}
