package org.pragmatica.temper.protocol.exec;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A process to run: an argv array, never a shell command line (R-SHELL). With `inheritEnv = false` the
/// process sees ONLY `env`; the worker's docker and git calls use that, copying `PATH`/`HOME` explicitly.
/// `outputLimit` caps the bytes kept per stream; the rest is read and discarded (`Completed.truncated`).
///
/// `stdoutFile` / `stderrFile` (M1) send a stream straight to a file, truncated first, instead of capturing it
/// (m1-design §1.2 bb-hold's output, §3.2 an evidence tarball, §3.4 a `pg_dump`): the captured bytes of that
/// stream are then empty, and nothing caps the file, so the caller bounds it.
public record Command(List<String> argv,
                      Option<Path> workDir,
                      Map<String, String> env,
                      boolean inheritEnv,
                      TimeSpan timeout,
                      int outputLimit,
                      Option<Path> stdoutFile,
                      Option<Path> stderrFile) {
    /// Default per-stream capture cap.
    public static final int DEFAULT_OUTPUT_LIMIT = 64 * 1024 * 1024;

    public Command {
        argv = List.copyOf(argv);
        env = Map.copyOf(env);
    }

    /// A command with an isolated environment (`inheritEnv = false`, only `env`), in the current directory.
    public static Result<Command> command(List<String> argv, Map<String, String> env, TimeSpan timeout) {
        return Verify.ensure(argv,
                             Predicate.not(List::isEmpty),
                             ExecError.General.EMPTY_ARGV)
                     .map(valid -> new Command(valid,
                                               none(),
                                               env,
                                               false,
                                               timeout,
                                               DEFAULT_OUTPUT_LIMIT,
                                               none(),
                                               none()));
    }

    /// The same command, run in `directory`.
    public Command inDirectory(Path directory) {
        return new Command(argv, some(directory), env, inheritEnv, timeout, outputLimit, stdoutFile, stderrFile);
    }

    /// The same command, inheriting this JVM's environment (then overlaid with `env`).
    public Command inheritingEnvironment() {
        return new Command(argv, workDir, env, true, timeout, outputLimit, stdoutFile, stderrFile);
    }

    /// The same command, keeping at most `bytes` of each output stream (at least 1).
    public Command withOutputLimit(int bytes) {
        return new Command(argv, workDir, env, inheritEnv, timeout, Math.max(1, bytes), stdoutFile, stderrFile);
    }

    /// The same command, writing its stdout to `file` instead of capturing it.
    public Command stdoutTo(Path file) {
        return new Command(argv, workDir, env, inheritEnv, timeout, outputLimit, some(file), stderrFile);
    }

    /// The same command, writing its stderr to `file` instead of capturing it.
    public Command stderrTo(Path file) {
        return new Command(argv, workDir, env, inheritEnv, timeout, outputLimit, stdoutFile, some(file));
    }

    /// The executable, for diagnostics.
    public String executable() {
        return argv.getFirst();
    }
}
