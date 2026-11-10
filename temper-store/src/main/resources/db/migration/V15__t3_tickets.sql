-- temper CI interface T3 WP-M: the bb-ticket owner tables, every table later WPs code against.
-- Applied once by MigrationRunner; never edit after release (a later need is V16).
--
-- The latest Mac liveness post (design §7.1). One row; replaced by every accepted post. received_at is temper's own
-- clock at receipt (F13): freshness is computed from it alone; posted_at is the Mac's claim, shown, never compared.
CREATE TABLE mac_post (
    id          int         PRIMARY KEY CHECK (id = 1),
    received_at timestamptz NOT NULL,
    posted_at   timestamptz NOT NULL,
    processes   jsonb       NOT NULL CHECK (jsonb_typeof(processes) = 'array')
);

-- Every session any post has listed: the source of DEAD(last seen <t>). (pid, start_s) is the identity: a reused pid
-- has another start, so it is another row (H5).
CREATE TABLE mac_session (
    pid        int         NOT NULL CHECK (pid > 0),
    start_s    bigint      NOT NULL CHECK (start_s >= 1000000000),
    label      text        NOT NULL CHECK (label ~ '^[A-Za-z0-9._:-]{1,64}$'),
    first_seen timestamptz NOT NULL,
    last_seen  timestamptz NOT NULL,
    PRIMARY KEY (pid, start_s)
);
CREATE INDEX mac_session_last_seen ON mac_session (last_seen);

-- The latest successful ticket collection per expected host (design §7.3, F18): the diff baseline that survives a
-- restart. tickets = the bb-tickets ticket lines in output order.
CREATE TABLE bb_snapshot (
    host     text        PRIMARY KEY CHECK (host ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    taken_at timestamptz NOT NULL,
    queue    text        CHECK (queue IN ('present', 'absent')),
    tickets  jsonb       NOT NULL CHECK (jsonb_typeof(tickets) = 'array')
);

-- Collection state per expected host, so "UNREACHABLE since <t>" survives a restart. failing_since = the first failed
-- attempt after the last success (NULL while the last attempt succeeded).
CREATE TABLE bb_collect (
    host            text        PRIMARY KEY CHECK (host ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    last_attempt_at timestamptz,
    last_ok_at      timestamptz,
    failing_since   timestamptz,
    last_error      text        CHECK (last_error IS NULL OR length(last_error) <= 512)
);
