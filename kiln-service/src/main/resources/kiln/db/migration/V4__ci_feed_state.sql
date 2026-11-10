-- CI interface T1 WP2 (design §2.1, §2.5): the state the GitHub-derived change kinds are diffed against, one set per
-- repository (`[[repos]]`). Each table is a cache like every other: a rebuilt database starts empty, under a new epoch
-- (V3), and its first cycle reports what it then sees.

-- The terminal check observations reported at a PR's current head, one row per {check, suite} (CheckFeed.Observation
-- key); a head that moved drops the old head's rows.
CREATE TABLE feed_check (
    repo        text        NOT NULL,
    pr          bigint      NOT NULL,
    head_sha    char(40)    NOT NULL,
    obs_key     text        NOT NULL,
    fingerprint text        NOT NULL,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (repo, pr, obs_key)
);

-- Per PR: its current head, when kiln first saw it (the settle window of a head with no run), and the settled outcome
-- last reported for it (NULL: none yet).
CREATE TABLE feed_settle (
    repo        text        NOT NULL,
    pr          bigint      NOT NULL,
    head_sha    char(40)    NOT NULL,
    first_seen  timestamptz NOT NULL,
    outcome     text        NULL,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (repo, pr)
);

-- Every push run of a tracked branch and every scheduled run kiln has read, as last read. refreshed_at orders the
-- bounded re-read by id (review R2-11, R3-5): oldest first.
CREATE TABLE feed_run (
    repo         text        NOT NULL,
    id           bigint      NOT NULL,
    workflow     text        NOT NULL,
    event        text        NOT NULL,
    branch       text        NULL,
    status       text        NOT NULL,
    conclusion   text        NULL,
    attempt      bigint      NOT NULL,
    created_at   timestamptz NOT NULL,
    refreshed_at timestamptz NOT NULL,
    PRIMARY KEY (repo, id)
);
CREATE INDEX feed_run_window ON feed_run (repo, event, branch, created_at);
CREATE INDEX feed_run_reread ON feed_run (repo, refreshed_at);

-- The `github-actions` issues as last read with `state=all&since=`.
CREATE TABLE feed_issue (
    repo        text        NOT NULL,
    number      bigint      NOT NULL,
    state       text        NOT NULL,
    updated_at  timestamptz NOT NULL,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (repo, number)
);

-- Every version of a base's required list (F18, NOTE 9): a new version when the contexts differ, last_seen moved
-- forward while they do not. etag is the branch read's ETag when one was stored (NULL otherwise).
CREATE TABLE required_list_history (
    repo       text        NOT NULL,
    base       text        NOT NULL,
    version    int         NOT NULL CHECK (version >= 1),
    contexts   jsonb       NOT NULL,
    etag       text        NULL,
    first_seen timestamptz NOT NULL,
    last_seen  timestamptz NOT NULL,
    PRIMARY KEY (repo, base, version)
);
