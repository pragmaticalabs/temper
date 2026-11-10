package org.pragmatica.temper.controller.t3;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// A [TicketTransport] that runs one command per read with the bounds of a collection: at
/// most [#READ_BOUND] and [#MAX_STDOUT] bytes of stdout. A timeout is `timeout`; more output than the cap is
/// `oversize`; a process that cannot start is `exit 127`; a non-zero exit is `exit <n>`, except for an `ssh` command's
/// 255 (ssh's own failure), which is `ssh exit 255: <first stderr line>`.
public record CommandTransport(ProcessRunner runner, Fn1<Result<Command>, String> commands, boolean ssh) implements TicketTransport {
    public static final int MAX_STDOUT = 8 * 1024 * 1024;
    public static final TimeSpan READ_BOUND = TimeSpan.timeSpan(20).seconds();
    /// ssh's exit code for its own failure (connection, authentication, host key).
    static final int SSH_FAILED = 255;

    public static CommandTransport commandTransport(ProcessRunner runner,
                                                    Fn1<Result<Command>, String> commands,
                                                    boolean ssh) {
        return new CommandTransport(runner, commands, ssh);
    }

    @Override
    public Promise<String> read(String host) {
        return commands.apply(host)
                       .map(command -> command.withOutputLimit(MAX_STDOUT))
                       .async()
                       .flatMap(runner::run)
                       .mapError(CommandTransport::failed)
                       .flatMap(this::checked);
    }

    private Promise<String> checked(Completed done) {
        return done.truncated()
               ? TicketError.Oversize.OVERSIZE.promise()
               : done.rc() == 0
                 ? Promise.success(done.stdoutText())
                 : exited(done).promise();
    }

    private TicketError exited(Completed done) {
        return ssh && done.rc() == SSH_FAILED
               ? TicketError.SshExit.sshExit(done.rc(), firstLine(done.stderrText()))
               : TicketError.Exit.exit(done.rc());
    }

    private static String firstLine(String stderr) {
        return stderr.lines()
                     .findFirst()
                     .map(String::strip)
                     .orElse("");
    }

    private static Cause failed(Cause cause) {
        return switch (cause) {
            case ExecError.TimedOut _ -> TicketError.Timeout.TIMEOUT;
            case TicketError known -> known;
            default -> TicketError.Unstartable.unstartable(cause.message());
        };
    }

    /// The command for `host`, as the isolated-environment commands of the other ssh uses: the JVM's environment.
    public static Result<Command> inheriting(List<String> argv, Map<String, String> env) {
        return Command.command(argv, env, READ_BOUND).map(Command::inheritingEnvironment);
    }
}
