package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.controller.evidence.EvidenceLayout;
import org.pragmatica.temper.controller.evidence.ResultFiles;
import org.pragmatica.temper.controller.fake.FakeRetention;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.controller.retention.RetentionGc;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.retention.JobRetention;
import org.pragmatica.temper.domain.retention.RetentionPolicy;
import org.pragmatica.temper.protocol.message.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// Host checkout GC (m1-design §4.2, §4.3): one `GcJobs` per UP host, `GcDone.deleted` recorded with `checkoutGone`,
/// `kept` left for the next tick; a DOWN host is not asked.
class RetentionCheckoutTest {
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private final FakeRetention retention = new FakeRetention();
    private LinkHarness harness;

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    private RetentionGc gc() {
        var layout = EvidenceLayout.evidenceLayout(harness.evidenceRoot);

        return RetentionGc.retentionGc(retention,
                                       harness.evidence,
                                       HostLinks.hostLinks(List.of(harness.link), harness.jobs),
                                       ResultFiles.resultFiles(layout, harness.results, () -> NOW),
                                       layout,
                                       RetentionPolicy.DEFAULT,
                                       () -> NOW);
    }

    private void jobWithCheckout(long id) {
        retention.jobs.add(JobRetention.jobRetention(JobId.jobId(id).unwrap(), false, true, some(NOW.minus(Duration.ofDays(2))),
                                                     List.of(), List.of(LinkHarness.HOST.value()), false));
    }

    @Test
    void run_asksTheUpHost_andRecordsWhatItDeleted() throws Exception {
        harness = new LinkHarness();
        harness.responder = request -> switch (request) {
            case Message.GcJobs gc -> List.of(new Message.GcDone(List.of(31L), List.of(new Message.GcDone.Kept(32, "unit main has no rc"))));
            default -> LinkHarness.ackEverything(request);
        };
        harness.link.start();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "link UP");
        jobWithCheckout(31);
        jobWithCheckout(32);

        var report = gc().run().await().unwrap();

        assertThat(harness.lastWorker.expect(Message.GcJobs.class, 5).jobs()).containsExactly(31L, 32L);
        assertThat(retention.checkoutsGone).containsExactly("bb/31");
        assertThat(report.failed()).isZero();
    }

    @Test
    void run_skipsADownHost() throws Exception {
        harness = new LinkHarness();
        jobWithCheckout(33);

        var report = gc().run().await().unwrap();

        assertThat(retention.checkoutsGone).isEmpty();
        assertThat(report.failed()).isZero();
    }
}
