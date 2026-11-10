package org.pragmatica.temper.acceptance.trust;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.pragmatica.lang.Result.success;


/// The processes the T1/T2 tooling runs (git, find, tar, bb-report, the temper CLI): argv only, inheriting this
/// JVM's environment overlaid with `env`.
public record Processes(ProcessRunner runner) {
    /// Bytes of output kept in a [TrustError.CommandFailed].
    public static final int OUTPUT_TAIL = 2000;

    public static Processes processes(ProcessRunner runner) {
        return new Processes(runner);
    }

    /// Runs `argv` to completion whatever its exit code.
    public Promise<Completed> run(List<String> argv, Map<String, String> env, TimeSpan timeout) {
        return Command.command(argv, env, timeout)
                      .map(Command::inheritingEnvironment)
                      .async()
                      .flatMap(runner::run)
                      .mapError(origin -> TrustError.Io.FACTORY.apply("run " + argv.getFirst(),
                                                                      origin));
    }

    /// Runs `argv` and answers its stdout; any exit but 0 is [TrustError.CommandFailed].
    public Promise<String> output(List<String> argv, TimeSpan timeout) {
        return run(argv, Map.of(), timeout).flatMap(completed -> zero(completed).async());
    }

    public static Result<String> zero(Completed completed) {
        return completed.rc() == 0
               ? success(completed.stdoutText())
               : TrustError.CommandFailed.FACTORY.apply(completed.executable(),
                                                        completed.rc(),
                                                        tail(completed.stdoutText() + completed.stderrText()))
                                                 .result();
    }

    private static String tail(String output) {
        return output.substring(Math.max(0, output.length() - OUTPUT_TAIL));
    }
}
