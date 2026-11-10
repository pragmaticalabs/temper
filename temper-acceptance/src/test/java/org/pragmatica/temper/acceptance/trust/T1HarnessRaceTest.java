package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// N3, adopted from the vm1r8 verifier's probe: harness runs of the same date started or finishing at the same
/// moment. None overwrites another's report or evidence (`CREATE_NEW`, `createDirectory`), and none loses its report:
/// the run that loses a name takes the next free number instead of failing with exit 69.
class T1HarnessRaceTest {
    private static final int RUNS = 8;

    @RepeatedTest(5)
    void concurrentLiveRuns_eachClaimItsOwnEvidence_andKeepTheirReport(@TempDir Path out) throws Exception {
        var args = T1HarnessReportTest.live(out);
        var written = race(() -> T1Harness.evidence(args)
                                          .flatMap(evidence -> T1Harness.written(args, evidence, T1HarnessReportTest.PLANTED, List.of())));

        assertThat(written).as("runs that lost their report (exit 69)").allMatch(Result::isSuccess);
        assertThat(entries(out, ".md")).as("one report per run, none overwritten").hasSize(RUNS);
        assertThat(entries(out, "").stream().filter(Files::isDirectory)).as("one evidence directory per run").hasSize(RUNS);
        assertThat(written).as("each report is named for its own evidence directory")
                           .allMatch(result -> Files.isDirectory(evidenceOf(result)));
    }

    @RepeatedTest(5)
    void concurrentReruns_neverOverwrite_andEachReportIsKept(@TempDir Path out) throws Exception {
        var args = T1HarnessReportTest.rerun(out, "--rerun");
        var evidence = Files.createDirectory(out.resolve("T1-2026-10-14"));
        var written = race(() -> T1Harness.written(args, evidence, T1HarnessReportTest.PLANTED, List.of()));

        assertThat(written).as("re-runs that lost their report (exit 69)").allMatch(Result::isSuccess);
        assertThat(entries(out, "-rerun")).as("one report per re-run, none overwritten").hasSize(RUNS);
    }

    private static List<Result<T1Harness.Written>> race(Callable<Result<T1Harness.Written>> run) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Result<T1Harness.Written>>>();

        try (var pool = Executors.newFixedThreadPool(RUNS)) {
            for (int i = 0; i < RUNS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return run.call();
                }));
            }
            start.countDown();
            var results = new ArrayList<Result<T1Harness.Written>>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    /// `<out>/T1-<date>[.N]` of the report `<out>/T1-<date>[.N].md`.
    private static Path evidenceOf(Result<T1Harness.Written> result) {
        var report = result.expect("written").report();
        var name = report.getFileName().toString();

        return report.resolveSibling(name.substring(0, name.length() - ".md".length()));
    }

    /// The `T1-` entries of `out` whose name contains `part`.
    private static List<Path> entries(Path out, String part) throws Exception {
        try (Stream<Path> listed = Files.list(out)) {
            return listed.filter(path -> path.getFileName().toString().startsWith("T1-")
                                         && path.getFileName().toString().contains(part))
                         .toList();
        }
    }
}
