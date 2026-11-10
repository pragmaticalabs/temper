-- WP-B: the tip of each configured base as the poller last read it, and BASE_MOVED admitted
-- into the change feed. Only the GitHub poller's single-flight cycle writes base_tip, in the transaction that inserts
-- the BASE_MOVED row under the change lock (7172).
CREATE TABLE base_tip (
    repo    text        NOT NULL,
    base    text        NOT NULL,
    sha     char(40)    NOT NULL,
    tree    char(40)    NOT NULL,
    seen_at timestamptz NOT NULL,
    PRIMARY KEY (repo, base)
);
ALTER TABLE change DROP CONSTRAINT change_kind_check;
ALTER TABLE change ADD CONSTRAINT change_kind_check CHECK (kind IN ('CHECK_CELL', 'PR_STATE', 'HEAD_MOVED',
    'CHECK_CONCLUDED', 'CHECKS_SETTLED', 'PUSH_RUN_CONCLUDED', 'SCHEDULED_RUN_CONCLUDED', 'BOT_ISSUE_OPENED',
    'BOT_ISSUE_CLOSED', 'RUN_CANCELLED', 'STALE_RUN_ALIVE', 'REQUIRED_LIST_CHANGED', 'BASE_MOVED'));
