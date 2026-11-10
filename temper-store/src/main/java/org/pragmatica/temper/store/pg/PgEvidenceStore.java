package org.pragmatica.temper.store.pg;

import java.nio.file.Path;
import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.store.api.EvidenceRow;
import org.pragmatica.temper.store.api.EvidenceStore;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.StoredEvidence;

import static org.pragmatica.lang.Option.option;


/// [EvidenceStore] over Postgres.
record PgEvidenceStore(Db db) implements EvidenceStore {
    /// [EvidenceRows#UPSERT] keyed by the unit's name: an unknown unit inserts nothing and returns no row.
    private static final String RECORD = "INSERT INTO evidence (job_id, unit_id, kind, path, bytes, sha256) "
                                       + "SELECT u.job_id, u.id, $3, $4, $5, $6 FROM units u WHERE u.job_id = $1 AND u.name = $2 "
                                       + "ON CONFLICT (unit_id, kind) WHERE deleted_at IS NULL DO UPDATE SET path = EXCLUDED.path, "
                                       + "bytes = EXCLUDED.bytes, sha256 = EXCLUDED.sha256, stored_at = now() RETURNING id";

    /// Marks the row only while `stored_at` is the one retention planned: the `stored_at` condition sits on the
    /// updated row itself, so READ COMMITTED re-checks it against a pull that committed meanwhile.
    private static final String MARK_DELETED = "WITH marked AS (UPDATE evidence SET deleted_at = coalesce(deleted_at, now()) "
                                             + "WHERE id = $1 AND stored_at = $2 RETURNING id) "
                                             + "SELECT EXISTS (SELECT 1 FROM evidence WHERE id = $1) AS found";

    private static final String SELECT = "SELECT e.id, e.job_id, u.name, e.kind, e.path, e.bytes, e.sha256, e.stored_at, e.deleted_at "
                                       + "FROM evidence e JOIN units u ON u.id = e.unit_id WHERE e.id = $1";

    @Override
    public Promise<Unit> recorded(UnitRef unit, EvidenceKind kind, StoredEvidence evidence) {
        return Sql.query(db.executor(),
                         RECORD,
                         unit.job().value(),
                         unit.unit().value(),
                         kind.wire(),
                         evidence.path().toString(),
                         evidence.bytes(),
                         evidence.sha256())
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.UnitNotFound.FACTORY.apply(unit.job(),
                                                                                                         unit.unit())))
                  .mapToUnit();
    }

    @Override
    public Promise<Option<EvidenceRow>> evidence(long id) {
        return Sql.query(db.executor(),
                         SELECT,
                         id)
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgEvidenceStore::evidenceRow));
    }

    @Override
    public Promise<Unit> deleted(long id, Instant storedAt) {
        return Sql.query(db.executor(),
                         MARK_DELETED,
                         id,
                         Sql.utc(storedAt))
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .flatMap(row -> found(id,
                                        row.getBoolean("found")));
    }

    private static Promise<Unit> found(long id, boolean found) {
        return found
               ? Promise.unitPromise()
               : StoreError.EvidenceNotFound.FACTORY.apply(id).promise();
    }

    private static Result<EvidenceRow> evidenceRow(PgRow row) {
        return Result.all(Rows.jobId(row, "job_id"),
                          UnitName.unitName(row.getString("name")),
                          EvidenceKind.evidenceKind(row.getString("kind")))
                     .map((job, name, kind) -> EvidenceRow.evidenceRow(row.getLong("id"),
                                                                       UnitRef.unitRef(job, name),
                                                                       kind,
                                                                       Path.of(row.getString("path")),
                                                                       row.getLong("bytes"),
                                                                       row.getString("sha256"),
                                                                       row.getInstant("stored_at"),
                                                                       option(row.getInstant("deleted_at"))))
                     .mapError(Rows.corrupt("evidence"));
    }
}
