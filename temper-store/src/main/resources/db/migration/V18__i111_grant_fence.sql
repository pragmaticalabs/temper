-- temper #111: a grant's COMMIT is fenced against its host's retirement. Applied once by MigrationRunner; never edit after
-- release (a later need is V19).
--
-- The scheduler refuses new grants from `retireAt - [scheduler] retireWindowSeconds` (#107), judged by its own clock when
-- it decides. Its timeout on the grant call only releases the host; it does not cancel the call, so a transaction that
-- hangs can still commit later. The fence therefore sits in the transaction itself: `PgUnitStore.grant` sets the
-- transaction-local `temper.grant_fence` (epoch milliseconds of `retireAt - window`) before its first statement, and this
-- deferred constraint trigger runs at COMMIT, once per unit the transaction moved QUEUED -> GRANTED. It raises when the
-- database clock at that moment is at or past the fence. `clock_timestamp()`, not `now()`: `now()` is the transaction's
-- START time, which a hung transaction would carry past the fence unchanged. The raise fails the COMMIT, so the whole
-- transaction rolls back: every unit it granted stays QUEUED with its attempt unchanged, and no `checkouts` row is left.
--
-- It fails CLOSED. Every QUEUED -> GRANTED transaction must set the fence: an epoch-millisecond value, or the explicit
-- sentinel 'none' (unfenced, deliberately: a host without `retireAt`). An UNSET fence (NULL, or '' - a pooled session
-- reads '' after any transaction that set it local) raises, and so does any value that is neither 'none' nor digits.
-- Why: a guarantee that holds only if every future GRANT path remembers to call set_config is a convention, not a
-- mechanism; a path that forgets must be refused here, not granted unfenced. (V18 was unreleased when this was decided,
-- so it is edited in place.)
--
-- Rollback (V18 is additive; no data changes): `DROP TRIGGER units_grant_fence ON units; DROP FUNCTION temper_grant_fence();
-- DROP FUNCTION temper_grant_fence_passed(timestamptz, bigint); DELETE FROM schema_migrations WHERE version = 18;` (a
-- build without V18 that finds its row aborts start with `MigrationNotInBuild`). Order matters: drop the trigger FIRST. Java that still sets `temper.grant_fence` is harmless without the trigger,
-- but Java reverted to a grant that sets nothing is REFUSED by the trigger (fail closed). Reverting the Java side means
-- restoring the four-argument `UnitStore.grant`, removing `PgUnitStore.fenced`/`GRANT_FENCE`, and `Scheduler.grantOn`
-- passing no fence (and `MigrationRunner` listing no V18).
--
-- The comparison is its own function so the `>=` edge can be pinned with fixed timestamps (clock_timestamp() cannot be
-- frozen): a fence of exactly `at` is passed (refused), one millisecond later is not. Exact arithmetic, no float.
CREATE FUNCTION temper_grant_fence_passed(at timestamptz, fence_ms bigint) RETURNS boolean
    LANGUAGE sql IMMUTABLE AS $$
    SELECT at >= timestamptz 'epoch' + fence_ms * interval '1 millisecond'
$$;

CREATE FUNCTION temper_grant_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    fence text := current_setting('temper.grant_fence', true);
BEGIN
    IF fence IS NULL OR fence = '' THEN
        RAISE EXCEPTION 'grant fence unset: unit % not granted to host %; a grant transaction must set temper.grant_fence (epoch ms, or none)',
            NEW.id, NEW.host;
    ELSIF fence = 'none' THEN
        RETURN NULL;
    ELSIF fence !~ '^[0-9]{1,18}$' THEN
        RAISE EXCEPTION 'grant fence malformed: unit % not granted to host %; temper.grant_fence is "%", want epoch ms or none',
            NEW.id, NEW.host, fence;
    ELSIF temper_grant_fence_passed(clock_timestamp(), fence::bigint) THEN
        RAISE EXCEPTION 'grant fence passed: unit % not granted to host %, which takes no grant committed from %',
            NEW.id, NEW.host, to_timestamp(fence::bigint / 1000.0);
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER units_grant_fence AFTER UPDATE OF state ON units
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.state = 'QUEUED' AND NEW.state = 'GRANTED')
    EXECUTE FUNCTION temper_grant_fence();
