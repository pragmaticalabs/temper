package org.pragmatica.temper.protocol.exec;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// CI-dependence pin: a 2-vCPU host has a common pool of ONE worker, and a caller blocked in it (a test's
/// `supplyAsync`, a request thread) must not stop a process's exit from being reported.
class SpawnedProcessTest {
    @Test
    void exit_isReported_whileEveryCommonPoolWorkerIsBlocked() throws IOException {
        var hold = new CountDownLatch(1);
        var blockers = new ArrayList<CompletableFuture<Void>>();

        try {
            for (int i = 0; i <= ForkJoinPool.commonPool().getParallelism(); i++) {
                blockers.add(CompletableFuture.runAsync(() -> await(hold)));
            }

            var process = SpawnedProcess.spawnedProcess(HermeticProcess.builder("/bin/sh", "-c", "exit 3").start());
            var exit = process.exit().await(timeSpan(10).seconds());
            int code = exit.fold(cause -> - 1, value -> value);

            assertThat(code).isEqualTo(3);
        } finally {
            hold.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
