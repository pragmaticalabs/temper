package org.pragmatica.temper.domain.retention;

import java.time.Instant;

import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.UnitName;


/// One stored, not yet deleted evidence tarball (an `evidence` row). Its retention clock is the job's finish,
/// not `storedAt`: a tarball pulled late does not live longer.
public record EvidenceItem(long id, UnitName unit, EvidenceKind kind, Instant storedAt) {
    public static EvidenceItem evidenceItem(long id, UnitName unit, EvidenceKind kind, Instant storedAt) {
        return new EvidenceItem(id, unit, kind, storedAt);
    }
}
