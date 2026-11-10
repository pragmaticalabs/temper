package org.pragmatica.temper.store.api;

import java.nio.file.Path;
import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.UnitRef;


/// An `evidence` row: one tarball of one unit, and when retention marked it deleted.
public record EvidenceRow(long id,
                          UnitRef unit,
                          EvidenceKind kind,
                          Path path,
                          long bytes,
                          String sha256,
                          Instant storedAt,
                          Option<Instant> deletedAt) {
    public static EvidenceRow evidenceRow(long id,
                                          UnitRef unit,
                                          EvidenceKind kind,
                                          Path path,
                                          long bytes,
                                          String sha256,
                                          Instant storedAt,
                                          Option<Instant> deletedAt) {
        return new EvidenceRow(id, unit, kind, path, bytes, sha256, storedAt, deletedAt);
    }
}
