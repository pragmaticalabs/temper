package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.diff.DiffBase;
import org.pragmatica.temper.domain.gate.GateEvidence;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.store.api.BaseRead;
import org.pragmatica.temper.store.api.GateRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// maintainer s44 on m2-design §3.1/§3.2: `diff <job> --base <sha>` reads side B, the Gate candidates, the base selection and
/// side A in ONE snapshot (`ResultStore.diffBase`). The instrument, 100 times: a PASSED Gate A at a fresh SHA and a
/// running Gate B at the same SHA; readers call `diffBase(S, B)` with the real selection (`GateEvidence`, `DiffBase`)
/// while B finishes. In one snapshot B's state among the candidates is B's state on side B, and the base is the one the
/// candidates name (A while B runs, B once it passed). Probe: run the read outside one snapshot (on the pool, or in one
/// READ COMMITTED transaction) → a finish committing between the reads gives a candidate state that side B contradicts:
/// red (probabilistic, hence many iterations and readers; the check that both worlds were seen validates the overlap).
@Tag("pg")
class DiffBaseSnapshotIT {
    private static final int ITERATIONS = 100;
    private static final int READERS = 4;
    private static StoreHarness harness;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void diffBase_duringAFinish_seesOneSnapshotForSelectionAndSides() throws InterruptedException {
        var mixed = new ConcurrentLinkedQueue<String>();
        var running = new AtomicInteger();
        var finished = new AtomicInteger();

        for (var iteration = 0; iteration < ITERATIONS; iteration++) {
            var sha = TestJobs.sha();
            var a = harness.run(TestJobs.gateBySha(sha), TestJobs.exited(0), TestJobs.passing());
            var b = harness.submit(TestJobs.gateBySha(sha));

            harness.grantHead();
            harness.start(b);
            var prefix = ShaPrefix.shaPrefix(sha.value()).unwrap();
            var done = new AtomicBoolean(false);
            var readers = IntStream.range(0, READERS)
                                   .mapToObj(_ -> Thread.ofVirtual().start(() -> readUntil(done, prefix, a, b, running, finished, mixed)))
                                   .toList();

            harness.complete(b, TestJobs.exited(0), TestJobs.passing());
            done.set(true);
            for (var reader : readers) {
                reader.join();
            }
        }

        assertThat(mixed).as("reads whose selection and sides saw different states").isEmpty();
        assertThat(running.get()).as("reads that saw B running").isPositive();
        assertThat(finished.get()).as("reads that saw B finished").isPositive();
    }

    /// Side A is in the snapshot too. The base here is B itself (the one PASSED Gate at the SHA), so sides A and B are one
    /// job read twice, while retention deletes its rows one transaction at a time: in one snapshot both reads see the
    /// same rows. Probe: read side A after the transaction (on the pool) → fewer rows on A than on B: red.
    @Test
    void diffBase_duringRetentionDeletes_readsSideAInTheSameSnapshot() throws InterruptedException {
        var sha = TestJobs.sha();
        var job = harness.run(TestJobs.gateBySha(sha), TestJobs.exited(0), rows());
        var prefix = ShaPrefix.shaPrefix(sha.value()).unwrap();
        var done = new AtomicBoolean(false);
        var mixed = new ConcurrentLinkedQueue<String>();
        var partial = new AtomicInteger();
        var readers = IntStream.range(0, READERS)
                               .mapToObj(_ -> Thread.ofVirtual().start(() -> readRowsUntil(done, prefix, job, mixed, partial)))
                               .toList();

        for (var deleted = 0; deleted < ROWS; deleted++) {
            deleteOneRow(job);
        }
        done.set(true);
        for (var reader : readers) {
            reader.join();
        }

        assertThat(mixed).as("reads whose side A and side B saw different rows").isEmpty();
        assertThat(partial.get()).as("reads that overlapped the deletes").isPositive();
    }

    private static final int ROWS = 400;

    private static List<TestcaseRow> rows() {
        return IntStream.range(0, ROWS)
                        .mapToObj(index -> TestJobs.row("a.A", "t" + index, Outcome.PASSED))
                        .toList();
    }

    private static void readRowsUntil(AtomicBoolean done,
                                      ShaPrefix prefix,
                                      JobId job,
                                      ConcurrentLinkedQueue<String> mixed,
                                      AtomicInteger partial) {
        while (!done.get()) {
            var read = harness.store.results().diffBase(prefix, job, DiffBaseSnapshotIT::chosen).await(PgFixture.WAIT).unwrap();
            var a = read.sides().map(sides -> sides.a().testcases().size()).or(-1);
            var b = read.sides().map(sides -> sides.b().testcases().size()).or(-2);

            if (!a.equals(b)) {
                mixed.add(a + " vs " + b);
            } else if (a > 0 && a < ROWS) {
                partial.incrementAndGet();
            }
        }
    }

    /// As retention deletes: one batch per transaction, inside the write-once door (V1 `temper_write_once`).
    private static void deleteOneRow(JobId job) {
        harness.raw.inTransaction(tx -> tx.completeQuery("SET LOCAL temper.retention = 'on'")
                                          .flatMap(_ -> tx.completeQuery("DELETE FROM testcases WHERE ctid IN "
                                                                         + "(SELECT ctid FROM testcases WHERE job_id = $1 LIMIT 1)",
                                                                         job.value())))
                   .await(PgFixture.WAIT)
                   .unwrap();
    }

    private static void readUntil(AtomicBoolean done,
                                  ShaPrefix prefix,
                                  JobId a,
                                  JobId b,
                                  AtomicInteger running,
                                  AtomicInteger finished,
                                  ConcurrentLinkedQueue<String> mixed) {
        while (!done.get()) {
            classify(harness.store.results().diffBase(prefix, b, DiffBaseSnapshotIT::chosen).await(PgFixture.WAIT).unwrap(),
                     a, b, running, finished, mixed);
        }
    }

    /// The selection `gate` and `diff --base` make, minus the UNKNOWN overlay (no host links here).
    private static Result<Option<JobId>> chosen(List<GateRow> rows) {
        return Result.success(switch (DiffBase.choose(GateEvidence.select(rows.stream().map(GateAnswers::candidate).toList()))) {
            case DiffBase.Found found -> some(found.gate().job());
            case DiffBase.Missing _, DiffBase.Conflicting _ -> none();
        });
    }

    private static void classify(BaseRead read,
                                 JobId a,
                                 JobId b,
                                 AtomicInteger running,
                                 AtomicInteger finished,
                                 ConcurrentLinkedQueue<String> mixed) {
        var candidate = read.candidates().stream().filter(row -> row.job().id().equals(b)).findFirst().map(row -> row.job().state());
        var side = read.sides().map(sides -> sides.b().job().state());
        var base = read.sides().map(sides -> sides.a().job().id());

        if (candidate.equals(Optional.of(JobState.RUNNING)) && side.equals(some(JobState.RUNNING)) && base.equals(some(a))) {
            running.incrementAndGet();
        } else if (candidate.equals(Optional.of(JobState.PASSED)) && side.equals(some(JobState.PASSED)) && base.equals(some(b))) {
            finished.incrementAndGet();
        } else {
            mixed.add("candidate " + candidate + ", side B " + side + ", base " + base);
        }
    }
}
