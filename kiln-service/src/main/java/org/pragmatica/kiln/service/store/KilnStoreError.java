package org.pragmatica.kiln.service.store;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// kiln-pg failures, copied from temper-store's `StoreError` (kiln-f1-design §1). Every failure reported by Postgres or
/// the driver is a [Database] carrying the driver's cause. The migration failures name kiln's own way out: every kiln
/// table is a cache (§2), so dropping the database is safe, except for the work ledger (`work_item`, `work_event`, V5),
/// which nothing rebuilds: back it up first with a data-only `pg_dump --data-only -t work_item -t work_event`, and load
/// it with `psql` once a fresh kiln has applied V5 and before it records any event (docs/kiln-f1-design.md §2).
public sealed interface KilnStoreError extends Cause {
    /// A statement failed in Postgres or in the driver; retry classification follows the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Database(Cause origin, String message) implements KilnStoreError, Cause.Wrapped {
        public static final Fn1<Database, Cause> FACTORY = Causes.forOneValue("Database operation failed", Database::new);

        @Override
        public boolean isTerminal() {
            return origin.isTerminal();
        }

        @Override
        public boolean isTransient() {
            return origin.isTransient();
        }
    }

    /// A stored row that no longer parses into its type.
    @SuppressWarnings("JBCT-CAUSE-04")
    record CorruptRow(String table, Cause origin, String message) implements KilnStoreError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<CorruptRow, String, Cause> FACTORY = Causes.forTwoValues("Unreadable %s row",
                                                                                         CorruptRow::new);
    }

    /// A `since` or `feed` page limit below 1: an empty page would hand back its own cursor, and a caller looping on it would
    /// stall (lead ruling, WP5 verification).
    /// The change feed's high-water file could not be read or written (CI interface T1 restore detection).
    @SuppressWarnings("JBCT-CAUSE-04")
    record HighWaterFile(String file, Cause origin, String message) implements KilnStoreError, Cause.Wrapped {
        public static final Fn2<HighWaterFile, String, Cause> FACTORY = Causes.forTwoValues("Cannot use the change high-water file %s",
                                                                                            HighWaterFile::new);
    }

    record InvalidLimit(Integer limit, String message) implements KilnStoreError, Cause.Terminal {
        public static final Fn1<InvalidLimit, Integer> FACTORY = Causes.forOneValue("A page limit must be at least 1, got %d",
                                                                                    InvalidLimit::new);
    }

    /// A column that is NULL where its row requires a value; the origin of a [CorruptRow].
    record NullColumn(String column, String message) implements KilnStoreError, Cause.Terminal {
        public static final Fn1<NullColumn, String> FACTORY = Causes.forOneValue("Column %s is NULL", NullColumn::new);
    }

    /// A stored kind, state or name no constant matches; the origin of a [CorruptRow].
    record UnknownValue(String value, String message) implements KilnStoreError, Cause.Terminal {
        public static final Fn1<UnknownValue, String> FACTORY = Causes.forOneValue("Unknown stored value '%s'",
                                                                                   UnknownValue::new);
    }

    /// A listed migration resource that is not on the classpath.
    record MigrationMissing(String resource, String message) implements KilnStoreError, Cause.Terminal {
        public static final Fn1<MigrationMissing, String> FACTORY = Causes.forOneValue("Migration %s is not on the classpath",
                                                                                       MigrationMissing::new);
    }

    /// A listed migration resource that cannot be read or hashed.
    @SuppressWarnings("JBCT-CAUSE-04")
    record MigrationUnreadable(String resource, Cause origin, String message) implements KilnStoreError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<MigrationUnreadable, String, Cause> FACTORY = Causes.forTwoValues("Cannot read migration %s",
                                                                                                  MigrationUnreadable::new);
    }

    /// An applied migration whose file changed since.
    record MigrationTampered(int version, String message) implements KilnStoreError, Cause.Terminal {
        public static final Fn1<MigrationTampered, Integer> FACTORY = Causes.forOneValue("Migration V%d changed after it was applied",
                                                                                         MigrationTampered::new);
    }

    /// The build's migration list is not V1, V2, … without a hole.
    record MigrationGap(int expected, int position, int found, String message) implements KilnStoreError, Cause.Terminal {
        public static final Fn3<MigrationGap, Integer, Integer, Integer> FACTORY = Causes.forThreeValues("Migration list: expected V%d at position %d, found V%d; the build's migration list is not contiguous; fix the build",
                                                                                                         MigrationGap::new);
    }

    /// The database carries a version this build lacks.
    record MigrationNotInBuild(int version, String message) implements KilnStoreError, Cause.Terminal {
        public static final Fn1<MigrationNotInBuild, Integer> FACTORY = Causes.forOneValue("Migration V%d is applied but not in this build; deploy a build containing it, or drop the kiln database (every table is a cache but the work ledger: first pg_dump --data-only -t work_item -t work_event, and load it after V5 reapplies)",
                                                                                           MigrationNotInBuild::new);
    }

    /// A pending version below the highest applied one.
    record MigrationOutOfOrder(int version, int highestApplied, String message) implements KilnStoreError, Cause.Terminal {
        public static final Fn2<MigrationOutOfOrder, Integer, Integer> FACTORY = Causes.forTwoValues("Migration V%d is pending but V%d is already applied; deploy a build whose migrations match the database, or drop the kiln database (every table is a cache but the work ledger: first pg_dump --data-only -t work_item -t work_event, and load it after V5 reapplies)",
                                                                                                     MigrationOutOfOrder::new);
    }
}
