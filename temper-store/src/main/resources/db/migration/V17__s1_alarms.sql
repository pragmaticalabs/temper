-- temper host upkeep S1-A: the alarm marks. Applied once by MigrationRunner; never edit
-- after release (a later need is V18).
--
-- One row per fired alarm. The primary key is the no-flood identity: an alarm event is emitted only when its key is
-- absent, and the row is inserted in the SAME transaction as the event, so both commit or neither does. The threshold is
-- part of the key, so a changed limit re-arms. subject: the ticket id; '-' for clock-skew; the retireAt ISO instant for
-- host-retiring. threshold: the limit in minutes; 'grace' for waiter-gone; '30' for clock-skew; '3' | '1' (days) for
-- host-retiring. fired_at is the controller clock.
CREATE TABLE bb_alarm (
    host      text        NOT NULL CHECK (host ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    kind      text        NOT NULL CHECK (kind IN ('queue-wait','portgroup-held','waiter-gone','clock-skew','host-retiring')),
    subject   text        NOT NULL,
    threshold text        NOT NULL,
    fired_at  timestamptz NOT NULL,
    PRIMARY KEY (host, kind, subject, threshold)
);

-- The trailer `at` of the stored listing (host clock), NULL for rows written before S1; and the round's measured skew in
-- seconds (positive = host ahead of the controller's read interval).
ALTER TABLE bb_snapshot ADD COLUMN host_at timestamptz;
ALTER TABLE bb_snapshot ADD COLUMN skew_s  integer;
