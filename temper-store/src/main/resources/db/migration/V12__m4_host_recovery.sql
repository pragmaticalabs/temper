-- temper M4 WP-4a: the LOST mark recovery last cleared (m4-design §2.5). Applied once by MigrationRunner; never edit
-- after release. V11 is fixed, hence a new version. Existing rows: NULL (no recovery recorded yet).
-- Why a column: a `hosts.lost` write can commit after recovery's UP write (the controller's bound fails the call, not
-- the statement). A row before its first loss and a row after recovery are otherwise the same (not LOST, lost_* NULL),
-- so only the cleared mark lets `lost` refuse a mark at or before it.
ALTER TABLE hosts ADD COLUMN cleared_lost_at timestamptz;
