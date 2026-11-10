package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.store.api.StoredSides;

import static org.assertj.core.api.Assertions.assertThat;

/// m2-design §3.2's mechanism: `diffSides` reads both sides in ONE snapshot, so a retention delete that commits during
/// the read is either seen by both side reads or by neither. The instrument: one job on both sides
/// (`diffSides(a, a)`) while retention deletes its rows one transaction at a time; every read must see the same rows on
/// both sides. Probe: read the two sides outside one transaction (the snapshot dropped from `PgResultStore.diffSides`) →
/// a delete committing between the side reads gives A and B different row counts: red (probabilistic, hence many rows,
/// several readers and a check that reads did overlap the deletes).
@Tag("pg")
class DiffSnapshotIT {
    private static final int ROWS = 400;
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
    void diffSides_duringRetentionDeletes_seesOneSnapshotForBothSides() throws InterruptedException {
        var job = harness.run(TestJobs.gateBySha(TestJobs.sha()), TestJobs.exited(0), rows());
        var done = new AtomicBoolean(false);
        var mixed = new ConcurrentLinkedQueue<String>();
        var partial = new AtomicInteger();
        var readers = IntStream.range(0, READERS)
                               .mapToObj(_ -> Thread.ofVirtual().start(() -> readUntil(done, job, mixed, partial)))
                               .toList();

        for (var deleted = 0; deleted < ROWS; deleted++) {
            deleteOneRow(job);
        }
        done.set(true);
        for (var reader : readers) {
            reader.join();
        }

        assertThat(harness.count("SELECT count(*) AS n FROM testcases WHERE job_id = $1", job.value())).isZero();
        assertThat(mixed).as("reads whose sides saw different rows").isEmpty();
        assertThat(partial.get()).as("reads that overlapped the deletes").isPositive();
    }

    private static List<TestcaseRow> rows() {
        return IntStream.range(0, ROWS)
                        .mapToObj(index -> TestJobs.row("a.A", "t" + index, Outcome.PASSED))
                        .toList();
    }

    private static void readUntil(AtomicBoolean done, JobId job, ConcurrentLinkedQueue<String> mixed, AtomicInteger partial) {
        while (!done.get()) {
            classify(harness.store.results().diffSides(job, job).await(PgFixture.WAIT).unwrap(), mixed, partial);
        }
    }

    private static void classify(StoredSides sides, ConcurrentLinkedQueue<String> mixed, AtomicInteger partial) {
        var a = sides.a().testcases().size();
        var b = sides.b().testcases().size();

        if (a != b) {
            mixed.add(a + " vs " + b);
        } else if (a > 0 && a < ROWS) {
            partial.incrementAndGet();
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
}
