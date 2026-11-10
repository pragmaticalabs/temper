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
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.GateRow;

import static org.assertj.core.api.Assertions.assertThat;

/// m2-design §1.2 (`GateSnapshotIT`): a supersede that commits while `gate` reads is either wholly in the answer or not
/// at all. 200 times: a QUEUED by-ref Gate at S1, then a by-ref Gate of the same ref at S2 (which supersedes it, cancelling
/// it at once: job CANCELLED and superseded, unit CANCELLED, result written, one commit) while readers read S1's
/// candidates in a loop. Every read must be one of the two worlds:
///
/// - before: the Gate, QUEUED, not superseded, its unit QUEUED, no result;
/// - after: no candidate (a superseded Gate is not one).
///
/// The read is two statements (jobs with results and judges, then units). Probe: drop the snapshot (`Sql.snapshot`,
/// REPEATABLE READ) from `gateCandidates` → a commit between the two statements yields the job from before with its unit
/// from after (CANCELLED): a mixed world, seen within the loop (probabilistic, hence many readers and iterations). The
/// instrument is checked too: both worlds must have been seen, so the reads did overlap the supersedes.
@Tag("pg")
class GateSnapshotIT {
    private static final int ITERATIONS = 200;
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
    void gateRead_duringASupersede_seesOneWholeWorld() throws InterruptedException {
        var mixed = new ConcurrentLinkedQueue<String>();
        var before = new AtomicInteger();
        var after = new AtomicInteger();

        for (var iteration = 0; iteration < ITERATIONS; iteration++) {
            var ref = "snapshot/" + iteration;
            var s1 = TestJobs.sha();
            var victim = harness.submit(TestJobs.gateByRef(ref, s1, false));
            var done = new AtomicBoolean(false);
            var readers = IntStream.range(0, READERS)
                                   .mapToObj(_ -> Thread.ofVirtual()
                                                        .start(() -> readUntil(done, s1, victim, before, after, mixed)))
                                   .toList();

            harness.submit(TestJobs.gateByRef(ref, TestJobs.sha(), false));
            done.set(true);
            for (var reader : readers) {
                reader.join();
            }
            assertThat(GateAnswers.rows(harness.store, s1)).as("after the supersede committed").isEmpty();
        }

        assertThat(mixed).as("mixed worlds").isEmpty();
        assertThat(before.get()).as("reads that saw the world before").isPositive();
        assertThat(after.get()).as("reads that saw the world after").isPositive();
    }

    private static void readUntil(AtomicBoolean done,
                                  Sha sha,
                                  JobId victim,
                                  AtomicInteger before,
                                  AtomicInteger after,
                                  ConcurrentLinkedQueue<String> mixed) {
        while (!done.get()) {
            classify(GateAnswers.rows(harness.store, sha), victim, before, after, mixed);
        }
    }

    private static void classify(List<GateRow> rows,
                                 JobId victim,
                                 AtomicInteger before,
                                 AtomicInteger after,
                                 ConcurrentLinkedQueue<String> mixed) {
        if (rows.isEmpty()) {
            after.incrementAndGet();
        } else if (isBefore(rows, victim)) {
            before.incrementAndGet();
        } else {
            mixed.add(rows.toString());
        }
    }

    private static boolean isBefore(List<GateRow> rows, JobId victim) {
        var row = rows.getFirst();

        return rows.size() == 1
               && row.job().id().equals(victim)
               && row.job().state() == JobState.QUEUED
               && !row.job().superseded()
               && row.record().isEmpty()
               && row.job().units().stream().allMatch(unit -> unit.state() == UnitState.QUEUED);
    }
}
