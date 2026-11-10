-- temper M0 schema (spec §10, m0-design §5.2). Applied once by MigrationRunner; never edit after release:
-- the runner aborts startup when an applied file's sha256 changes (StoreError.MigrationTampered).

CREATE TABLE hosts (
  name text PRIMARY KEY, address text NOT NULL, host_key text,
  cap int NOT NULL DEFAULT 5, memory_budget_mb int NOT NULL, port_isolated boolean NOT NULL DEFAULT false,
  state text NOT NULL DEFAULT 'DOWN' CHECK (state IN ('DOWN','CONNECTING','UP')),
  facts jsonb, facts_at timestamptz);

CREATE TABLE jobs (
  id bigserial PRIMARY KEY,
  submitted_by text NOT NULL CHECK (submitted_by ~ '^[A-Za-z0-9_]{1,64}$'),
  submitted_at timestamptz NOT NULL DEFAULT now(),
  priority int NOT NULL, keep boolean NOT NULL DEFAULT false, fresh boolean NOT NULL DEFAULT false,
  unpushed boolean NOT NULL DEFAULT false,
  source jsonb NOT NULL, source_sha text CHECK (source_sha ~ '^[0-9a-f]{40}$'), ref text,
  recipe jsonb NOT NULL, recipe_kind text NOT NULL, dedup_key text,
  state text NOT NULL CHECK (state IN ('REFUSED','QUEUED','RUNNING','CANCELLING','SUPERSEDED',
                                       'PASSED','FAILED','EMPTY','VOID','ERROR','CANCELLED')),
  refusal_code text, refusal_detail text,
  superseded boolean NOT NULL DEFAULT false, superseded_by bigint REFERENCES jobs(id),
  cancel_requested_at timestamptz, finished_at timestamptz);
CREATE INDEX jobs_dedup  ON jobs (dedup_key, id DESC);
CREATE INDEX jobs_active_ref ON jobs (ref, recipe_kind) WHERE state IN ('QUEUED','RUNNING','CANCELLING');
CREATE INDEX jobs_sha    ON jobs (source_sha);

CREATE TABLE units (
  id bigserial PRIMARY KEY, job_id bigint NOT NULL REFERENCES jobs(id),
  name text NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,31}$'), kind text NOT NULL,
  host text REFERENCES hosts(name),
  state text NOT NULL CHECK (state IN ('QUEUED','GRANTED','RUNNING','CANCELLING',
                                       'PASSED','FAILED','EMPTY','VOID','ERROR','CANCELLED')),
  reason text, args jsonb NOT NULL, requests_tests boolean NOT NULL,
  needs_port_group boolean NOT NULL DEFAULT false, memory_mb int NOT NULL, timeout_s int NOT NULL,
  lease_until timestamptz, granted_at timestamptz, started_at timestamptz, ended_at timestamptz,
  rc int, killed_by text CHECK (killed_by IN ('timeout','cancel')),
  marker_epoch_ns bigint, image_id text, container_id text, tests_attr bigint,
  rerun boolean NOT NULL DEFAULT false,
  UNIQUE (job_id, name));
CREATE INDEX units_queued      ON units (id) WHERE state = 'QUEUED';
CREATE INDEX units_host_active ON units (host) WHERE state IN ('GRANTED','RUNNING','CANCELLING');

CREATE TABLE results (
  job_id bigint PRIMARY KEY REFERENCES jobs(id), verdict text NOT NULL, reason text,
  annotations text[] NOT NULL DEFAULT '{}', counts jsonb NOT NULL, record jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE testcases (
  job_id bigint NOT NULL REFERENCES jobs(id), unit_id bigint NOT NULL REFERENCES units(id),
  class text NOT NULL, name text NOT NULL,
  outcome text NOT NULL CHECK (outcome IN ('passed','failed','error','skipped')),
  time_s double precision NOT NULL, rerun boolean NOT NULL DEFAULT false);
CREATE INDEX testcases_job ON testcases (job_id, outcome);

CREATE TABLE manifests (job_id bigint NOT NULL REFERENCES jobs(id), module text NOT NULL, class text NOT NULL,
  PRIMARY KEY (job_id, module, class));

CREATE TABLE events (
  seq bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(), job_id bigint REFERENCES jobs(id),
  kind text NOT NULL CHECK (kind IN ('queued','started','unit-finished','finished','cancelled','superseded')),
  payload jsonb NOT NULL);

CREATE TABLE subscribers (
  name text PRIMARY KEY CHECK (name ~ '^[A-Za-z0-9_.-]{1,64}$'),
  cursor bigint NOT NULL, selectors jsonb NOT NULL DEFAULT '[]');

CREATE TABLE refusals (
  code text PRIMARY KEY, matcher jsonb NOT NULL, issue int, reason text NOT NULL,
  until_text text, expired_at timestamptz);

CREATE TABLE unit_facts (
  unit_id bigint NOT NULL REFERENCES units(id), phase text NOT NULL CHECK (phase IN ('start','end')),
  facts jsonb NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (unit_id, phase));

-- write-once (spec §5.4, §10). The only DELETE path is retention (M1), which runs
-- SET LOCAL temper.retention = 'on' inside its own transaction. UPDATE and TRUNCATE have no exception.
CREATE FUNCTION temper_write_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' AND current_setting('temper.retention', true) = 'on' THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'temper: % on % is forbidden (write-once)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER results_write_once   BEFORE UPDATE OR DELETE ON results   FOR EACH ROW EXECUTE FUNCTION temper_write_once();
CREATE TRIGGER testcases_write_once BEFORE UPDATE OR DELETE ON testcases FOR EACH ROW EXECUTE FUNCTION temper_write_once();
CREATE TRIGGER results_no_truncate   BEFORE TRUNCATE ON results   FOR EACH STATEMENT EXECUTE FUNCTION temper_write_once();
CREATE TRIGGER testcases_no_truncate BEFORE TRUNCATE ON testcases FOR EACH STATEMENT EXECUTE FUNCTION temper_write_once();

-- Tree-specific refusals as data (spec §2.5). R-1553 carries "runner":"failsafe" (WP1 handoff): only the
-- failsafe selection (-Dit.test) is judged for StreamPublishReshuffleTest.
INSERT INTO refusals (code, matcher, issue, reason, until_text) VALUES
 ('R-1553', '{"excludeTestClass":{"simpleClassName":"StreamPublishReshuffleTest","disabledMarker":"#1553","runner":"failsafe"}}',
  1553, 'StreamPublishReshuffleTest is unstable until #1553 is fixed', 'until #1553 closes'),
 ('R-FAILSAFE', '{"itTestRequired":{"moduleSegment":"forge-tests"}}',
  NULL, 'forge-tests with -Dtest but no -Dit.test runs every IT', 'permanent');
