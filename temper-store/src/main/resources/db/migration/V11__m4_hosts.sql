-- temper M4: multi-host (m4-design §6). Applied once by MigrationRunner on top of every earlier version (the number is
-- fixed at merge); never edit after release. Written whole in WP-1 (m4-design §13): later work packages only use its
-- columns. What each change does to existing rows (R-12) is stated with it.

-- hosts: LOST, retirement, CPU class (§2, §9, §10). Existing rows: state unchanged, shared_cpu false (bigboy is
-- dedicated), retire_at null, lost_* null. lost_at is set exactly while LOST: recovery clears it (§2.5 step 5).
ALTER TABLE hosts DROP CONSTRAINT hosts_state_check;
ALTER TABLE hosts ADD CONSTRAINT hosts_state_check CHECK (state IN ('DOWN','CONNECTING','UP','LOST'));
ALTER TABLE hosts
  ADD COLUMN shared_cpu boolean NOT NULL DEFAULT false,
  ADD COLUMN retire_at timestamptz,
  ADD COLUMN lost_at timestamptz,
  ADD COLUMN lost_reason text CHECK (lost_reason IN ('link-down','host-key')),
  ADD CONSTRAINT hosts_lost_pair CHECK ((lost_at IS NULL) = (lost_reason IS NULL)),
  ADD CONSTRAINT hosts_lost_state CHECK ((state = 'LOST') = (lost_at IS NOT NULL));

-- units: attempt fencing (§4), requeue bound (§2.3, R-M4-13: one requeue), side effects (§5), CPU class of the
-- current attempt (§9.4). Existing rows: attempt 1 for a unit ever granted, else 0; no requeues; side_effects
-- 'external' (fail-safe: an active pre-M4 unit is never requeued); shared_cpu false where granted (bigboy was the
-- only host), else null. "Ever granted" is granted_at or started_at set, or a unit_facts row [G]: only the requeue of a
-- unit that never started clears granted_at, and it leaves the unit QUEUED, so every active unit is caught; a unit
-- requeued after HOLDING keeps its hold facts (attempt 1 below), so it is attempt 1 too and its next grant is 2.
ALTER TABLE units
  ADD COLUMN attempt int NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  ADD COLUMN host_lost_requeues int NOT NULL DEFAULT 0 CHECK (host_lost_requeues BETWEEN 0 AND 1),
  ADD COLUMN side_effects text NOT NULL DEFAULT 'external' CHECK (side_effects IN ('none','external')),
  ADD COLUMN shared_cpu boolean;
UPDATE units SET attempt = 1, shared_cpu = false
 WHERE granted_at IS NOT NULL OR started_at IS NOT NULL OR EXISTS (SELECT 1 FROM unit_facts f WHERE f.unit_id = units.id);
ALTER TABLE units ALTER COLUMN side_effects DROP DEFAULT;

-- The lost attempts (§2.3, §2.4), write-once. Retention deletes no units row, so it deletes none of these either.
CREATE TABLE unit_attempts (
  unit_id bigint NOT NULL REFERENCES units(id), attempt int NOT NULL CHECK (attempt >= 1),
  host text NOT NULL REFERENCES hosts(name), granted_at timestamptz NOT NULL, started_at timestamptz,
  ended_at timestamptz NOT NULL, shared_cpu boolean NOT NULL,
  outcome text NOT NULL CHECK (outcome IN ('host-lost')),
  PRIMARY KEY (unit_id, attempt));
CREATE TRIGGER unit_attempts_write_once BEFORE UPDATE OR DELETE ON unit_attempts
  FOR EACH ROW EXECUTE FUNCTION temper_write_once();

-- unit_facts per attempt (F4). Existing rows belong to attempt 1; no default after V11, so an INSERT that forgets the
-- attempt fails at once.
ALTER TABLE unit_facts ADD COLUMN attempt int NOT NULL DEFAULT 1;
ALTER TABLE unit_facts ALTER COLUMN attempt DROP DEFAULT;
ALTER TABLE unit_facts DROP CONSTRAINT unit_facts_pkey;
ALTER TABLE unit_facts ADD PRIMARY KEY (unit_id, attempt, phase);

-- jobs: the dedicated-CPU request (§9.2). Existing rows: false.
ALTER TABLE jobs ADD COLUMN dedicated_cpu boolean NOT NULL DEFAULT false;

-- events: the unit-lost kind (§2.3). The list is V4's, the last migration that redefined it, plus 'unit-lost'.
-- Existing rows: unchanged.
ALTER TABLE events DROP CONSTRAINT events_kind_check;
ALTER TABLE events ADD CONSTRAINT events_kind_check CHECK (kind IN ('queued','started','start-unknown','unit-finished',
                                                                    'finished','cancelled','superseded','unit-lost'));
