package org.pragmatica.temper.domain.retention;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// m1-design §4.1, §9.5: `keep` and non-terminal jobs are exempt; each clock; events and dumps.
class RetentionPlanTest {
    private static final RetentionPolicy POLICY = RetentionPolicy.DEFAULT;
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final JobId JOB = ok(JobId.jobId(4711));
    private static final EvidenceItem SMALL = EvidenceItem.evidenceItem(1, UnitName.GATE, EvidenceKind.SMALL, NOW.minus(Duration.ofDays(200)));
    private static final EvidenceItem FULL = EvidenceItem.evidenceItem(2, UnitName.GATE, EvidenceKind.FULL, NOW.minus(Duration.ofDays(200)));

    private static JobRetention finished(Duration ago, boolean keep) {
        return JobRetention.jobRetention(JOB, keep, true, Option.some(NOW.minus(ago)), List.of(SMALL, FULL), List.of("bigboy"), true);
    }

    private static List<RetentionAction> plan(JobRetention job) {
        return RetentionPlan.plan(POLICY, NOW, List.of(job), Option.none(), List.of());
    }

    @Nested
    class Exemptions {
        @Test
        void plan_keptJob_producesNoAction_everAfter() {
            assertThat(plan(finished(Duration.ofDays(400), true))).isEmpty();
        }

        @Test
        void plan_nonTerminalJob_producesNoAction() {
            var running = JobRetention.jobRetention(JOB, false, false, Option.some(NOW.minus(Duration.ofDays(400))), List.of(SMALL, FULL),
                                                    List.of("bigboy"), true);
            var unfinished = JobRetention.jobRetention(JOB, false, true, Option.none(), List.of(SMALL, FULL), List.of("bigboy"), true);

            assertThat(plan(running)).isEmpty();
            assertThat(plan(unfinished)).isEmpty();
        }
    }

    @Nested
    class Clocks {
        @Test
        void plan_beforeAnyClock_isEmpty() {
            assertThat(plan(finished(Duration.ofHours(23), false))).isEmpty();
        }

        @Test
        void plan_after24h_deletesTheCheckoutOnly() {
            assertThat(plan(finished(Duration.ofHours(24), false))).containsExactly(new RetentionAction.DeleteCheckout("bigboy", JOB));
        }

        @Test
        void plan_after14d_alsoDeletesTheFullTarball() {
            assertThat(plan(finished(Duration.ofDays(14), false))).containsExactlyInAnyOrder(new RetentionAction.DeleteEvidence(2, FULL.storedAt()),
                                                                                            new RetentionAction.DeleteCheckout("bigboy", JOB));
        }

        @Test
        void plan_after90d_deletesSmallEvidenceAndRows() {
            assertThat(plan(finished(Duration.ofDays(90), false))).containsExactlyInAnyOrder(new RetentionAction.DeleteEvidence(1, SMALL.storedAt()),
                                                                                            new RetentionAction.DeleteEvidence(2, FULL.storedAt()),
                                                                                            new RetentionAction.DeleteTestcases(JOB),
                                                                                            new RetentionAction.DeleteCheckout("bigboy", JOB));
        }

        @Test
        void plan_doesNotRepeatDeletedRows() {
            var rowsGone = JobRetention.jobRetention(JOB, false, true, Option.some(NOW.minus(Duration.ofDays(120))), List.of(), List.of(), false);

            assertThat(plan(rowsGone)).isEmpty();
        }
    }

    @Nested
    class EventsAndDumps {
        @Test
        void plan_eventHorizon_becomesOneAction_regardlessOfKeep() {
            assertThat(RetentionPlan.plan(POLICY, NOW, List.of(finished(Duration.ofDays(400), true)), Option.some(88L), List.of()))
                .containsExactly(new RetentionAction.DeleteEventsUpTo(88));
            assertThat(RetentionPlan.plan(POLICY, NOW, List.of(), Option.some(0L), List.of())).isEmpty();
            assertThat(RetentionPlan.plan(POLICY, NOW, List.of(), Option.none(), List.of())).isEmpty();
        }

        @Test
        void plan_oldDumps_areDeleted_butTheNewestIsAlwaysKept() {
            var oldest = DumpFile.dumpFile("temper-20260801T033000Z.dump", NOW.minus(Duration.ofDays(70)));
            var old = DumpFile.dumpFile("temper-20260901T033000Z.dump", NOW.minus(Duration.ofDays(40)));
            var recent = DumpFile.dumpFile("temper-20261012T033000Z.dump", NOW.minus(Duration.ofDays(1)));

            assertThat(RetentionPlan.plan(POLICY, NOW, List.of(), Option.none(), List.of(oldest, old, recent)))
                .containsExactlyInAnyOrder(new RetentionAction.DeletePgDump(oldest.name()), new RetentionAction.DeletePgDump(old.name()));
            assertThat(RetentionPlan.plan(POLICY, NOW, List.of(), Option.none(), List.of(oldest, old)))
                .containsExactly(new RetentionAction.DeletePgDump(oldest.name()));
        }
    }

    @Test
    void evidenceKind_wireFormsRoundTrip() {
        assertThat(ok(EvidenceKind.evidenceKind("small"))).isEqualTo(EvidenceKind.SMALL);
        assertThat(ok(EvidenceKind.evidenceKind("full"))).isEqualTo(EvidenceKind.FULL);
        assertThat(failure(EvidenceKind.evidenceKind("FULL"))).isNotNull();
    }
}
