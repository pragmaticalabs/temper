-- temper CI interface T2 WP-0T: the merge gate's schema, every table later WPs code against.
-- Applied once by MigrationRunner; never edit after release (a later need is V15).
--
-- Verdict registry (design §4.3). The latest row by id for (repo, pr, sha) is the verdict (NOTE 5). `declared_by` is
-- the design's `by`: self-declared, printed, never a control (R2-2). A carried row (F8) copies the report fields of
-- the ACCEPT it carries (carried_verdict_id) and is itself ACCEPT.
CREATE TABLE verdicts (
    id                 bigserial PRIMARY KEY,
    repo               text        NOT NULL CHECK (repo ~ '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'),
    pr                 bigint      NOT NULL CHECK (pr > 0),
    sha                char(40)    NOT NULL CHECK (sha ~ '^[0-9a-f]{40}$'),
    base               text        NOT NULL CHECK (base <> '' AND length(base) <= 255),
    verdict            text        NOT NULL CHECK (verdict IN ('ACCEPT', 'FIX', 'REJECT')),
    declared_by        text        NOT NULL CHECK (declared_by ~ '^[!-~]{1,64}$'),
    report_path        text        NOT NULL CHECK (report_path LIKE '/%' AND length(report_path) <= 4096),
    report_sha256      char(64)    NOT NULL CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
    carried_from       char(40)    NULL     CHECK (carried_from ~ '^[0-9a-f]{40}$'),
    carried_verdict_id bigint      NULL     REFERENCES verdicts (id),
    at                 timestamptz NOT NULL DEFAULT now(),
    CHECK ((carried_from IS NULL) = (carried_verdict_id IS NULL)),
    CHECK (carried_from IS NULL OR verdict = 'ACCEPT')
);
CREATE INDEX verdicts_head ON verdicts (repo, pr, sha, id DESC);
CREATE INDEX verdicts_pr   ON verdicts (repo, pr, id DESC);

-- A GO (design §4.1, §4.2a): a single-use record. `fleet_seq` is the fleet head read before the gate's server reads
-- began; an invalidating event with a higher seq invalidates it. Outstanding = consumed_at IS NULL AND
-- invalidated_at IS NULL AND created_at > now() - interval '10 minutes'. There is no generated expiry column
-- (timestamptz + interval is not immutable); every query states the 10 minutes.
CREATE TABLE go (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    repo               text        NOT NULL CHECK (repo ~ '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'),
    pr                 bigint      NOT NULL CHECK (pr > 0),
    sha                char(40)    NOT NULL CHECK (sha ~ '^[0-9a-f]{40}$'),
    base               text        NOT NULL,
    base_tip           char(40)    NOT NULL CHECK (base_tip ~ '^[0-9a-f]{40}$'),
    verdict_id         bigint      NOT NULL REFERENCES verdicts (id),
    fleet_seq          bigint      NOT NULL CHECK (fleet_seq >= 0),
    created_at         timestamptz NOT NULL DEFAULT now(),
    consumed_at        timestamptz NULL,
    invalidated_at     timestamptz NULL,
    reason             text        NULL CHECK (reason IN ('sibling-consumed', 'base-moved', 'verdict-changed',
                                                          'checks-changed')),
    invalidated_by_seq bigint      NULL,
    outcome            text        NULL CHECK (outcome IN ('consumed', 'refused', 'merging', 'merged',
                                                           'merge-failed')),
    outcome_detail     text        NULL CHECK (length(outcome_detail) <= 500),
    outcome_at         timestamptz NULL,
    base_tip_before    char(40)    NULL CHECK (base_tip_before ~ '^[0-9a-f]{40}$'),
    CHECK ((invalidated_at IS NULL) = (reason IS NULL)),
    CHECK (consumed_at IS NULL OR invalidated_at IS NULL),
    CHECK ((consumed_at IS NULL) = (outcome IS NULL))
);
CREATE INDEX go_open_base ON go (repo, base) WHERE consumed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX go_open_pr   ON go (repo, pr)   WHERE consumed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX go_head      ON go (repo, pr, sha);

-- Every merge temper has seen, by either route, once (the dedupe key of `merged` / `ungated-merge`). `sha` is
-- 'unknown' when kiln's PR_STATE row carried no head (a kiln without WP-0K).
CREATE TABLE merges (
    repo      text        NOT NULL,
    pr        bigint      NOT NULL CHECK (pr > 0),
    sha       text        NOT NULL CHECK (sha ~ '^[0-9a-f]{40}$' OR sha = 'unknown'),
    base      text        NOT NULL,
    go_id     uuid        NULL REFERENCES go (id),
    route     text        NOT NULL CHECK (route IN ('temper-merge', 'kiln')),
    merged_by text        NULL,
    merged_at timestamptz NULL,
    event_seq bigint      NULL,
    seen_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (repo, pr, sha)
);

-- Serial merge queue (design §4.4). Positions are dense 1..n per (repo, base); a PR is queued at most once.
CREATE TABLE merge_queue (
    repo     text        NOT NULL,
    base     text        NOT NULL,
    position int         NOT NULL CHECK (position >= 1),
    pr       bigint      NOT NULL CHECK (pr > 0),
    added_by text        NOT NULL CHECK (added_by ~ '^[!-~]{1,64}$'),
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (repo, pr),
    UNIQUE (repo, base, position) DEFERRABLE INITIALLY DEFERRED
);

-- G9's path list (design §4.2 G9, R2-4), edited with the VERDICT token. Glob syntax: G9.
CREATE TABLE ci_control_paths (
    repo     text        NOT NULL,
    glob     text        NOT NULL CHECK (glob ~ '^[A-Za-z0-9_.*/-]+$' AND glob !~ '^/' AND glob !~ '(^|/)\.\.(/|$)'),
    added_by text        NOT NULL,
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (repo, glob)
);
INSERT INTO ci_control_paths (repo, glob, added_by) VALUES
    ('pragmaticalabs/pragmatica', '.github/**', 'V14'),
    ('pragmaticalabs/pragmatica', 'scripts/changelog-check.sh', 'V14'),
    ('pragmaticalabs/pragmatica', 'tools/**', 'V14'),
    ('pragmaticalabs/pragmatica', '.mvn/**', 'V14'),
    ('pragmaticalabs/pragmatica', 'pom.xml', 'V14'),
    ('pragmaticalabs/temper-control', '.github/**', 'V14');   -- Q5

-- T1 cursor addition: the T2 detector is a fleet producer with a cursor. `started_at` lets it skip catch-up
-- rows observed before it existed (a first kiln pull or a kiln rewind replays kiln's whole retention as catch-up).
ALTER TABLE fleet_source ADD COLUMN started_at timestamptz NULL;
INSERT INTO fleet_source (name, cursor, period_s, last_ok_at, started_at)
SELECT 't2:detector', greatest(coalesce(max(seq), 0), (SELECT floor_seq FROM fleet_floor)), 5, now(), now()
FROM fleet_events;
