-- temper M1: the UNKNOWN state (m1-design §14, ruling R-13 amended). Applied once by MigrationRunner on top of V3;
-- never edit after release.

-- A unit runs but its start could not be recorded: unit and job show UNKNOWN, never QUEUED, until a start or the
-- exit facts arrive. An UNKNOWN unit is active (it holds its slot), and an UNKNOWN job is an active job of its ref.
ALTER TABLE jobs DROP CONSTRAINT jobs_state_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_state_check CHECK (state IN ('REFUSED','QUEUED','UNKNOWN','RUNNING','CANCELLING',
                                                                  'SUPERSEDED','PASSED','FAILED','EMPTY','VOID','ERROR',
                                                                  'CANCELLED'));
ALTER TABLE units DROP CONSTRAINT units_state_check;
ALTER TABLE units ADD CONSTRAINT units_state_check CHECK (state IN ('QUEUED','GRANTED','UNKNOWN','RUNNING','CANCELLING',
                                                                    'PASSED','FAILED','EMPTY','VOID','ERROR','CANCELLED'));
ALTER TABLE events DROP CONSTRAINT events_kind_check;
ALTER TABLE events ADD CONSTRAINT events_kind_check CHECK (kind IN ('queued','started','start-unknown','unit-finished',
                                                                    'finished','cancelled','superseded'));

DROP INDEX jobs_active_ref;
CREATE INDEX jobs_active_ref ON jobs (ref, recipe_kind) WHERE state IN ('QUEUED','UNKNOWN','RUNNING','CANCELLING');
DROP INDEX units_host_active;
CREATE INDEX units_host_active ON units (host) WHERE state IN ('GRANTED','UNKNOWN','RUNNING','CANCELLING');
