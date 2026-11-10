-- temper M2: the judge, which temper build judged a job (m2-design §1.7, rulings R-M2-10 and R-M2-11). Applied once
-- by MigrationRunner on top of every earlier version (V5 is fix-supersede-key's); never edit after release.
--
-- jobs.judge is the judge of the controller that accepted the job (INSERT_JOB, REFUSED rows included), and
-- results.judge that of the controller that wrote the result (Finishing). Both are added with a constant default
-- and the default is then dropped: Postgres 16 records the value in the catalog for the existing rows, so no row is
-- UPDATEd and results' write-once trigger (V1) never fires. Existing rows read 'pre-judge', older than any judge,
-- because the newest judge at a SHA is chosen by job id. From here on every insert must name its judge.
ALTER TABLE jobs ADD COLUMN judge text NOT NULL DEFAULT 'pre-judge'
  CHECK (judge ~ '^([0-9a-f]{40}(-dirty)?|unjudged|pre-judge)$');
ALTER TABLE jobs ALTER COLUMN judge DROP DEFAULT;
ALTER TABLE results ADD COLUMN judge text NOT NULL DEFAULT 'pre-judge'
  CHECK (judge ~ '^([0-9a-f]{40}(-dirty)?|unjudged|pre-judge)$');
ALTER TABLE results ALTER COLUMN judge DROP DEFAULT;

-- units.granted_judge is the judge of the controller that granted the unit's current attempt (R-M2-11): set by the
-- grant, cleared by a requeue with the rest of the attempt, NULL for a unit never granted. A job is judged across a deploy
-- when one of its units' non-null grant judges is not its result's judge. Existing rows stay NULL (R-12, no conversion),
-- so a job granted before V6 is never judged across a deploy.
ALTER TABLE units ADD COLUMN granted_judge text
  CHECK (granted_judge ~ '^([0-9a-f]{40}(-dirty)?|unjudged)$');
