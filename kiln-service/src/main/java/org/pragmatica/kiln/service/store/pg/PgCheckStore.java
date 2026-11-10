package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.PrSnapshot;
import org.pragmatica.kiln.service.store.CheckStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Transaction;


/// [CheckStore] over `required_checks` and `check_cell`. [#cells] reads the PR's stored cells, replaces them with the
/// head's and inserts the `CHECK_CELL` changes in one transaction, so a change commits or rolls back with its value.
record PgCheckStore(Db db, String repo, ChangeDiff diff, Watermark committed, HighWater feedMark) implements CheckStore {
    private static final String REQUIRED = "INSERT INTO required_checks (base_ref, kind, enforcement, why, status, stacked, checks, observed_at) "
                                         + "VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8) ON CONFLICT (base_ref) DO UPDATE SET "
                                         + "kind = EXCLUDED.kind, enforcement = EXCLUDED.enforcement, why = EXCLUDED.why, "
                                         + "status = EXCLUDED.status, stacked = EXCLUDED.stacked, checks = EXCLUDED.checks, "
                                         + "observed_at = EXCLUDED.observed_at";

    private static final String STORED_CELLS = "SELECT context, app_id, value FROM check_cell WHERE pr = $1 FOR UPDATE";

    private static final String INSERT_CELL = "INSERT INTO check_cell (pr, context, head_sha, app_id, value, foreign_runs, shown, observed_at) "
                                            + "VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8)";

    @Override
    public Promise<Unit> required(BranchName base, RequiredChecks list, Instant observedAt) {
        var stored = CheckRows.stored(list);

        return Json.write(stored.checks())
                   .async()
                   .flatMap(checks -> Sql.execute(db.executor(),
                                                  REQUIRED,
                                                  base.value(),
                                                  stored.kind(),
                                                  Sql.nullable(stored.enforcement()),
                                                  Sql.nullable(stored.why()),
                                                  Sql.nullable(stored.status()),
                                                  stored.stacked(),
                                                  checks,
                                                  Sql.utc(observedAt)));
    }

    @Override
    public Promise<List<Change>> cells(PrNumber pr, Sha head, List<Cell> cells, Instant observedAt) {
        return committed.tracked(recordedAt -> db.inTransaction(tx -> cellsIn(tx,
                                                                              pr,
                                                                              head,
                                                                              cells,
                                                                              observedAt,
                                                                              recordedAt)))
                        .flatMap(changes -> feedMark.after(db, changes));
    }

    private Promise<List<Change>> cellsIn(Transaction tx,
                                          PrNumber pr,
                                          Sha head,
                                          List<Cell> cells,
                                          Instant observedAt,
                                          Instant recordedAt) {
        return stored(tx, pr).map(before -> diff.between(snapshot(pr, before),
                                                         snapshot(pr,
                                                                  after(cells)),
                                                         observedAt))
                     .map(changes -> PgChangeStore.recorded(changes, recordedAt))
                     .flatMap(changes -> replace(tx, pr, head, cells, observedAt, changes));
    }

    private Promise<List<Change>> replace(Transaction tx,
                                          PrNumber pr,
                                          Sha head,
                                          List<Cell> cells,
                                          Instant observedAt,
                                          List<Change> changes) {
        return Sql.execute(tx,
                           "DELETE FROM check_cell WHERE pr = $1",
                           pr.value())
                  .flatMap(_ -> Sql.sequence(cells,
                                             cell -> insert(tx, pr, head, cell, observedAt)))
                  .flatMap(_ -> PgChangeStore.insert(tx, feedMark, repo, changes));
    }

    private static Promise<Unit> insert(Transaction tx, PrNumber pr, Sha head, Cell cell, Instant observedAt) {
        return Json.write(cell.shown())
                   .async()
                   .flatMap(shown -> insertRow(tx, pr, head, cell, shown, observedAt));
    }

    private static Promise<Unit> insertRow(Transaction tx,
                                           PrNumber pr,
                                           Sha head,
                                           Cell cell,
                                           String shown,
                                           Instant observedAt) {
        var appId = cell.check().appId().map(AppId::value);

        return Sql.execute(tx,
                           INSERT_CELL,
                           pr.value(),
                           cell.check().context().value(),
                           head.value(),
                           Sql.nullable(appId),
                           cell.value(),
                           cell.foreign(),
                           shown,
                           Sql.utc(observedAt));
    }

    /// The PR's stored cell values by [RequiredCheck#key], the key the change rule and the new cells use.
    private static Promise<Map<String, String>> stored(Transaction tx, PrNumber pr) {
        return Sql.query(tx,
                         STORED_CELLS,
                         pr.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgCheckStore::keyed))
                  .map(PgCheckStore::values);
    }

    private static Result<Entry<String, String>> keyed(PgRow row) {
        return CheckRows.requiredCheck(row)
                        .map(check -> Map.entry(check.key(),
                                                row.getString("value")))
                        .mapError(Rows.corrupt(CheckRows.CELL_TABLE));
    }

    private static Map<String, String> values(List<Entry<String, String>> keyed) {
        return keyed.stream()
                    .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

    private static Map<String, String> after(List<Cell> cells) {
        return cells.stream()
                    .collect(Collectors.toMap(cell -> cell.check()
                                                          .key(),
                                              Cell::value,
                                              (_, last) -> last));
    }

    private static PrSnapshot snapshot(PrNumber pr, Map<String, String> cells) {
        return new PrSnapshot(Map.of(), Map.of(pr, cells), Map.of());
    }
}
