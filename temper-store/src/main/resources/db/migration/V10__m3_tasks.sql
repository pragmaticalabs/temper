-- temper M3a WP-1: the task store (m3-design §5.3, §6). Applied once by MigrationRunner on top of every earlier version
-- (the number is fixed at merge); never edit after release.
--
-- Every table of §5.3 is created here, the evidence and checklist ones (task_evidence, task_items) included, so WP-2
-- adds no table of its own to this set. Existing rows: jobs.task_id and jobs.task_epoch are NULL, which is true of every
-- job submitted before tasks existed (R-12, no conversion); no other existing table changes.
--
-- tasks is the current-state projection of task_events: every command runs in one transaction (SELECT … FOR UPDATE of
-- the row, TaskMachine, the conditional UPDATE, the event INSERTs), and every event carries the after-image of the
-- projected columns, so folding a task's events gives its row (TaskProjectionIT). Two columns are added to §5.3's list:
-- wait_jobs and wait_ci hold what a WAITING task waits for (§6.6 "records what it waits for"), so the sweeper's resume
-- reads the projection, not the event log [G]. lease_hash is the sha256 of the lease token (64 hex); the token is never
-- stored.
CREATE TABLE tasks (
  id bigserial PRIMARY KEY,
  slug text NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,39}$'),
  title text NOT NULL, issues int[] NOT NULL DEFAULT '{}',
  brief jsonb NOT NULL,
  repo text NOT NULL,
  base_ref text NOT NULL, branch text NOT NULL, pr int,
  critical boolean NOT NULL DEFAULT false, priority int NOT NULL DEFAULT 0 CHECK (priority BETWEEN -1000 AND 1000),
  after bigint[] NOT NULL DEFAULT '{}',
  stage text NOT NULL CHECK (stage IN ('TRIAGED','DESIGN','CODE','VERIFY','FIX','CI','GATE','MERGE_READY',
                                       'NEEDS_RULING','MERGED','ABANDONED')),
  status text NOT NULL CHECK (status IN ('READY','LEASED','WAITING','HELD','CLOSED')),
  raised_from text,
  round int NOT NULL DEFAULT 0, round_cap int NOT NULL DEFAULT 3, ci_rounds int NOT NULL DEFAULT 0,
  visit int NOT NULL DEFAULT 0, visit_expiries int NOT NULL DEFAULT 0, visit_failures int NOT NULL DEFAULT 0,
  lease_epoch bigint NOT NULL DEFAULT 0, lease_hash text CHECK (lease_hash ~ '^[0-9a-f]{64}$'), lease_holder text,
  lease_until timestamptz, step_deadline timestamptz,
  read_point text CHECK (read_point ~ '^[0-9a-f]{40}$'), head text CHECK (head ~ '^[0-9a-f]{40}$'),
  ready_since timestamptz,
  wait_jobs bigint[] NOT NULL DEFAULT '{}', wait_ci boolean NOT NULL DEFAULT false,
  audit text NOT NULL DEFAULT 'none' CHECK (audit IN ('none','pending','caught','missed')),
  created_by text NOT NULL CHECK (created_by ~ '^[A-Za-z0-9_]{1,64}$'),
  created_at timestamptz NOT NULL DEFAULT now(), closed_at timestamptz,
  -- A status belongs to its stages (§5.1): HELD exactly in MERGE_READY and NEEDS_RULING, CLOSED exactly in MERGED and
  -- ABANDONED, so NEEDS_RULING is only ever HELD; LEASED only in a worker stage (so never in TRIAGED or CI); CI only
  -- WAITING (never READY or LEASED).
  CHECK ((status = 'HELD') = (stage IN ('MERGE_READY','NEEDS_RULING'))),
  CHECK ((status = 'CLOSED') = (stage IN ('MERGED','ABANDONED'))),
  CHECK (status <> 'LEASED' OR stage IN ('DESIGN','CODE','VERIFY','FIX','GATE')),
  CHECK (stage <> 'CI' OR status NOT IN ('READY','LEASED')),
  -- The columns a status implies: raised_from exactly in NEEDS_RULING, the lease's hash and both clocks exactly while
  -- LEASED, closed_at exactly when CLOSED.
  CHECK ((raised_from IS NOT NULL) = (stage = 'NEEDS_RULING')),
  CHECK ((lease_hash IS NOT NULL) = (status = 'LEASED') AND (lease_until IS NOT NULL) = (status = 'LEASED')
         AND (step_deadline IS NOT NULL) = (status = 'LEASED')),
  CHECK ((closed_at IS NOT NULL) = (status = 'CLOSED')));
CREATE INDEX tasks_ready ON tasks (stage, critical DESC, priority DESC, ready_since, id) WHERE status = 'READY';
CREATE INDEX tasks_leased ON tasks (lease_until) WHERE status = 'LEASED';

