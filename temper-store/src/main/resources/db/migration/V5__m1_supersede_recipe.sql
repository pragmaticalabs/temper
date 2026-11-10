-- temper M1: supersede by ref and full recipe, not recipe kind (spec §2.4, maintainer ruling R-M2-8). Applied once by
-- MigrationRunner on top of V4; never edit after release.

-- The recipe's canonical JSON (`Recipe.canonical()`, the recipe part of the dedup material), written at insert. Supersede
-- compares it as text, so two module-test jobs with different `pl` on one ref no longer cancel each other. Rows written
-- before V5 are backfilled with `recipe::text`, which never equals a canonical form: a job active across the upgrade is
-- not superseded by a later push (no backward compatibility until Aether GA, ruling R-12). Not indexed: a long mvn
-- recipe can exceed the btree entry limit; `jobs_active_ref` narrows by ref first.
ALTER TABLE jobs ADD COLUMN recipe_canonical text;
UPDATE jobs SET recipe_canonical = recipe::text;
ALTER TABLE jobs ALTER COLUMN recipe_canonical SET NOT NULL;
