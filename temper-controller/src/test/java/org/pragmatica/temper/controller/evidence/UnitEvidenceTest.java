package org.pragmatica.temper.controller.evidence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.StoredEvidence;

import static org.assertj.core.api.Assertions.assertThat;

/// The controller's disk guard (m1-design §3.2, R13): below `[evidence] minFreeGiB` the full pull is skipped, the
/// small pull never is.
class UnitEvidenceTest {
    private static final UnitRef REF = UnitRef.unitRef(JobId.jobId(3).unwrap(), UnitName.MAIN);
    private static final ControllerConfig.Host HOST = ControllerConfig.Host.host(HostName.hostName("bb").unwrap(), "localhost",
                                                                                 "builder", Path.of("/k"), Path.of("/kh"), 5,
                                                                                 20480, false);
    @TempDir
    Path root;
    private final List<EvidenceKind> pulled = new CopyOnWriteArrayList<>();
    private final FakeStores.Evidence store = new FakeStores.Evidence();

    private UnitEvidence evidence(long minFreeBytes) {
        EvidencePuller puller = (host, ref, attempt, kind) -> {
            pulled.add(kind);
            return Promise.success(StoredEvidence.storedEvidence(root.resolve(kind.wire()), 1, "ab"));
        };
        return UnitEvidence.unitEvidence(puller, store,
                                         ResultFiles.resultFiles(EvidenceLayout.evidenceLayout(root), new FakeStores.Results(),
                                                                 Instant::now),
                                         root, minFreeBytes);
    }

    @Test
    void full_isSkipped_belowTheFreeSpaceFloor_whileSmallIsStillPulled() throws Exception {
        var guarded = evidence(Long.MAX_VALUE);

        assertThat(guarded.small(HOST, REF, 1).await().unwrap().isPresent()).isTrue();
        guarded.full(HOST, REF, 1);
        Thread.sleep(200);

        assertThat(pulled).containsExactly(EvidenceKind.SMALL);
        assertThat(store.recorded).isEmpty();
    }

    @Test
    void full_isPulledAndRecorded_aboveTheFloor() throws Exception {
        evidence(0).full(HOST, REF, 1);
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (store.recorded.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(pulled).containsExactly(EvidenceKind.FULL);
        assertThat(store.recorded).extracting(FakeStores.Evidence.Recorded::kind).containsExactly(EvidenceKind.FULL);
    }
}