CREATE TABLE task_events (
  seq bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(),
  task_id bigint NOT NULL REFERENCES tasks(id), kind text NOT NULL, stage text NOT NULL, status text NOT NULL,
  visit int NOT NULL, epoch bigint NOT NULL, actor text NOT NULL, payload jsonb NOT NULL);
CREATE INDEX task_events_task ON task_events (task_id, seq);

CREATE TABLE task_evidence (
  id bigserial PRIMARY KEY, task_id bigint NOT NULL REFERENCES tasks(id), epoch bigint NOT NULL,
  stage text NOT NULL, schema text NOT NULL, accepted boolean NOT NULL,
  document jsonb NOT NULL, checks jsonb NOT NULL,
  sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'), at timestamptz NOT NULL DEFAULT now());

CREATE TABLE task_items (
  task_id bigint NOT NULL REFERENCES tasks(id), item text NOT NULL CHECK (item ~ '^[A-Za-z0-9_.-]{1,40}$'),
  raised_round int NOT NULL, raised_evidence bigint NOT NULL REFERENCES task_evidence(id),
  status text NOT NULL CHECK (status IN ('open','fixed','disputed','confirmed','reopened','withdrawn')),
  PRIMARY KEY (task_id, item));

CREATE TABLE task_rulings (
  id bigserial PRIMARY KEY, task_id bigint NOT NULL REFERENCES tasks(id),
  raised_from text NOT NULL, raised_visit int NOT NULL, kind text NOT NULL, request jsonb NOT NULL,
  raised_at timestamptz NOT NULL DEFAULT now(), raised_by text NOT NULL,
  ruled_at timestamptz, ruled_by text, option text, ruling_text text,
  know_sha text CHECK (know_sha ~ '^[0-9a-f]{40}$'));
CREATE UNIQUE INDEX task_rulings_open ON task_rulings (task_id) WHERE ruled_at IS NULL;

-- task_events and task_evidence are write-once (V1's temper_write_once): never updated, never truncated, and deleted only
-- under retention's setting, which no M3 retention path sets for them (§5.3: never deleted in M3).
CREATE TRIGGER task_events_write_once   BEFORE UPDATE OR DELETE ON task_events   FOR EACH ROW EXECUTE FUNCTION temper_write_once();
CREATE TRIGGER task_evidence_write_once BEFORE UPDATE OR DELETE ON task_evidence FOR EACH ROW EXECUTE FUNCTION temper_write_once();
CREATE TRIGGER task_events_no_truncate   BEFORE TRUNCATE ON task_events   FOR EACH STATEMENT EXECUTE FUNCTION temper_write_once();
CREATE TRIGGER task_evidence_no_truncate BEFORE TRUNCATE ON task_evidence FOR EACH STATEMENT EXECUTE FUNCTION temper_write_once();

-- A ruling is written once (§5.5 [P]): the request and the raise are immutable, and the ruling columns (ruled_at,
-- ruled_by, option, ruling_text, and know_sha if any) go from NULL to their values together, in the one UPDATE that sets
-- ruled_at: an open ruling is never partly written, so `rule` can always still write it. Any other
-- UPDATE, every DELETE and TRUNCATE are refused with SQLSTATE 23000, as the write-once tables are.
CREATE FUNCTION temper_ruling_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF OLD.ruled_at IS NULL AND OLD.ruled_by IS NULL AND OLD.option IS NULL AND OLD.ruling_text IS NULL
       AND OLD.know_sha IS NULL
       AND NEW.ruled_at IS NOT NULL AND NEW.ruled_by IS NOT NULL AND NEW.option IS NOT NULL AND NEW.ruling_text IS NOT NULL
       AND NEW.id = OLD.id AND NEW.task_id = OLD.task_id AND NEW.raised_from = OLD.raised_from
       AND NEW.raised_visit = OLD.raised_visit AND NEW.kind = OLD.kind AND NEW.request = OLD.request
       AND NEW.raised_at = OLD.raised_at AND NEW.raised_by = OLD.raised_by THEN
      RETURN NEW;
    END IF;
  END IF;
  RAISE EXCEPTION 'temper: % on % is forbidden (a ruling is written once)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER task_rulings_once        BEFORE UPDATE OR DELETE ON task_rulings FOR EACH ROW EXECUTE FUNCTION temper_ruling_once();
CREATE TRIGGER task_rulings_no_truncate BEFORE TRUNCATE ON task_rulings FOR EACH STATEMENT EXECUTE FUNCTION temper_ruling_once();

-- A job submitted under a lease (`submit --task`, §6.4) names its task and the epoch it was submitted at; while such a
-- job is not terminal at the task's current epoch, the lease's TTL does not lapse (§6.2).
ALTER TABLE jobs ADD COLUMN task_id bigint REFERENCES tasks(id), ADD COLUMN task_epoch bigint,
  ADD CONSTRAINT jobs_task_link CHECK ((task_id IS NULL) = (task_epoch IS NULL));
CREATE INDEX jobs_task ON jobs (task_id) WHERE task_id IS NOT NULL;
