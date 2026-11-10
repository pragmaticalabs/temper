-- temper M1 manifest corrections (m1-design §14, rulings R-2 and R-7). Applied once by MigrationRunner on top of V2;
-- never edit after release.

-- R-2: a manifest row is required (it must report; absent, it is missing) or optional (it may report; neither
-- missing nor drift). V2 wrote only required rows.
ALTER TABLE manifests ADD COLUMN optional boolean NOT NULL DEFAULT false;

-- R-7: how many manifests rows the unit's computed manifest wrote. Retention deletes the rows and keeps
-- manifest_units, so fewer rows than written means pruned, and a computed manifest that wrote none means "no tests".
-- Backfilled from the rows present now, so a unit already pruned before V3 reads as written-empty; V2 and V3 ship
-- together in M1, so only a development database can hold such a unit.
ALTER TABLE manifest_units ADD COLUMN entry_count int NOT NULL DEFAULT 0 CHECK (entry_count >= 0);
UPDATE manifest_units mu SET entry_count = (SELECT count(*) FROM manifests m WHERE m.unit_id = mu.unit_id);
