-- GitHub identities WP-4a: all four WP-4 tables in one migration, so that
-- WP-4b (dismissals, exemptions) and WP-5 (budget samples) add none of their own.
-- Applied once by MigrationRunner; never edit after release (a later need is V17).

-- The author set of a PR (§5.2): append-only, one row per lane, compared case-insensitively. There is no remove verb;
-- a wrong row is corrected by the owner in SQL (§11 Q2).
CREATE TABLE pr_authors (
    repo     text        NOT NULL CHECK (repo ~ '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'),
    pr       bigint      NOT NULL CHECK (pr > 0),
    lane     text        NOT NULL CHECK (lane ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    added_by text        NOT NULL CHECK (added_by ~ '^[!-~]{1,64}$'),
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (repo, pr, lane)
);
CREATE UNIQUE INDEX pr_authors_ci ON pr_authors (repo, pr, lower(lane));

-- One row per (repo, pr, sha, event) (§5.3 table `reviews`): `sending` is committed before the POST so a crash leaves
-- it behind; `posted` carries GitHub's review id; `failed` is retried on the same row (`attempts` + 1).
-- `carry_base_tip` / `carry_base_ref`: parent 2 of a proven carry and the PR's base ref the proof used (step 5d).
-- `proof`: the step-5 outcome (`fresh`, `carry-proven`). `dismiss_pending` is WP-4b's: the verdict id a superseded
-- APPROVE's dismissal is due for (§5.8); WP-4a only creates the column.
CREATE TABLE reviews (
    id                bigserial   PRIMARY KEY,
    repo              text        NOT NULL CHECK (repo ~ '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'),
    pr                bigint      NOT NULL CHECK (pr > 0),
    sha               char(40)    NOT NULL CHECK (sha ~ '^[0-9a-f]{40}$'),
    event             text        NOT NULL CHECK (event IN ('APPROVE', 'REQUEST_CHANGES')),
    state             text        NOT NULL CHECK (state IN ('sending', 'posted', 'failed')),
    verdict_id        bigint      NOT NULL REFERENCES verdicts (id),
    declared_as       text        NOT NULL CHECK (declared_as ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    attempts          int         NOT NULL DEFAULT 1 CHECK (attempts >= 1),
    github_review_id  bigint      NULL,
    carry_base_tip    char(40)    NULL CHECK (carry_base_tip ~ '^[0-9a-f]{40}$'),
    carry_base_ref    text        NULL CHECK (length(carry_base_ref) <= 255),
    proof             text        NULL CHECK (length(proof) <= 200),
    body_sha256       char(64)    NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    http_status       int         NULL,
    ratelimit_remaining int       NULL,
    failure           text        NULL CHECK (length(failure) <= 500),
    sending_at        timestamptz NOT NULL DEFAULT now(),
    posted_at         timestamptz NULL,
    dismiss_pending   bigint      NULL REFERENCES verdicts (id),
    UNIQUE (repo, pr, sha, event),
    CHECK (state <> 'posted' OR github_review_id IS NOT NULL),
    CHECK ((carry_base_tip IS NULL) = (carry_base_ref IS NULL))
);
CREATE INDEX reviews_github_id ON reviews (github_review_id) WHERE github_review_id IS NOT NULL;

-- WP-4b's (§5.8 review-exempt, arm then bind): the table is created here, nothing in WP-4a reads or writes it.
CREATE TABLE review_exemptions (
    id              bigserial   PRIMARY KEY,
    repo            text        NOT NULL CHECK (repo ~ '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'),
    pr              bigint      NOT NULL CHECK (pr > 0),
    sha             char(40)    NOT NULL CHECK (sha ~ '^[0-9a-f]{40}$'),
    reason          text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    armed_at        timestamptz NOT NULL DEFAULT now(),
    bound_review_id bigint      NULL,
    bound_at        timestamptz NULL,
    expired_at      timestamptz NULL,
    CHECK ((bound_review_id IS NULL) = (bound_at IS NULL))
);
CREATE INDEX review_exemptions_pr ON review_exemptions (repo, pr, id DESC);

-- WP-5's (§7.1): the latest sample per identity, upserted, plus the drift baselines (§7.4). Nothing in WP-4a uses it.
CREATE TABLE gh_budget (
    identity      text        PRIMARY KEY CHECK (identity IN ('kiln-app', 'controller-app', 'reviewer', 'owner')),
    lim           int         NOT NULL CHECK (lim >= 0),
    remaining     int         NOT NULL CHECK (remaining >= 0),
    used          int         NOT NULL CHECK (used >= 0),
    reset_at      timestamptz NOT NULL,
    observed_at   timestamptz NOT NULL,
    source        text        NOT NULL CHECK (length(source) <= 64),
    state         text        NOT NULL DEFAULT 'OK' CHECK (state IN ('OK', 'LOW', 'EXHAUSTED', 'STALE', 'CREDENTIAL', 'ANONYMOUS')),
    state_since   timestamptz NOT NULL DEFAULT now(),
    ruleset_hash  text        NULL,
    team_members  jsonb       NULL CHECK (jsonb_typeof(team_members) = 'array')
);
