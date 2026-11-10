package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// `required_checks` and `check_cell` rows: the stored form of a [RequiredChecks] list and of a [Cell]; shared by
/// [PgCheckStore] and [PgViewStore].
sealed interface CheckRows {
    String REQUIRED_TABLE = "required_checks";
    String CELL_TABLE = "check_cell";
    String REQUIRED = "REQUIRED";
    String NONE_REQUIRED = "NONE_REQUIRED";
    String UNKNOWN = "UNKNOWN";

    /// A list as its columns: `kind`, `enforcement`, `why`, `status`, `stacked`, `checks`.
    record Stored(String kind,
                  Option<String> enforcement,
                  Option<String> why,
                  Option<String> status,
                  boolean stacked,
                  List<Json.CheckJson> checks) {}

    static Stored stored(RequiredChecks list) {
        return switch (list) {
            case RequiredChecks.Required(var checks, var enforcement) -> new Stored(REQUIRED,
                                                                                    some(enforcement),
                                                                                    none(),
                                                                                    none(),
                                                                                    false,
                                                                                    checks.stream().map(CheckRows::json).toList());
            case RequiredChecks.NoneRequired(var why, var stacked) -> new Stored(NONE_REQUIRED,
                                                                                 none(),
                                                                                 some(why),
                                                                                 none(),
                                                                                 stacked,
                                                                                 List.of());
            case RequiredChecks.Unknown(var status) -> new Stored(UNKNOWN,
                                                                  none(),
                                                                  none(),
                                                                  some(status),
                                                                  false,
                                                                  List.of());
        };
    }

    static Json.CheckJson json(RequiredCheck check) {
        return new Json.CheckJson(check.context().value(),
                                  check.appId().map(AppId::value).toOptional().orElse(null));
    }

    /// The list stored in `row` (columns `kind`, `enforcement`, `why`, `status`, `stacked`, `checks`).
    static Result<RequiredChecks> requiredChecks(PgRow row) {
        return switch (row.getString("kind")) {
            case REQUIRED -> Result.all(Json.read(row.getString("checks"), Json.CHECKS).flatMap(CheckRows::checks),
                                        Rows.required(row, "enforcement")).map(RequiredChecks.Required::new);
            case NONE_REQUIRED -> Rows.required(row, "why").map(why -> new RequiredChecks.NoneRequired(why,
                                                                                                       row.getBoolean("stacked")));
            case UNKNOWN -> Rows.required(row, "status").map(RequiredChecks.Unknown::new);
            case null, default -> Rows.UNKNOWN_VALUE.apply(row.getString("kind")).result();
        };
    }

    private static Result<List<RequiredCheck>> checks(List<Json.CheckJson> stored) {
        return Result.allOf(stored.stream().map(CheckRows::check).toList());
    }

    private static Result<RequiredCheck> check(Json.CheckJson stored) {
        return Result.all(CheckContext.checkContext(stored.context()),
                          appId(option(stored.appId())))
                     .map(RequiredCheck::new);
    }

    private static Result<Option<AppId>> appId(Option<Long> raw) {
        return Rows.flip(raw.map(AppId::appId));
    }

    /// The check a `check_cell` row is keyed by (columns `context`, `app_id`); the caller reports a failure as its row's.
    static Result<RequiredCheck> requiredCheck(PgRow row) {
        return Result.all(CheckContext.checkContext(row.getString("context")),
                          appId(option(row.getLong("app_id"))))
                     .map(RequiredCheck::new);
    }

    /// The cell stored in `row` (columns `context`, `app_id`, `value`, `foreign_runs`, `shown`).
    static Result<Cell> cell(PgRow row) {
        return Result.all(requiredCheck(row),
                          Json.read(row.getString("shown"),
                                    Json.STRINGS))
                     .map((check, shown) -> new Cell(check,
                                                     row.getString("value"),
                                                     row.getInt("foreign_runs"),
                                                     shown))
                     .mapError(Rows.corrupt(CELL_TABLE));
    }

    record unused() implements CheckRows {}
}
