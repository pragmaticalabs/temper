-- kiln F1 schema (kiln-f1-design §2). Every table is a cache: dropping the database loses nothing (spec §1, F1).
-- PR numbers are bigint (kiln's PrNumber is a long). Times are timestamptz; the session runs in UTC.

-- One row per source key (`github`, `mirror`, `temper`, `host:<name>`, `mac`). A success sets last_success_at; a
-- failure keeps it, so the header always shows the last success (spec §6 panel 1).
CREATE TABLE source_state (
    source          text        PRIMARY KEY,
    state           text        NOT NULL CHECK (state IN ('OK', 'UNREACHABLE', 'INCOMPLETE', 'RATE_LIMITED', 'GITHUB_DOWN',
                                                          'TEMPER_DOWN', 'TEMPER_SCHEMA')),
    detail          text        NULL,
    since           timestamptz NOT NULL,
    last_success_at timestamptz NULL,
    updated_at      timestamptz NOT NULL
);

-- The last 200 body for each URL, replayed on a 304.
CREATE TABLE gh_etag (
    url         text        PRIMARY KEY,
    etag        text        NOT NULL,
    body        text        NOT NULL,
    observed_at timestamptz NOT NULL
);

-- `backfill_done`, `pulls_high_water` (an ISO instant).
CREATE TABLE gh_cursor (
    name  text PRIMARY KEY,
    value text NOT NULL
);

-- Every PR, kept forever (K-13): the triggers below refuse DELETE and TRUNCATE, so no code path can shrink the table.
-- mergeable, mergeable_state and head_committed_at come from the open-PR detail read, observed at detail_at.
CREATE TABLE pr (
    number            bigint      PRIMARY KEY CHECK (number >= 1),
    title             text        NOT NULL,
    state             text        NOT NULL CHECK (state IN ('open', 'closed', 'merged')),
    draft             boolean     NOT NULL,
    base_ref          text        NOT NULL,
    head_ref          text        NOT NULL,
    head_repo         text        NULL,
    head_sha          char(40)    NOT NULL,
    merged_at         timestamptz NULL,
    closed_at         timestamptz NULL,
    updated_at        timestamptz NOT NULL,
    labels            text[]      NOT NULL,
    closes            bigint[]    NOT NULL,
    part_of           bigint[]    NOT NULL,
    mergeable         boolean     NULL,
    mergeable_state   text        NULL,
    head_committed_at timestamptz NULL,
    detail_at         timestamptz NULL,
    observed_at       timestamptz NOT NULL
);
CREATE INDEX pr_head_ref ON pr (head_ref);
CREATE INDEX pr_head_sha ON pr (head_sha);

CREATE FUNCTION pr_never_deleted() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'pr rows are never deleted (K-13)';
END
$$;
CREATE TRIGGER pr_no_delete BEFORE DELETE ON pr FOR EACH ROW EXECUTE FUNCTION pr_never_deleted();
CREATE TRIGGER pr_no_truncate BEFORE TRUNCATE ON pr FOR EACH STATEMENT EXECUTE FUNCTION pr_never_deleted();

-- A base's required-check list, rewritten every cycle; a failed read writes UNKNOWN(status).
CREATE TABLE required_checks (
    base_ref    text        PRIMARY KEY,
    kind        text        NOT NULL CHECK (kind IN ('REQUIRED', 'NONE_REQUIRED', 'UNKNOWN')),
    enforcement text        NULL,
    why         text        NULL,
    status      text        NULL,
    stacked     boolean     NOT NULL,
    checks      jsonb       NOT NULL,
    observed_at timestamptz NOT NULL
);

-- The cells of a PR's current head only, one per required check {context, app_id}: one context pinned to two apps (or
-- pinned and not) keeps two cells (lead ruling, WP2). A NULL app_id is a key value of its own (COALESCE(app_id, -1)).
-- `foreign_runs` is the design's `foreign` (a reserved word in SQL).
CREATE TABLE check_cell (
    pr           bigint      NOT NULL,
    context      text        NOT NULL,
    head_sha     char(40)    NOT NULL,
    app_id       bigint      NULL,
    value        text        NOT NULL,
    foreign_runs int         NOT NULL,
    shown        jsonb       NOT NULL,
    observed_at  timestamptz NOT NULL
);
CREATE UNIQUE INDEX check_cell_key ON check_cell (pr, context, COALESCE(app_id, -1));

-- NULL reads UNKNOWN, never 0 (spec §5.1, K-8).
CREATE TABLE pr_mirror (
    pr                bigint      PRIMARY KEY,
    head_sha          char(40)    NOT NULL,
    base_ref          text        NOT NULL,
    behind_by         int         NULL,
    behind_tree_empty boolean     NULL,
    fetched_at        timestamptz NOT NULL
);

-- schema_field set => TEMPER-SCHEMA(field) for that PR; exactly one of answer and schema_field is set.
CREATE TABLE temper_gate (
    pr           bigint      PRIMARY KEY,
    head_sha     char(40)    NOT NULL,
    answer       text        NULL,
    job          bigint      NULL,
    judge        text        NULL,
    schema_field text        NULL,
    observed_at  timestamptz NOT NULL,
    CHECK ((answer IS NULL) <> (schema_field IS NULL))
);

-- temper's digest text, stored verbatim and never decoded.
CREATE TABLE temper_digest (
    id          smallint    PRIMARY KEY CHECK (id = 1),
    text        text        NOT NULL,
    observed_at timestamptz NOT NULL
);

