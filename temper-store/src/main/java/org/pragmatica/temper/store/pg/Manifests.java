package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.manifest.ManifestOutcome;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.verdict.Manifest;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A unit's manifest rows (`manifests`, `manifest_units`, m1-design §2.5) and a job's manifest read back. Each runs on
/// the executor it is given (the completing transaction, or a read snapshot).
sealed interface Manifests {
    String COMPUTED = "computed";
    String UNSUPPORTED = "unsupported";

    /// The rows are write-once per unit: a repeated delivery of the same unit's manifest changes nothing. Each row is
    /// required or `optional` (ruling R-2).
    String INSERT_ENTRIES = "INSERT INTO manifests (job_id, unit_id, module, class, runner, optional) "
                          + "SELECT $1, $2, e.module, e.class, e.runner, e.optional::boolean "
                          + "FROM unnest($3::text[], $4::text[], $5::text[], $6::text[]) "
                          + "AS e(module, class, runner, optional) ON CONFLICT DO NOTHING";

    /// `entry_count` is how many `manifests` rows the first delivery wrote (ruling R-7): retention deletes those rows
    /// and keeps this one, so fewer rows than `entry_count` reads as pruned.
    String INSERT_UNIT = "INSERT INTO manifest_units (unit_id, status, detail, not_reached, unresolved, entry_count) "
                       + "VALUES ($1, $2, $3, $4::text[], $5::text[], $6) ON CONFLICT (unit_id) DO NOTHING";

    /// Whether any unit has a spec; whether every unit with a spec delivered a computed manifest (a unit with a spec
    /// and no `manifest_units` row counts as not computed); whether a unit's `manifests` rows are fewer than it wrote
    /// (pruned by retention, ruling R-7); the distinct modules not reached, sorted; the number of distinct unresolved
    /// annotation types.
    String STATUS = "SELECT coalesce(bool_or(u.manifest_spec IS NOT NULL), false) AS any_spec, "
                  + "coalesce(bool_and(u.manifest_spec IS NULL OR coalesce(m.status = 'computed', false)), true) AS computed, "
                  + "coalesce(bool_or(m.entry_count > (SELECT count(*) FROM manifests x WHERE x.unit_id = u.id)), false) AS pruned, "
                  + "(SELECT coalesce(json_agg(DISTINCT r ORDER BY r), '[]'::json)::text FROM manifest_units mu "
                  + "JOIN units uu ON uu.id = mu.unit_id, unnest(mu.not_reached) r WHERE uu.job_id = $1) AS not_reached, "
                  + "(SELECT count(DISTINCT a) FROM manifest_units mu JOIN units uu ON uu.id = mu.unit_id, "
                  + "unnest(mu.unresolved) a WHERE uu.job_id = $1) AS unresolved "
                  + "FROM units u LEFT JOIN manifest_units m ON m.unit_id = u.id WHERE u.job_id = $1";

    static Promise<Unit> write(QueryExecutor tx, JobId job, long unitId, ManifestOutcome outcome) {
        return switch (outcome) {
            case ManifestOutcome.Computed computed -> insertEntries(tx, job, unitId, computed).flatMap(written -> insertUnit(tx,
                                                                                                                             unitId,
                                                                                                                             COMPUTED,
                                                                                                                             none(),
                                                                                                                             computed.notReached(),
                                                                                                                             computed.unresolvedAnnotations(),
                                                                                                                             written));
            case ManifestOutcome.Unsupported unsupported -> insertUnit(tx,
                                                                       unitId,
                                                                       UNSUPPORTED,
                                                                       some(unsupported.reason()),
                                                                       List.of(),
                                                                       Set.of(),
                                                                       0);
        };
    }

    /// The job's manifest; none when no unit of the job has a manifest spec.
    static Promise<Option<JobManifest>> load(QueryExecutor executor, JobId job) {
        return Sql.query(executor,
                         STATUS,
                         job.value())
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .flatMap(row -> withEntries(executor, job, row));
    }

