package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.UnitRef;


/// Evidence tarballs on the controller host (m1-design §3). The `small` tarball of a unit is recorded by
/// [UnitStore#complete] in the completing transaction; this store records the background `full` pull and serves
/// retention.
public interface EvidenceStore {
    /// Records a tarball of `unit`. At most one live row per unit and kind: a later pull of the same kind replaces
    /// the live row's path, size, sha256 and time (the file was replaced by an atomic rename at the same path).
    /// Recording never changes a result record (it is immutable). An unknown unit fails with
    /// [StoreError.UnitNotFound].
    Promise<Unit> recorded(UnitRef unit, EvidenceKind kind, StoredEvidence evidence);
    /// The row, deleted or not.
    Promise<Option<EvidenceRow>> evidence(long id);
    /// Retention deleted the file of the row it planned, listed with `storedAt`: `deleted_at = now()` (kept when
    /// already set). A row whose `stored_at` is no longer `storedAt` was replaced by a later pull after the plan
    /// (`recorded` keeps the id) and stays live: its file is the new one. When the pull landed before retention's file
    /// delete, the row stays live without a file until the next retention tick lists it again and finishes it (only
    /// while retention keeps running). Marking it anyway would leave a file written after the delete on disk under a
    /// deleted row, which no later plan collects. The row stays; an
    /// unknown id fails with [StoreError.EvidenceNotFound].
    Promise<Unit> deleted(long id, Instant storedAt);
}
