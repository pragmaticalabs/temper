-- temper M1 schema (m1-design §7 temper-store, §2.5, §3, §4). Applied once by MigrationRunner on top of V1, in the
-- same transaction as any other pending file; never edit after release.

-- bb-hold (§1.3): a hold unit stays GRANTED while it waits; port_group says where it is. hold_since is when it
-- entered that state (the wait's start, then the grant). manifest_spec is the planner's ManifestSpec (wire form),
-- which StartUnit carries (§2.2).
ALTER TABLE units
  ADD COLUMN port_group text CHECK (port_group IN ('waiting','held')),
  ADD COLUMN bbhold_ticket text,
  ADD COLUMN hold_since timestamptz,
  ADD COLUMN manifest_spec jsonb;

-- unit_facts gains the phase 'hold' (UnitHolding's facts).
ALTER TABLE unit_facts DROP CONSTRAINT unit_facts_phase_check;
ALTER TABLE unit_facts ADD CONSTRAINT unit_facts_phase_check CHECK (phase IN ('start','end','hold'));

-- Manifest rows per unit (§2.5). M0 never wrote manifests, so the NOT NULL columns are added to an empty table;
-- a V1 database holding manifest rows fails this migration (and startup) instead of guessing their unit.
ALTER TABLE manifests
  ADD COLUMN unit_id bigint NOT NULL REFERENCES units(id),
  ADD COLUMN runner text NOT NULL CHECK (runner IN ('surefire','failsafe'));
ALTER TABLE manifests DROP CONSTRAINT manifests_pkey;
ALTER TABLE manifests ADD PRIMARY KEY (unit_id, module, class, runner);
CREATE INDEX manifests_job ON manifests (job_id);

CREATE TABLE manifest_units (
  unit_id bigint PRIMARY KEY REFERENCES units(id),
  status text NOT NULL CHECK (status IN ('computed','unsupported')),
  detail text,
  not_reached text[] NOT NULL DEFAULT '{}',
  unresolved text[] NOT NULL DEFAULT '{}');

-- Evidence tarballs on the controller host (§3). At most one live row per unit and kind; retention marks a row
-- deleted_at after deleting its file, and never deletes the row.
CREATE TABLE evidence (
  id bigserial PRIMARY KEY,
  job_id bigint NOT NULL REFERENCES jobs(id),
  unit_id bigint NOT NULL REFERENCES units(id),
  kind text NOT NULL CHECK (kind IN ('small','full')),
  path text NOT NULL,
  bytes bigint NOT NULL CHECK (bytes >= 0),
  sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  stored_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz);
CREATE UNIQUE INDEX evidence_live ON evidence (unit_id, kind) WHERE deleted_at IS NULL;
CREATE INDEX evidence_job ON evidence (job_id);

-- The event floor (§4.4): every event with seq <= floor_seq was deleted by retention. Retention raises it in the
-- transaction that deletes; a reader reads it in the statement that reads the events. M0 never deleted events, so
-- the floor starts at 0.
CREATE TABLE event_floor (id int PRIMARY KEY CHECK (id = 1), floor_seq bigint NOT NULL CHECK (floor_seq >= 0));
INSERT INTO event_floor (id, floor_seq) VALUES (1, 0);
CREATE INDEX events_at ON events (at);

-- Maintenance runs (§3.4).
CREATE TABLE maintenance_runs (
  id bigserial PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('pg_dump')),
  started_at timestamptz NOT NULL,
  ended_at timestamptz NOT NULL,
  ok boolean NOT NULL,
  detail text,
  path text,
  bytes bigint CHECK (bytes >= 0));
CREATE INDEX maintenance_runs_good ON maintenance_runs (kind, ended_at DESC) WHERE ok;

-- Hosts a job's directory may exist on (§4.3): written when a unit of the job is granted there, so a requeue (which
-- clears units.host) does not lose track of a checkout made by an earlier attempt. Backfilled from V1's units.host.
CREATE TABLE checkouts (
  job_id bigint NOT NULL REFERENCES jobs(id),
  host text NOT NULL REFERENCES hosts(name),
  PRIMARY KEY (job_id, host));
INSERT INTO checkouts (job_id, host) SELECT DISTINCT job_id, host FROM units WHERE host IS NOT NULL;

-- Host checkouts the worker deleted (§4.3).
CREATE TABLE checkouts_gone (
  job_id bigint NOT NULL REFERENCES jobs(id),
  host text NOT NULL REFERENCES hosts(name),
  gone_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (job_id, host));

-- Retention candidates (§4.2): finished, unpinned jobs, paged by id.
CREATE INDEX jobs_collectable ON jobs (id) WHERE finished_at IS NOT NULL AND NOT keep;
