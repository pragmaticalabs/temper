-- The team's work ledger (the dashboard's Work tab): explicit phase transitions of each work package, written by the
-- team lead through `kiln-work` -> `kiln-ingest work` -> `POST /api/v1/ingest/work`. Unlike every other kiln table this
-- is NOT a cache: phase and round are not inferable from git or GitHub, so a dropped database loses the ledger.

-- One row per work package. `item` is the id's leading segment (`T1` of `T1-WP5`); `submitted_at` is the time of the
-- item's earliest event; `title` is the last one given (NULL until one is).
CREATE TABLE work_item (
    id           text        PRIMARY KEY CHECK (char_length(id) <= 64 AND id ~ '^[A-Za-z0-9]+(-[A-Za-z0-9]+)*$'),
    item         text        NOT NULL CHECK (item ~ '^[A-Za-z0-9]+$'),
    title        text        NULL CHECK (char_length(title) <= 200),
    submitted_at timestamptz NOT NULL
);

-- Append-only: rows are inserted, never updated or deleted. An item's current state is its latest event by (at, id),
-- so an event recorded late with an earlier `at` takes its place in the history instead of overriding the present.
CREATE TABLE work_event (
    id       bigserial   PRIMARY KEY,
    item_id  text        NOT NULL REFERENCES work_item (id),
    at       timestamptz NOT NULL,
    phase    text        NOT NULL CHECK (phase IN ('design', 'coding', 'verify', 'fixing', 'accepted', 'integrated',
                                                   'in-pr', 'merged', 'parked')),
    round    int         NOT NULL CHECK (round >= 0),
    coder    text        NULL CHECK (char_length(coder) <= 64 AND coder ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$'),
    verifier text        NULL CHECK (char_length(verifier) <= 64 AND verifier ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$'),
    note     text        NULL CHECK (char_length(note) <= 300)
);
-- The latest event of an item and its phase runs are read in (at, id) order per item; the ledger's freshness is the
-- newest `at` of the items' latest events, so no index on `at` alone is needed.
CREATE INDEX work_event_item_order ON work_event (item_id, at, id);
