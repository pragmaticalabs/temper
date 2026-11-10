package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// F7 for T2: usage errors exit 64 as documented, although `Result.all` wraps them in a composite. And T2 numbers its
/// reports as T1 does (T2 run 2 wrote over run 1's report): never written over, a lost race takes the next number.
class T2HarnessTest {
    private static final int RUNS = 8;

    @Test
    void run_exits64_whenOnlyRequiredFlagsAreMissing(@TempDir Path home) throws Exception {
        var token = Files.writeString(home.resolve("token"), "t\n");

        assertThat(T2Harness.run(List.of("--controller", "http://127.0.0.1:1", "--token-file", token.toString(), "--sha", "abcdef1",
                                         "--parent-job", "7"),
                                 Map.of("HOME", home.toString()))).as("one flag missing").isEqualTo(T1Harness.EXIT_USAGE);
        assertThat(T2Harness.run(List.of("--controller", "http://127.0.0.1:1", "--token-file", token.toString()),
                                 Map.of("HOME", home.toString()))).as("several flags missing").isEqualTo(T1Harness.EXIT_USAGE);
    }

    @Test
    void run_exits69_whenTheTokenFileCannotBeRead(@TempDir Path home) {
        assertThat(T2Harness.run(List.of("--controller", "http://127.0.0.1:1", "--token-file", home.resolve("absent").toString()),
                                 Map.of("HOME", home.toString()))).isEqualTo(T1Harness.EXIT_ERROR);
    }

    @Test
    void report_ofASecondRunTheSameDate_isNumbered_andTheFirstIsKept(@TempDir Path out) {
        var base = T2Harness.base(out, "2026-10-14");
        var first = T2Harness.report(base, "run 1\n");
        var second = T2Harness.report(base, "run 2\n");

        assertThat(first).isEqualTo(Result.success(out.resolve("T2-2026-10-14.md")));
        assertThat(second).isEqualTo(Result.success(out.resolve("T2-2026-10-14.2.md")));
        assertThat(out.resolve("T2-2026-10-14.md")).hasContent("run 1");
        assertThat(out.resolve("T2-2026-10-14.2.md")).hasContent("run 2");
    }

    @RepeatedTest(5)
    void report_ofConcurrentRuns_neverOverwrites_andEachReportIsKept(@TempDir Path out) throws Exception {
        var base = T2Harness.base(out, "2026-10-14");
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Result<Path>>>();

        try (var pool = Executors.newFixedThreadPool(RUNS)) {
            for (var i = 0; i < RUNS; i++) {
                var text = "run " + i + "\n";

                futures.add(pool.submit(() -> {
                    start.await();
                    return T2Harness.report(base, text);
                }));
            }
            start.countDown();
            for (var future : futures) {
                assertThat(future.get().isSuccess()).as("a run that lost its report").isTrue();
            }
        }
        try (Stream<Path> reports = Files.list(out)) {
            assertThat(reports.map(T2HarnessTest::text)).as("one report per run, none overwritten")
                                                        .containsExactlyInAnyOrderElementsOf(Stream.iterate(0, i -> i < RUNS, i -> i + 1)
                                                                                                   .map(i -> "run " + i + "\n")
                                                                                                   .toList());
        }
    }

    private static String text(Path report) {
        try {
            return Files.readString(report);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
