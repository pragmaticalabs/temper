-- temper off-queue reactors: the `off-queue-reactor` and `lingering-reactor` alarm kinds (a maven launcher on a host the queue did
-- not start, and the same one still running at the lingering limit), and the off-queue launchers of the latest listing, which the
-- fleet/alarms reply checks a mark against. Applied once by MigrationRunner; never edit after release.
-- V19 replaced the inline kind CHECK; it is replaced again with the two new kinds.
ALTER TABLE bb_snapshot ADD COLUMN offq jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE bb_alarm DROP CONSTRAINT bb_alarm_kind_check;
ALTER TABLE bb_alarm ADD CONSTRAINT bb_alarm_kind_check
    CHECK (kind IN ('queue-wait','portgroup-held','portgroup-long','waiter-gone','clock-skew','host-retiring','off-queue-reactor','lingering-reactor'));
