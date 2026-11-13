-- temper lanes as tasks (WP1 + the schema of WP2): a lane outlives its agent.
-- Applied once by MigrationRunner; never edit after release.
-- Rollback (manual): DROP TABLE task_report; DROP TABLE task_checkpoint; DELETE FROM schema_migrations WHERE version = 20;
--
-- task_checkpoint: what a lane records after every push so a successor's claim yields its state file. Versioned per
-- task (seq 1, 2, ...), never updated. lease_epoch is the epoch the writer held; pushed_sha was reachable from the
-- task's branch at the controller's mirror when it was written. The 16 KB body cap is the route's, not the table's.
CREATE TABLE task_checkpoint (
  task_id bigint NOT NULL REFERENCES tasks(id),
  seq int NOT NULL CHECK (seq >= 1),
  lease_epoch bigint NOT NULL,
  pushed_sha text NOT NULL CHECK (pushed_sha ~ '^[0-9a-f]{40}$'),
  checklist jsonb NOT NULL,
  read_map jsonb NOT NULL,
  note text NOT NULL,
  at timestamptz NOT NULL,
  PRIMARY KEY (task_id, seq)
);

-- task_report: telemetry about the lease holder, one row per task. A side table and not columns of tasks, because
-- the tasks row is the fold of its task_events (TaskProjectionIT) and none of this is a state transition.
--   last_hb_at, report_*: written by the lease-scoped heartbeat (the report is what the agent SELF-REPORTED at
--     report_at, under report_epoch; the controller cannot observe the tree) and by a claim (last_hb_at = claim time,
--     report_* left as the predecessor's, which is how a claim sees a dirty predecessor).
--   observed_*: written ONLY by the full-scope POST tasks/{id}/observe (the parent measures context from the agent's
--     transcript; the agent's lease token cannot write it).
CREATE TABLE task_report (
  task_id bigint PRIMARY KEY REFERENCES tasks(id),
  last_hb_at timestamptz,
  report_epoch bigint,
  report_at timestamptz,
  report_head text,
  report_dirty int CHECK (report_dirty >= 0),
  report_unpushed int CHECK (report_unpushed >= 0),
  observed_ctx_k int CHECK (observed_ctx_k >= 0),
  observed_turns int CHECK (observed_turns >= 0),
  observed_at timestamptz
);

-- One-time backfill of the heartbeat clock for tasks that are LEASED when this migration runs (leased before V20, so
-- no heartbeat or claim has written a row yet): the time of the latest claim ('leased') or 'heartbeat' event of the
-- task's CURRENT epoch, which task_events records exactly. Without it a lane that died around the deploy shows no
-- hbAge and is never stale until its TTL expires it. Other events (lease-extended at controller start, job-linked)
-- are not a heartbeat and do not count; a LEASED task with no such event gets no row.
INSERT INTO task_report (task_id, last_hb_at)
SELECT t.id, max(e.at)
FROM tasks t JOIN task_events e ON e.task_id = t.id AND e.epoch = t.lease_epoch
WHERE t.status = 'LEASED' AND e.kind IN ('leased', 'heartbeat')
GROUP BY t.id;
