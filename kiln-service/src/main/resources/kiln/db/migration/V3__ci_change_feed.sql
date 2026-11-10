-- CI interface T1 (design §2.1 "kiln migration V2", numbered V3 here since V2 is temper_gate_answer_sha).
-- The epoch (review H4): one row, generated when this file runs. Every table is a cache, so a rebuilt database runs it
-- again and gets a new epoch by construction; a reader holding the old one is told to reset. The unique index on a
-- constant admits at most one row.
CREATE TABLE kiln_epoch (
    id         uuid        PRIMARY KEY,
    created_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX kiln_epoch_one ON kiln_epoch ((true));
INSERT INTO kiln_epoch (id, created_at) VALUES (gen_random_uuid(), now());

-- The change feed (`GET /api/v1/changes`): every kind WP1 and WP2 write, so WP2 needs no further CHECK migration; the
-- repository a change is about (every row so far is pragmatica's); `pr` NULL for a change about no PR (a base run, a bot
-- issue, a required list: WP2); `payload` the kind's extra facts, NULL for the WP1 kinds.
ALTER TABLE change DROP CONSTRAINT change_kind_check;
ALTER TABLE change ADD CONSTRAINT change_kind_check CHECK (kind IN ('CHECK_CELL', 'PR_STATE', 'HEAD_MOVED',
                                                                    'CHECK_CONCLUDED', 'CHECKS_SETTLED',
                                                                    'PUSH_RUN_CONCLUDED', 'SCHEDULED_RUN_CONCLUDED',
                                                                    'BOT_ISSUE_OPENED', 'BOT_ISSUE_CLOSED',
                                                                    'RUN_CANCELLED', 'STALE_RUN_ALIVE',
                                                                    'REQUIRED_LIST_CHANGED'));
ALTER TABLE change ADD COLUMN repo text NULL;
UPDATE change SET repo = 'pragmaticalabs/pragmatica';
ALTER TABLE change ALTER COLUMN repo SET NOT NULL;
ALTER TABLE change ALTER COLUMN pr DROP NOT NULL;
ALTER TABLE change ADD COLUMN payload jsonb NULL;

-- The prune watermark (one row): every id below `floor` was pruned. Only the pruner raises it, in the statement that
-- deletes, so a gap is reported for pruned ids only; an id a rolled-back write burned is never read as pruned. Rows
-- already pruned before this file start it at the smallest id kept (or after the last id handed out).
CREATE TABLE change_floor (
    id    smallint PRIMARY KEY CHECK (id = 1),
    floor bigint   NOT NULL CHECK (floor >= 1)
);
INSERT INTO change_floor (id, floor)
VALUES (1, coalesce((SELECT min(id) FROM change),
                    (SELECT CASE WHEN is_called THEN last_value + 1 ELSE 1 END FROM change_id_seq)));
