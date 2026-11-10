package org.pragmatica.temper.protocol.exec;

import java.io.InputStream;
import java.io.OutputStream;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Promise.promise;


/// [Spawned] over a JDK `Process`.
record SpawnedProcess(Process process) implements Spawned {
    static SpawnedProcess spawnedProcess(Process process) {
        return new SpawnedProcess(process);
    }

    @Override
    public InputStream stdout() {
        return process.getInputStream();
    }

    @Override
    public OutputStream stdin() {
        return process.getOutputStream();
    }

    @Override
    public InputStream stderr() {
        return process.getErrorStream();
    }

    @Override
    public Promise<Integer> exit() {
        // Not process.onExit(): the JDK completes that future's continuation on ForkJoinPool.commonPool(), which a
        // 2-vCPU host sizes to ONE worker; one blocked caller there and no process ever reports its exit.
        return promise(promise -> Thread.ofVirtual()
                                        .name("process-exit-" + process.pid())
                                        .start(() -> awaitExit(promise)));
    }

    private void awaitExit(Promise<Integer> promise) {
        try {
            promise.succeed(process.waitFor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            promise.fail(Causes.fromThrowable(e));
        }
    }

    @Override
    public Unit kill() {
        return JdkProcessRunner.destroyTree(process);
    }

    @Override
    public long pid() {
        return process.pid();
    }
}
