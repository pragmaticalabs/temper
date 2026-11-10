package org.pragmatica.temper.store.pg;

import java.nio.file.Path;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.store.api.StoredEvidence;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.option;


/// `evidence` rows (m1-design §3.2) written in a caller's transaction, and the job's evidence summary the result
/// record is built from.
sealed interface EvidenceRows {
    /// At most one live row per unit and kind (`evidence_live`): a later pull replaces the live row's file facts.
    String UPSERT = "INSERT INTO evidence (job_id, unit_id, kind, path, bytes, sha256) VALUES ($1, $2, $3, $4, $5, $6) "
                  + "ON CONFLICT (unit_id, kind) WHERE deleted_at IS NULL DO UPDATE SET path = EXCLUDED.path, "
                  + "bytes = EXCLUDED.bytes, sha256 = EXCLUDED.sha256, stored_at = now()";

    /// Units with a marker, how many of them have a live small tarball, the small tarballs' total size, and one of
    /// their paths (all lie under the same job directory).
    String SUMMARY = "SELECT count(*) FILTER (WHERE u.marker_epoch_ns IS NOT NULL) AS marked, "
                   + "count(e.id) FILTER (WHERE u.marker_epoch_ns IS NOT NULL) AS stored, "
                   + "coalesce(sum(e.bytes), 0)::bigint AS bytes, min(e.path) AS path "
                   + "FROM units u LEFT JOIN evidence e ON e.unit_id = u.id AND e.kind = 'small' AND e.deleted_at IS NULL "
                   + "WHERE u.job_id = $1";

    /// The job's small evidence at finish: `marked` units ran far enough to leave a directory, `stored` of them have
    /// their small tarball, `bytes` is the tarballs' total, `tarball` any one of their paths.
    record Summary(long marked, long stored, long bytes, Option<Path> tarball) {}

    static Promise<Unit> upsert(QueryExecutor tx, JobId job, long unitId, EvidenceKind kind, StoredEvidence evidence) {
        return Sql.execute(tx,
                           UPSERT,
                           job.value(),
                           unitId,
                           kind.wire(),
                           evidence.path().toString(),
                           evidence.bytes(),
                           evidence.sha256());
    }

    static Promise<Summary> summary(QueryExecutor executor, JobId job) {
        return Sql.query(executor,
                         SUMMARY,
                         job.value())
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .map(EvidenceRows::summaryOf);
    }

    private static Summary summaryOf(PgRow row) {
        return new Summary(row.getLong("marked"),
                           row.getLong("stored"),
                           row.getLong("bytes"),
                           option(row.getString("path")).map(Path::of));
    }

    record unused() implements EvidenceRows {}
}
