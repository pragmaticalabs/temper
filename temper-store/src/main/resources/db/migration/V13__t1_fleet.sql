-- temper CI interface T1 WP3: the fleet event stream (design §2.2). Applied once by
-- MigrationRunner; never edit after release.
--
-- temper's own epoch: one row, rewritten only by the controller when its high-water file shows the database was
-- restored or replaced (R3-4: no operator step).
CREATE TABLE temper_epoch (id uuid PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now());
INSERT INTO temper_epoch (id) VALUES (gen_random_uuid());

-- Every insert takes pg_advisory_xact_lock(70700001), the lock `events` inserts take, so seqs commit in order (F9).
-- `epoch` is temper's epoch when the row was written. `source_seq` is kiln's change id and NULL for every other
-- producer; NULLs never conflict, so UNIQUE binds only the kiln rows (F10: the pull inserts ON CONFLICT DO NOTHING).
CREATE TABLE fleet_events (
    seq          bigserial PRIMARY KEY,
    epoch        uuid NOT NULL,
    at           timestamptz NOT NULL DEFAULT now(),
    observed_at  timestamptz NOT NULL,
    source       text NOT NULL,
    source_epoch text,
    source_seq   bigint,
    kind         text NOT NULL,
    subject      text NOT NULL,
    payload      jsonb NOT NULL,
    catch_up     boolean NOT NULL DEFAULT false,
    UNIQUE (source, source_epoch, source_seq)
);
CREATE INDEX fleet_events_at ON fleet_events (at);

-- Retention (30 days) raises the floor and deletes in one transaction, as event_floor does for `events`.
CREATE TABLE fleet_floor (id int PRIMARY KEY CHECK (id = 1), floor_seq bigint NOT NULL CHECK (floor_seq >= 0));
INSERT INTO fleet_floor (id, floor_seq) VALUES (1, 0);

-- A subscriber is bound to the id (SHA-256 prefix) of the token that created it (R2-f).
CREATE TABLE fleet_subscribers (
    name         text PRIMARY KEY,
    cursor       bigint NOT NULL CHECK (cursor >= 0),
    token_id     text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_poll_at timestamptz
);

-- One row per producer (§2.5). Staleness is computed from last_ok_at and period_s only (F13); `stale` records the last
-- announced verdict so a transition is announced once. The kiln row also keeps the pull state: `epoch` (kiln's, as
-- sent back), `cursor`, `generation` (bumped by a rewind, R2-6.1, so re-used kiln ids stay distinct in source_epoch),
-- `catch_up_to` (rows up to this kiln id are catch-up, H4) and `head` (kiln's head at the last pull, for kilnLag).
CREATE TABLE fleet_source (
    name        text PRIMARY KEY,
    epoch       text,
    cursor      bigint NOT NULL DEFAULT 0,
    last_ok_at  timestamptz,
    period_s    int NOT NULL CHECK (period_s > 0),
    generation  int NOT NULL DEFAULT 0,
    catch_up_to bigint NOT NULL DEFAULT 0,
    head        bigint NOT NULL DEFAULT 0,
    stale       boolean NOT NULL DEFAULT false
);

-- The controller's own heartbeat log: one row per minute, 30 days.
CREATE TABLE fleet_heartbeat_tick (at timestamptz PRIMARY KEY);