-- A successful read replaces its scope's set (a base, or `schedule`); a failed one keeps the old set with its age. The
-- key is (scope, id): a scheduled run on a base is listed under both scopes.
CREATE TABLE base_run (
    scope       text        NOT NULL,
    id          bigint      NOT NULL,
    name        text        NOT NULL,
    status      text        NOT NULL,
    conclusion  text        NULL,
    head_sha    text        NOT NULL,
    html_url    text        NOT NULL,
    created_at  timestamptz NOT NULL,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (scope, id)
);

-- The open `github-actions` issues; replaced as base_run.
CREATE TABLE gh_issue (
    number      bigint      PRIMARY KEY,
    title       text        NOT NULL,
    html_url    text        NOT NULL,
    created_at  timestamptz NOT NULL,
    observed_at timestamptz NOT NULL
);

-- facts_at IS NULL => UNSCANNED (Q-5): a stub line lists a checkout without full facts. Deleted only by a COMPLETE pass
-- of a reachable host, within that pass's roots (§3.4).
CREATE TABLE checkout (
    host         text        NOT NULL,
    path         text        NOT NULL,
    common_dir   text        NOT NULL,
    head_sha     char(40)    NULL,
    branch       text        NULL,
    detached     boolean     NULL,
    remote_url   text        NULL,
    branches     jsonb       NULL,
    modified     int         NULL,
    untracked    int         NULL,
    op           text        NULL,
    activity_at  timestamptz NOT NULL,
    processes    bigint[]    NULL,
    maven        boolean     NULL,
    salvage_refs int         NULL,
    facts_pass   text        NULL CHECK (facts_pass IN ('hot', 'full', 'push')),
    facts_at     timestamptz NULL,
    listed_at    timestamptz NOT NULL,
    size_kb      bigint      NULL,
    size_at      timestamptz NULL,
    PRIMARY KEY (host, path),
    CHECK ((facts_at IS NULL) = (facts_pass IS NULL)),
    CHECK (facts_at IS NULL OR (branches IS NOT NULL AND modified IS NOT NULL AND untracked IS NOT NULL
                                AND processes IS NOT NULL AND maven IS NOT NULL))
);

-- One stash count per common git dir, shared by every worktree of it (spec §5.2).
CREATE TABLE stash (
    host        text        NOT NULL,
    common_dir  text        NOT NULL,
    count       int         NOT NULL,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (host, common_dir)
);

-- The derivation with the times of its inputs, so a status always shows the age of the fact it came from (spec §5.6).
CREATE TABLE checkout_derived (
    host         text        NOT NULL,
    path         text        NOT NULL,
    status       text        NOT NULL CHECK (status IN ('ACTIVE', 'IN_USE', 'REMOVABLE', 'BASE_CLEAN', 'NEEDS_SALVAGE',
                                                        'STALE_UNOWNED', 'UNOWNED', 'UNSCANNED')),
    preservation text        NOT NULL CHECK (preservation IN ('PRESERVED', 'HOST_BARE_ONLY', 'UNPRESERVED')),
    reasons      text[]      NOT NULL,
    at_base      boolean     NULL,
    linked_prs   bigint[]    NOT NULL,
    link_rule    text        NOT NULL CHECK (link_rule IN ('BRANCH', 'SHA', 'ANCESTRY', 'NONE')),
    facts_at     timestamptz NULL,
    mirror_at    timestamptz NULL,
    prs_at       timestamptz NULL,
    derived_at   timestamptz NOT NULL,
    PRIMARY KEY (host, path),
    FOREIGN KEY (host, path) REFERENCES checkout (host, path) ON DELETE CASCADE
);

-- Non-git directories under a root (Q-4); replaced only by a COMPLETE pass.
CREATE TABLE non_git (
    host      text        NOT NULL,
    root      text        NOT NULL,
    path      text        NOT NULL,
    listed_at timestamptz NOT NULL,
    PRIMARY KEY (host, root, path)
);

-- The last pass of each kind; the Mac uses kind `push` (its finished_at is pushedAt).
CREATE TABLE host_pass (
    host        text        NOT NULL,
    kind        text        NOT NULL CHECK (kind IN ('hot', 'full', 'push')),
    started_at  timestamptz NOT NULL,
    finished_at timestamptz NULL,
    outcome     text        NOT NULL CHECK (outcome IN ('COMPLETE', 'INCOMPLETE', 'UNREACHABLE', 'RUNNING')),
    rows        int         NOT NULL,
    expected    int         NULL,
    non_git     int         NULL,
    progress_k  int         NULL,
    progress_n  int         NULL,
    PRIMARY KEY (host, kind)
);

-- Observed changes (spec §7), inserted in the same transaction as the write that changed the value; pruned after 30 days.
-- observed_at is the caller's observation (shown); recorded_at is the summary cursor, stamped by the store when the write
-- begins (lead ruling, 2026-10-30), so a caller's lag never puts a change behind a cursor already handed out.
CREATE TABLE change (
    id          bigserial   PRIMARY KEY,
    observed_at timestamptz NOT NULL,
    kind        text        NOT NULL CHECK (kind IN ('CHECK_CELL', 'PR_STATE')),
    pr          bigint      NOT NULL,
    subject     text        NOT NULL,
    before      text        NOT NULL,
    after       text        NOT NULL,
    recorded_at timestamptz NOT NULL
);
CREATE INDEX change_recorded_at ON change (recorded_at);
