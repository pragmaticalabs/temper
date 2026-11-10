-- temper M2: explicit rerun (m2-design §5.4, ruling R-M2-4). Applied once by MigrationRunner on top of every earlier
-- version (V5 is fix-supersede-key's, V7 is #26's; the number is fixed at merge); never edit after release.
--
-- jobs.rerun_of is the parent of a rerun job (recipe_kind 'rerun'), written by INSERT_JOB from the recipe's `of`, on
-- every rerun row, a refused request included; NULL for every other job. Existing rows get NULL, which is true of
-- them (R-12 needs no conversion). The parent's own row and its write-once result are never touched: the link is read
-- from the rerun's side (JobView.reruns, GateView.reruns), through the partial index. recipe_kind has no CHECK (V1).
ALTER TABLE jobs ADD COLUMN rerun_of bigint REFERENCES jobs(id);
CREATE INDEX jobs_rerun_of ON jobs (rerun_of) WHERE rerun_of IS NOT NULL;
