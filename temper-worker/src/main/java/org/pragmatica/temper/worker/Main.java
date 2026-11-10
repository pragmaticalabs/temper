package org.pragmatica.temper.worker;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.InstantSource;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.frame.FrameReader;
import org.pragmatica.temper.protocol.frame.FrameWriter;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.host.HostLock;
import org.pragmatica.temper.worker.link.FrameLoop;
import org.pragmatica.temper.worker.link.Outbox;
import org.pragmatica.temper.worker.unit.Timers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The worker process (m0-design §7.2), started by `worker-launch run <ver>` over `ssh -T`:
///
/// 1. stdout is captured as the frame stream, then `System.out` is rebound to stderr, so a stray library print
///    can never corrupt a frame (logs go to stderr only);
/// 2. `~/.temper/worker.lock` is taken (waiting up to 30 s; exit 75 when it stays held);
/// 3. the unit directories are scanned and `Hello` is sent;
/// 4. frames are served until EOF, `Shutdown` or `Incompatible` (exit 0); detached containers keep running.
///
/// Exit codes: 0 normal end, 1 the worker could not start, 2 the frame stream broke, 75 host lock held.
public sealed interface Main {
    int EXIT_START_FAILED = 1;

    /// JVM entry point: the signature is the JVM's. stdout is captured and rebound BEFORE anything else runs, and no
    /// logger is a constant of this interface: logging initialises on first use, after the rebinding, so even a
    /// logback status message printed to `System.out` lands on stderr instead of corrupting a frame.
    @Contract
    static void main(String[] args) {
        var frames = new FileOutputStream(FileDescriptor.out);

        System.setOut(System.err);
        System.exit(run(frames, System.in));
    }

    @TerminalOperation
    static int run(OutputStream frames, InputStream controller) {
        return WorkerConfig.workerConfig(System.getenv()).fold(Main::startFailed,
                                                               config -> locked(config, frames, controller));
    }

    @TerminalOperation
    private static int locked(WorkerConfig config, OutputStream frames, InputStream controller) {
        return HostLock.hostLock(config.paths().lockFile(),
                                 config.timings().lockWait())
                       .fold(Main::lockFailed,
                             lock -> served(config, lock, frames, controller));
    }

    /// Serves the session while holding the lock; the explicit release at the end also keeps the lock reachable
    /// for the whole session (an unreachable channel could be closed by its cleaner, releasing the lock early).
    @TerminalOperation
    private static int served(WorkerConfig config, HostLock lock, OutputStream frames, InputStream controller) {
        var mapper = TemperJson.mapper();
        var outbox = Outbox.framed(FrameWriter.frameWriter(frames), mapper);
        var exit = WorkerAssembly.assemble(config,
                                           ProcessRunner.processRunner(),
                                           mapper,
                                           outbox,
                                           InstantSource.system(),
                                           Timers.shared())
                                 .flatMap(assembly -> greeted(assembly, outbox))
                                 .await()
                                 .fold(Main::startFailed,
                                       assembly -> looped(config, assembly, outbox, controller));

        lock.release()
            .onFailure(cause -> Lazy.LOG.warn("releasing the host lock failed (the OS releases it at exit): {}",
                                              cause));

        return exit;
    }

    private static Promise<WorkerAssembly> greeted(WorkerAssembly assembly, Outbox outbox) {
        return assembly.hello()
                       .flatMap(hello -> outbox.post(hello)
                                               .async())
                       .map(_ -> assembly);
    }

    /// Serves frames until the session ends; the heartbeat starts once the controller has said `Welcome`.
    private static int looped(WorkerConfig config, WorkerAssembly assembly, Outbox outbox, InputStream controller) {
        return FrameLoop.frameLoop(FrameReader.frameReader(controller),
                                   TemperJson.mapper(),
                                   outbox,
                                   assembly.router(),
                                   () -> assembly.heartbeat()
                                                 .start(config.timings().heartbeat()))
                        .run();
    }

    private static int lockFailed(Cause cause) {
        Lazy.LOG.error("worker not started: {}", cause);

        return cause instanceof WorkerError.LockTimeout
               ? HostLock.EXIT_LOCKED
               : EXIT_START_FAILED;
    }

    private static int startFailed(Cause cause) {
        Lazy.LOG.error("worker not started: {}", cause);

        return EXIT_START_FAILED;
    }

    /// Initialised on first use (class initialisation is lazy), which is after stdout was rebound.
    final class Lazy {
        static final Logger LOG = LoggerFactory.getLogger(Main.class);

        private Lazy() {}
    }

    record unused() implements Main {}
}
