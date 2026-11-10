package org.pragmatica.temper.protocol.exec;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;


/// [ProcessRunner] over `ProcessBuilder`. stdout and stderr are drained on their own virtual threads while a
/// third waits for exit, so a chatty process can never block on a full pipe.
record JdkProcessRunner() implements ProcessRunner {
    /// How long output may stay open after the process exits.
    static final TimeSpan DRAIN_GRACE = TimeSpan.timeSpan(5).seconds();

    static JdkProcessRunner jdkProcessRunner() {
        return new JdkProcessRunner();
    }

    @Override
    public Promise<Completed> run(Command command) {
        return start(command).async()
                    .flatMap(process -> complete(command, process));
    }

    @Override
    public Result<Spawned> spawn(Command command) {
        return start(command).map(SpawnedProcess::spawnedProcess);
    }

    /// Destroys the process's descendants, then the process itself.
    static Unit destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();

        return Unit.unit();
    }

    private static Result<Process> start(Command command) {
        return Result.lift(thrown -> ExecError.StartFailed.FACTORY.apply(command.executable(),
                                                                         Causes.fromThrowable(thrown)),
                           () -> builder(command).start());
    }

    private static ProcessBuilder builder(Command command) {
        var builder = new ProcessBuilder(command.argv());

        command.workDir().onPresent(directory -> builder.directory(directory.toFile()));
        command.stdoutFile().onPresent(file -> builder.redirectOutput(file.toFile()));
        command.stderrFile().onPresent(file -> builder.redirectError(file.toFile()));
        if (!command.inheritEnv()) {
            builder.environment().clear();
        }

        builder.environment().putAll(command.env());

        return builder;
    }

    /// Starts draining stdout and stderr (so a chatty process never blocks on a full pipe), waits for exit,
    /// then joins the drains. The exit wait comes first so a timeout fails as a bare [ExecError.TimedOut]
    /// (destroying the process tree also ends both drains). Package-private so a test can hand it a process whose
    /// output stays open after exit, which a real process cannot be relied on to produce (see [#collected]).
    static Promise<Completed> complete(Command command, Process process) {
        var stdout = drain(command, process.getInputStream());
        var stderr = drain(command, process.getErrorStream());

        return exit(command, process).flatMap(rc -> collected(command, process, rc, stdout, stderr));
    }

    /// Joins the drains within a grace period. A background child that inherited the pipes keeps them open
    /// after exit and is no longer a descendant, so it cannot be killed here; past the grace period the
    /// streams are closed (which ends the drains). Either that or a read failure fails the run as
    /// [ExecError.OutputNotCollected], with the underlying cause as its origin.
    ///
    /// Whether a held pipe reaches the grace at all is decided by the JDK at exit: its exit hook and a read lock the
    /// same stream, and the hook replaces the stream with the bytes already buffered. A drain blocked in a read at
    /// exit keeps the pipe, so a background child holding it fails the run as above. A process that exits before its
    /// drains start reading completes with the output buffered at exit, and what a background child writes later is
    /// lost. Either way the run returns within the grace after exit.
    private static Promise<Completed> collected(Command command,
                                                Process process,
                                                int rc,
                                                Promise<Captured> stdout,
                                                Promise<Captured> stderr) {
        // The close may run late: it only ends drains whose results nobody reads once the run failed.
        return Promise.all(stdout, stderr)
                      .map((out, err) -> Completed.completed(command.executable(),
                                                             rc,
                                                             out.bytes(),
                                                             err.bytes(),
                                                             out.truncated() || err.truncated()))
                      .timeout(drainGrace(command))
                      .onFailure(_ -> closeOutput(process))
                      .mapError(origin -> ExecError.OutputNotCollected.FACTORY.apply(command.executable(),
                                                                                     origin));
    }

    private static TimeSpan drainGrace(Command command) {
        return command.timeout()
                      .compareTo(DRAIN_GRACE) < 0
               ? command.timeout()
               : DRAIN_GRACE;
    }

    /// FER (degrade forward): closing an exited process's pipes only unblocks our drain threads. Each pipe is
    /// closed on its own, so one failed close never skips the other; a failed close leaves that one drain thread
    /// parked on a pipe of a process that already exited, which costs at most one virtual thread per stream and
    /// nothing else, so the failure is dropped deliberately.
    private static Unit closeOutput(Process process) {
        return Result.all(closed(process.getInputStream()),
                          closed(process.getErrorStream()))
                     .map((_, _) -> Unit.unit())
                     .or(Unit.unit());
    }

    private static Result<Unit> closed(InputStream stream) {
        return Result.lift(Causes::fromThrowable, stream::close);
    }

    /// Captured output of one stream: at most `outputLimit` bytes, the rest read and discarded.
    private record Captured(byte[] bytes, boolean truncated) {
        static Captured captured(byte[] bytes, boolean truncated) {
            return new Captured(bytes, truncated);
        }
    }

    /// Closes stdin (the process gets EOF at once), then waits for exit within the timeout.
    private static Promise<Integer> exit(Command command, Process process) {
        return closeStdin(command, process).flatMap(_ -> exitCode(command, process));
    }

    private static Promise<Captured> drain(Command command, InputStream stream) {
        return Promise.lift(ioError(command),
                            () -> stream.readNBytes(command.outputLimit()))
                      .flatMap(head -> discardRest(command, stream, head));
    }

    private static Promise<Captured> discardRest(Command command, InputStream stream, byte[] head) {
        return Promise.lift(ioError(command),
                            () -> stream.transferTo(OutputStream.nullOutputStream()))
                      .map(rest -> Captured.captured(head, rest > 0));
    }

    private static Promise<Unit> closeStdin(Command command, Process process) {
        return Promise.lift(ioError(command),
                            () -> process.getOutputStream()
                                         .close());
    }

    private static Promise<Integer> exitCode(Command command, Process process) {
        return Promise.lift(ioError(command),
                            () -> process.waitFor(command.timeout().nanos(),
                                                  TimeUnit.NANOSECONDS))
                      .flatMap(exited -> exitedOrKilled(command, process, exited));
    }

    /// On timeout the process tree is destroyed (which also ends the drains) and the run fails TimedOut.
    private static Promise<Integer> exitedOrKilled(Command command, Process process, boolean exited) {
        if (exited) {
            return Promise.success(process.exitValue());
        }

        destroyTree(process);

        return ExecError.TimedOut.FACTORY.apply(command.executable(),
                                                command.timeout())
                                         .promise();
    }

    private static Fn1<ExecError.Io, Throwable> ioError(Command command) {
        return thrown -> ExecError.Io.FACTORY.apply(command.executable(), Causes.fromThrowable(thrown));
    }
}
