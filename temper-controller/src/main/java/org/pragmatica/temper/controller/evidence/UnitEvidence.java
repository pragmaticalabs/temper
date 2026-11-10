package org.pragmatica.temper.controller.evidence;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.EvidenceStore;
import org.pragmatica.temper.store.api.StoredEvidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;


/// What happens to a unit's evidence when it ends (m1-design §3.2, §3.3), for the host link's report path:
///
/// 1. [#small] before `UnitStore.complete`, which records it in the completing transaction;
/// 2. [#finished] after `complete`: when that completion finished the job, its `result.json` is written;
/// 3. [#full] after the report is acked, in the background, recorded with `EvidenceStore.recorded`; skipped while
///    the evidence filesystem has less than `[evidence] minFreeGiB` free (the small pull is always attempted).
public record UnitEvidence(EvidencePuller puller,
                           EvidenceStore store,
                           ResultFiles results,
                           Path root,
                           long minFreeBytes) {
    private static final Logger log = LoggerFactory.getLogger(UnitEvidence.class);

    public static UnitEvidence unitEvidence(EvidencePuller puller,
                                            EvidenceStore store,
                                            ResultFiles results,
                                            Path root,
                                            long minFreeBytes) {
        return new UnitEvidence(puller, store, results, root, minFreeBytes);
    }

    /// FER (degrade forward): a failed small pull is logged and the unit completes without it. Guarantee: the verdict
    /// is unchanged and the job's record says `evidence: {stored: false}` with the annotation `evidence=missing`
    /// (design §6, R3). Mechanism: `complete` receives no evidence row, and the store's record compares the units
    /// with a marker against their live small tarballs.
    public Promise<Option<StoredEvidence>> small(ControllerConfig.Host host, UnitRef ref, int attempt) {
        return puller.pull(host, ref, attempt, EvidenceKind.SMALL)
                     .map(Option::some)
                     .onFailure(cause -> log.warn("[{}] small evidence of {} not pulled: {}",
                                                  host.name(),
                                                  ref.containerName(),
                                                  cause.message()))
                     .recover(_ -> none());
    }

    /// Writes `result.json` when `outcome` finished the job.
    public Unit finished(CompleteOutcome outcome) {
        return switch (outcome) {
            case CompleteOutcome.Completed completed when completed.job().isPresent() -> results.writeLater(completed.ref().job());
            default -> unit();
        };
    }

    /// Fire-and-forget: the full tarball changes no record (the result record is immutable); a failure is logged and
    /// the tarball is simply absent (FER), and retention deletes full tarballs at 14 d anyway.
    public Unit full(ControllerConfig.Host host, UnitRef ref, int attempt) {
        return hasRoom()
               ? pulledFull(host, ref, attempt)
               : skipped(ref);
    }

    private Unit pulledFull(ControllerConfig.Host host, UnitRef ref, int attempt) {
        puller.pull(host, ref, attempt, EvidenceKind.FULL)
              .flatMap(stored -> store.recorded(ref, EvidenceKind.FULL, stored))
              .onFailure(cause -> log.warn("[{}] full evidence of {} not stored: {}",
                                           host.name(),
                                           ref.containerName(),
                                           cause.message()));

        return unit();
    }

    private Unit skipped(UnitRef ref) {
        log.warn("full evidence of {} skipped: less than {} bytes free under {}",
                 ref.containerName(),
                 minFreeBytes,
                 root);

        return unit();
    }

    /// The free space of the evidence filesystem; an unreadable one counts as full (the full pull is optional).
    private boolean hasRoom() {
        return usableBytes().map(free -> free >= minFreeBytes)
                          .or(false);
    }

    private Result<Long> usableBytes() {
        return FileOps.createDirectories(root).flatMap(directory -> Result.lift(Causes::fromThrowable,
                                                                                () -> Files.getFileStore(directory).getUsableSpace()));
    }
}
