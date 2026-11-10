package org.pragmatica.temper.controller.fake;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.pragmatica.lang.Unit.unit;

/// A fake ssh process: the controller's stdin/stdout are [BytePipe]s whose other ends belong to a fake worker.
public final class PipeProcess implements Spawned {
    private final BytePipe toController = new BytePipe();
    private final BytePipe toWorker = new BytePipe();
    private final BytePipe stderr = new BytePipe();
    private final Promise<Integer> exit = Promise.promise();
    private final AtomicBoolean killed = new AtomicBoolean();

    /// What the fake worker reads (the controller's frames).
    public InputStream workerIn() {
        return toWorker.in;
    }

    /// Sees each write of the controller, on the controller's thread, before the worker can read it.
    public void tapControllerWrites(Consumer<byte[]> tap) {
        toWorker.tap(tap);
    }

    /// Where the fake worker writes its frames.
    public OutputStream workerOut() {
        return toController.out;
    }

    /// Where the fake ssh writes its stderr (a host-key failure, m4-design §2.2).
    public OutputStream workerErr() {
        return stderr.out;
    }

    /// While set, stderr stays open after the exit or kill: a stale session's late stderr line can still be written.
    public volatile boolean keepStderr = false;

    /// The worker exits: EOF on the controller's stdout and stderr, then the exit code.
    public void workerExits(int rc) {
        toController.shut();
        if (!keepStderr) {
            stderr.shut();
        }
        exit.succeed(rc);
    }

    public boolean killed() {
        return killed.get();
    }

    @Override
    public InputStream stdout() {
        return toController.in;
    }

    @Override
    public OutputStream stdin() {
        return toWorker.out;
    }

    @Override
    public InputStream stderr() {
        return stderr.in;
    }

    @Override
    public Promise<Integer> exit() {
        return exit;
    }

    /// Runs first in [#kill], on the killing thread (a test can hold a close half-way).
    public volatile Runnable onKill = () -> {};

    @Override
    public Unit kill() {
        onKill.run();
        killed.set(true);
        toWorker.shut();
        workerExits(137);
        return unit();
    }

    @Override
    public long pid() {
        return 4242;
    }
}