    /// The verdict's manifest: the required and optional class names of every unit, only when the job's manifest is
    /// computed.
    static Option<Manifest> forVerdict(Option<JobManifest> manifest) {
        return manifest.filter(JobManifest::computed)
                       .map(computed -> Manifest.manifest(classNames(computed.entries()),
                                                          classNames(computed.optional())));
    }

    private static Set<String> classNames(List<ManifestEntry> entries) {
        return entries.stream()
                      .map(ManifestEntry::className)
                      .collect(Collectors.toUnmodifiableSet());
    }

    /// One `manifests` row: an entry and whether it is optional.
    record StoredEntry(ManifestEntry entry, boolean optional) {}

    /// How many rows this delivery wrote: all of them the first time, none on a repeat.
    private static Promise<Integer> insertEntries(QueryExecutor tx,
                                                  JobId job,
                                                  long unitId,
                                                  ManifestOutcome.Computed computed) {
        var rows = Stream.concat(computed.entries().stream().map(entry -> new StoredEntry(entry, false)),
                                 computed.optional().stream().map(entry -> new StoredEntry(entry, true)))
                         .toList();
        var wire = rows.stream().map(row -> ManifestEntryDto.manifestEntryDto(row.entry())).toList();

        return Sql.update(tx,
                          INSERT_ENTRIES,
                          job.value(),
                          unitId,
                          Sql.texts(wire.stream().map(ManifestEntryDto::module)),
                          Sql.texts(wire.stream().map(ManifestEntryDto::className)),
                          Sql.texts(wire.stream().map(ManifestEntryDto::runner)),
                          Sql.texts(rows.stream().map(row -> String.valueOf(row.optional()))));
    }

    private static Promise<Unit> insertUnit(QueryExecutor tx,
                                            long unitId,
                                            String status,
                                            Option<String> detail,
                                            List<String> notReached,
                                            Set<String> unresolved,
                                            int entryCount) {
        return Sql.execute(tx,
                           INSERT_UNIT,
                           unitId,
                           status,
                           Sql.nullable(detail),
                           Sql.texts(notReached.stream()),
                           Sql.texts(unresolved.stream().sorted()),
                           entryCount);
    }

    private static Promise<Option<JobManifest>> withEntries(QueryExecutor executor, JobId job, PgRow status) {
        return status.getBoolean("any_spec")
               ? entries(executor, job).flatMap(entries -> jobManifest(status, entries).async())
                        .map(Option::some)
               : Promise.success(none());
    }

    private static Promise<List<StoredEntry>> entries(QueryExecutor executor, JobId job) {
        return Sql.query(executor,
                         "SELECT module, class, runner, optional FROM manifests WHERE job_id = $1 ORDER BY module, class, runner",
                         job.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, Manifests::entry));
    }

    private static Result<StoredEntry> entry(PgRow row) {
        return new ManifestEntryDto(row.getString("module"), row.getString("class"), row.getString("runner")).toDomain()
                                                                                                             .map(entry -> new StoredEntry(entry,
                                                                                                                                           row.getBoolean("optional")))
                                                                                                             .mapError(Rows.corrupt("manifests"));
    }

    private static Result<JobManifest> jobManifest(PgRow status, List<StoredEntry> entries) {
        return Json.read(status.getString("not_reached"),
                         Json.STRINGS)
                   .map(notReached -> JobManifest.jobManifest(status.getBoolean("computed"),
                                                              status.getBoolean("pruned"),
                                                              ofKind(entries, false),
                                                              ofKind(entries, true),
                                                              notReached,
                                                              Math.toIntExact(status.getLong("unresolved"))))
                   .mapError(Rows.corrupt("manifest_units"));
    }

    private static List<ManifestEntry> ofKind(List<StoredEntry> entries, boolean optional) {
        return entries.stream()
                      .filter(stored -> stored.optional() == optional)
                      .map(StoredEntry::entry)
                      .toList();
    }

    record unused() implements Manifests {}
}
