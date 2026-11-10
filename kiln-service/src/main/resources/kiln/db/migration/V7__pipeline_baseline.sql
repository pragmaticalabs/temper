-- #102 pipeline baseline: every merged PR of the measured base since the start date, its GraphQL node verbatim (a
-- merged PR never changes, so it is never listed again), and its measurement once read. A complete measurement is
-- final and never read again; an incomplete one (a run still in progress) is read again on a later cycle.
--
-- A PR's reads are kept as they arrive, so a cycle that stops mid-PR (its request slice spent) resumes where it stopped
-- and never asks for a finished part again: each run listing once (`pr_runs_listed`, `queue_runs_listed`), each run
-- (`pipeline_run`, re-read alone while not completed) and each attempt's jobs (`pipeline_attempt`, re-read only when it
-- was stored before its run completed and the run has completed since).
CREATE TABLE pipeline_pr (
    number            bigint      PRIMARY KEY,
    merged_at         timestamptz NOT NULL,
    node              text        NOT NULL,
    listed_at         timestamptz NOT NULL,
    measure           jsonb,
    complete          boolean     NOT NULL DEFAULT false,
    measured_at       timestamptz,
    pr_runs_listed    boolean     NOT NULL DEFAULT false,
    queue_runs_listed boolean     NOT NULL DEFAULT false
);
CREATE INDEX pipeline_pr_merged_at ON pipeline_pr (merged_at);

CREATE TABLE pipeline_run (
    number      bigint  NOT NULL REFERENCES pipeline_pr (number),
    run         bigint  NOT NULL,
    workflow    text    NOT NULL,
    event       text    NOT NULL,
    head_sha    text    NOT NULL,
    head_branch text    NOT NULL,
    attempts    bigint  NOT NULL,
    completed   boolean NOT NULL,
    PRIMARY KEY (number, run)
);

CREATE TABLE pipeline_attempt (
    number  bigint  NOT NULL,
    run     bigint  NOT NULL,
    attempt bigint  NOT NULL,
    jobs    jsonb   NOT NULL,
    final   boolean NOT NULL,
    PRIMARY KEY (number, run, attempt),
    FOREIGN KEY (number, run) REFERENCES pipeline_run (number, run)
);
