-- temper host upkeep: the `portgroup-long` alarm kind (the age backstop of a running group=1 ticket, issue #10).
-- Applied once by MigrationRunner; never edit after release. V17 named the kinds inline, so its CHECK constraint
-- (bb_alarm_kind_check, the name Postgres gives an inline column CHECK) is replaced.
ALTER TABLE bb_alarm DROP CONSTRAINT bb_alarm_kind_check;
ALTER TABLE bb_alarm ADD CONSTRAINT bb_alarm_kind_check
    CHECK (kind IN ('queue-wait','portgroup-held','portgroup-long','waiter-gone','clock-skew','host-retiring'));
